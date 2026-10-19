// Behavior checks of the browser mock, not evidence of production authorization or concurrency.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from '../../../../frontend/node_modules/vite/dist/node/index.js'
import { JSDOM } from '../../../../frontend/node_modules/jsdom/lib/api.js'
import { fileURLToPath } from 'node:url'
const dom = new JSDOM('', { url: 'http://localhost/' })
dom.window.fetch = globalThis.fetch
for (const key of ['window', 'document', 'location', 'localStorage', 'Event', 'CustomEvent']) globalThis[key] = key === 'window' ? dom.window : dom.window[key]
const server = await createServer({ configFile: fileURLToPath(new URL('../vite.config.mjs', import.meta.url)), server: { middlewareMode: true, hmr: false } })
const db = await server.ssrLoadModule('/src/mock/db.js'), account = await server.ssrLoadModule('/src/mock/account.js'), ua = await server.ssrLoadModule('/src/mock/user-admin.js'), mock = await server.ssrLoadModule('/src/mock/server.js')
const mutate = fn => { fn(db.getDb()); db.saveDb() }
async function request(method, path, body, csrf = true) {
  const response = await window.fetch(path, { method, headers: csrf ? { 'X-XSRF-TOKEN': db.getDb().csrf } : {}, ...(body ? { body: JSON.stringify(body) } : {}) })
  return { status: response.status, body: await response.json() }
}
const change = (id, status, overrides = {}) => request('PATCH', `/api/admin/users/${id}/status`, { status, expectedVersion: db.getDb().users.find(u => u.id === id).statusVersion, note: '原型行为验证', ...overrides })
beforeEach(() => { db.resetDb(); account.ensureAccount(); ua.simulateSignIn(1); db.saveDb(); mock.installMockServer() })
after(async () => { await server.close(); dom.window.close() })

test('status combines with search, role and pagination; public response does not expose reasons', async () => {
  const result = await request('GET', '/api/admin/users?status=DISABLED&q=user7&role=MEMBER&size=1&page=0')
  assert.equal(result.status, 200); assert.equal(result.body.total, 1); assert.equal(result.body.items[0].id, 7)
  assert.equal(result.body.items[0].accountStatus, 'DISABLED'); assert.equal(result.body.managerCount, 2)
  assert.equal((await request('GET', '/api/admin/users?status=ACTIVE&q=user7')).body.total, 0)
})
test('requires a live permitted session and CSRF for direct status calls', async () => {
  assert.equal((await request('PATCH', '/api/admin/users/3/status', { status: 'DISABLED', note: '验证', expectedVersion: 1 }, false)).status, 403)
  mutate(() => ua.simulateSignIn(3)); assert.equal((await change(2, 'DISABLED')).status, 403)
  mutate(() => ua.simulateSignIn(null)); assert.equal((await change(3, 'DISABLED')).status, 401)
  assert.equal(db.getDb().users.find(u => u.id === 3).accountStatus, 'ACTIVE')
})
test('reason validation and save failure do not change account, role, content or session epoch', async () => {
  const before = structuredClone(db.getDb().users.find(u => u.id === 3))
  for (const note of ['   ', '字'.repeat(201)]) assert.equal((await change(3, 'DISABLED', { note })).status, 400)
  mutate(d => { d.userAdmin.failSaveOnce = true })
  assert.equal((await change(3, 'DISABLED')).status, 503)
  assert.deepEqual(db.getDb().users.find(u => u.id === 3), before)
  assert.equal((await change(3, 'DISABLED', { note: '字'.repeat(200) })).status, 200)
})
test('disable revokes old sessions without changing published content; restore never revives them', async () => {
  const before = JSON.stringify([db.getDb().posts, db.getDb().collections]), epoch = db.getDb().users.find(u => u.id === 3).sessionEpoch
  assert.equal((await change(3, 'DISABLED')).status, 200)
  assert.equal(JSON.stringify([db.getDb().posts, db.getDb().collections]), before)
  assert.ok(db.getDb().account.devices[3].every(d => !d.active))
  mutate(d => { d.session = { userId: 3, epoch } }); assert.equal((await request('GET', '/api/auth/me')).status, 401)
  mutate(() => ua.simulateSignIn(1)); assert.equal((await change(3, 'ACTIVE')).status, 200)
  mutate(d => { d.session = { userId: 3, epoch } }); assert.equal((await request('GET', '/api/auth/me')).status, 401)
  assert.equal((await request('POST', '/api/auth/login', { email: 'yuan@finns.test', password: 'mock-only' })).status, 200)
})
test('disabled login only reveals status after simulated password verification; recovery leaves status disabled', async () => {
  await change(3, 'DISABLED')
  const login = { email: 'yuan@finns.test', password: 'mock-only' }
  const blocked = await request('POST', '/api/auth/login', login)
  assert.equal(blocked.status, 403); assert.equal(blocked.body.message, '账号已停用，请联系站点管理员')
  mutate(d => { d.account.wrongLogin = true })
  assert.equal((await request('POST', '/api/auth/login', login)).body.message, '邮箱或密码不正确。')
  const recovery = await request('POST', '/api/auth/request-link', { email: login.email, purpose: 'reset' })
  assert.equal(recovery.status, 200)
  const token = db.getDb().account.links[0].id
  assert.equal((await request('POST', '/api/auth/reset', { token, password: 'MockNewPass42', confirmPassword: 'MockNewPass42' })).status, 200)
  assert.equal(db.getDb().users.find(u => u.id === 3).accountStatus, 'DISABLED')
  assert.equal(JSON.stringify(db.getDb()).includes('MockNewPass42'), false)
})
test('sole owner is protected, administrators cannot manage their peers, owner can manage administrators', async () => {
  assert.equal((await change(1, 'DISABLED')).status, 422)
  mutate(d => { d.users.find(u => u.id === 3).role = 'ADMIN'; ua.simulateSignIn(2) })
  assert.equal((await change(3, 'DISABLED')).status, 403)
  assert.equal((await change(1, 'DISABLED')).status, 403)
  assert.equal((await change(4, 'DISABLED')).status, 200)
  assert.equal((await change(4, 'ACTIVE')).status, 200)
  const blocked = await request('PATCH', '/api/admin/users/3/role', { role: 'MEMBER', expectedRole: 'ADMIN' })
  assert.equal(blocked.status, 403)
  mutate(() => ua.simulateSignIn(1))
  assert.equal((await change(3, 'DISABLED')).status, 200)
  assert.equal((await change(3, 'ACTIVE')).status, 200)
  assert.equal(ua.normalManagerCount(), 3)
})
test('administrator cannot grant admin status, transfer ownership, or copy admin capabilities', async () => {
  mutate(() => ua.simulateSignIn(2))
  assert.equal((await request('PATCH', '/api/admin/users/3/role', { role: 'ADMIN', expectedRole: 'MEMBER' })).status, 403)
  assert.equal((await request('PATCH', '/api/admin/users/3/role', { role: 'OWNER', expectedRole: 'MEMBER' })).status, 403)
  let roles = (await request('GET', '/api/admin/roles')).body
  assert.equal(roles.roles.find(r => r.key === 'OWNER').assignable, false)
  assert.equal(roles.roles.find(r => r.key === 'ADMIN').assignable, false)
  assert.equal((await request('POST', '/api/admin/roles/definitions', { version: roles.version, label: '提权测试', copyFrom: 'ADMIN' })).status, 403)
  const permissions = Object.fromEntries(roles.roles.map(r => [r.key, [...r.permissions]]))
  permissions.MEMBER.push('admin.roles')
  assert.equal((await request('PUT', '/api/admin/roles', { version: roles.version, permissions })).status, 403)
  permissions.MEMBER = permissions.MEMBER.filter(p => p !== 'admin.roles')
  permissions.ADMIN = permissions.ADMIN.filter(p => p !== 'post.create')
  assert.equal((await request('PUT', '/api/admin/roles', { version: roles.version, permissions })).status, 403)
  mutate(() => ua.simulateSignIn(1))
  assert.equal((await request('PATCH', '/api/admin/users/3/role', { role: 'ADMIN', expectedRole: 'MEMBER' })).status, 200)
  assert.equal((await request('PATCH', '/api/admin/users/1/role', { role: 'ADMIN', expectedRole: 'OWNER' })).status, 403)
  roles = (await request('GET', '/api/admin/roles')).body
  assert.equal((await request('POST', '/api/admin/roles/definitions', { version: roles.version, label: '复制站长', copyFrom: 'OWNER' })).status, 403)
  assert.equal((await request('DELETE', '/api/admin/roles/definitions/OWNER', { version: roles.version })).status, 403)
  assert.equal((await request('PATCH', '/api/admin/roles/definitions/OWNER', { version: roles.version, label: '新名字' })).status, 403)
})
test('stale update conflicts, repeat update is a no-op, audit exposes reason and result to audit permission only', async () => {
  mutate(d => { d.userAdmin.conflictOnce = true })
  assert.equal((await change(3, 'DISABLED')).status, 409)
  assert.equal(db.getDb().users.find(u => u.id === 3).accountStatus, 'ACTIVE')
  await change(3, 'DISABLED')
  const epoch = db.getDb().users.find(u => u.id === 3).sessionEpoch
  const count = db.getDb().audit.length
  await change(3, 'DISABLED', { expectedVersion: 1 })
  assert.equal(db.getDb().audit.length, count); assert.equal(db.getDb().users.find(u => u.id === 3).sessionEpoch, epoch)
  const audit = await request('GET', '/api/admin/audit?type=ACCOUNT_STATUS&q=原型行为验证')
  assert.equal(audit.body.total, 2); assert.equal(audit.body.items[0].outcome, 'SUCCESS'); assert.equal(audit.body.items[1].outcome, 'REJECTED')
  mutate(d => { ua.simulateSignIn(2); d.permissions.ADMIN = d.permissions.ADMIN.filter(p => p !== 'admin.audit') })
  assert.equal((await request('GET', '/api/admin/users')).status, 200)
  assert.equal((await request('GET', '/api/admin/audit')).status, 403)
})
test('list failure can retry', async () => {
  mutate(d => { d.userAdmin.failLoadOnce = true })
  assert.equal((await request('GET', '/api/admin/users')).status, 503)
  assert.equal((await request('GET', '/api/admin/users')).status, 200)
})

 test('session refresh observes committed status when UI listeners reread mock storage', async () => {
  let pending
  const refreshSnapshot = () => mock.proto.db()
  const refreshSession = () => { pending = request('GET', '/api/auth/me') }
  window.addEventListener('finns-proto-change', refreshSnapshot)
  window.addEventListener('finns-account-session', refreshSession)
  try {
    assert.equal((await change(3, 'DISABLED')).status, 200)
    await pending
    assert.equal((await request('GET', '/api/admin/users?q=yuan')).body.items[0].accountStatus, 'DISABLED')
    assert.equal(db.reloadDb().users.find(u => u.id === 3).accountStatus, 'DISABLED')
  } finally { window.removeEventListener('finns-proto-change', refreshSnapshot); window.removeEventListener('finns-account-session', refreshSession) }
})

const transfer = (extra = {}, csrf = true) => request('POST', '/api/admin/ownership', { targetId: 2, expectedOwnerId: 1, expectedVersion: db.getDb().userAdmin.ownerVersion, note: '管理职责交接', confirmEmail: 'zhou@finns.test', ...extra }, csrf)

test('ownership handover atomically swaps the unique owner and downgrades the former owner session', async () => {
  const options = await request('GET', '/api/admin/ownership')
  assert.equal(options.status, 200); assert.deepEqual(options.body.candidates.map(u => u.id), [2])
  const content = JSON.stringify([db.getDb().posts, db.getDb().collections])
  const epochs = db.getDb().users.slice(0, 2).map(u => u.sessionEpoch)
  assert.equal((await transfer()).status, 200)
  const d = db.getDb()
  assert.equal(d.siteOwnerId, 2); assert.deepEqual(d.users.filter(u => u.role === 'OWNER').map(u => u.id), [2])
  assert.equal(d.users.find(u => u.id === 1).role, 'ADMIN')
  assert.equal((await request('GET', '/api/auth/me')).body.role, 'ADMIN')
  assert.equal((await request('GET', '/api/admin/ownership')).status, 403)
  assert.equal((await change(2, 'DISABLED')).status, 403)
  assert.equal(JSON.stringify([d.posts, d.collections]), content)
  assert.equal(d.users.find(u => u.id === 1).sessionEpoch, epochs[0] + 1)
  assert.equal(d.users.find(u => u.id === 2).sessionEpoch, epochs[1] + 1)
  mutate(d => { d.session = { userId: 2, epoch: epochs[1] } })
  assert.equal((await request('GET', '/api/auth/me')).status, 401)
  mutate(() => ua.simulateSignIn(2))
  assert.equal((await request('GET', '/api/auth/me')).body.role, 'OWNER')
  const audit = await request('GET', '/api/admin/audit?type=OWNER_TRANSFER')
  assert.equal(audit.body.total, 1); assert.equal(audit.body.items[0].outcome, 'SUCCESS')
  assert.equal(audit.body.items[0].ownerBeforeName, '你')
})

test('ownership handover rejects invalid recipients, missing confirmation, stale data and partial failure', async () => {
  const original = structuredClone(db.getDb().users)
  for (const targetId of [1, 3, 7, 999]) assert.equal((await transfer({targetId})).status, 422)
  assert.equal((await transfer({ note: ' ' })).status, 400)
  assert.equal((await transfer({ note: '字'.repeat(201) })).status, 400)
  assert.equal((await transfer({ confirmEmail: 'other@finns.test' })).status, 400)
  assert.equal((await transfer({ expectedOwnerId: 99 })).status, 409)
  assert.equal((await transfer({ expectedVersion: 0 })).status, 409)
  assert.equal((await transfer({}, false)).status, 403)
  mutate(d => { d.userAdmin.failSaveOnce = true })
  assert.equal((await transfer()).status, 503)
  assert.deepEqual(db.getDb().users, original); assert.equal(db.getDb().siteOwnerId, 1)
  assert.equal(db.getDb().audit[0].outcome, 'FAILED')
  mutate(d => { d.users.find(u => u.id === 2).accountStatus = 'DISABLED' })
  assert.equal((await transfer()).status, 422)
})

test('concurrent or repeated ownership requests cannot produce multiple owners or grant former-owner authority', async () => {
  mutate(d => { d.userAdmin.conflictOnce = true })
  const stale = db.getDb().userAdmin.ownerVersion
  assert.equal((await transfer()).status, 409)
  assert.equal((await transfer({ expectedVersion: stale })).status, 409)
  assert.equal(db.getDb().siteOwnerId, 1)
  const version = db.getDb().userAdmin.ownerVersion
  const responses = await Promise.all([transfer({expectedVersion: version}), transfer({expectedVersion: version})])
  assert.deepEqual(responses.map(r => r.status).sort(), [200,403])
  assert.equal((await transfer()).status, 403)
  assert.deepEqual(db.getDb().users.filter(u => u.role === 'OWNER').map(u => u.id), [2])
  assert.equal(db.getDb().audit.filter(a => a.type === 'OWNER_TRANSFER' && a.outcome === 'SUCCESS').length, 1)
  mutate(() => ua.simulateSignIn(3))
  assert.equal((await transfer()).status, 403)
})
