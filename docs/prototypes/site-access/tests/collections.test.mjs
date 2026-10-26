// Browser-mock behavior evidence only (FINNS-19 R1 / requirements revision 2-3); does not validate real server security, persistence or concurrency.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from '../../../../frontend/node_modules/vite/dist/node/index.js'
import { JSDOM } from '../../../../frontend/node_modules/jsdom/lib/api.js'
import { fileURLToPath } from 'node:url'
const dom = new JSDOM('', { url: 'http://localhost/' }); dom.window.fetch = globalThis.fetch
for (const key of ['window', 'document', 'location', 'localStorage', 'Event', 'CustomEvent']) globalThis[key] = key === 'window' ? dom.window : dom.window[key]
const server = await createServer({ configFile: fileURLToPath(new URL('../vite.config.mjs', import.meta.url)), server: { middlewareMode: true, hmr: false } })
const db = await server.ssrLoadModule('/src/mock/db.js'), account = await server.ssrLoadModule('/src/mock/account.js'), ua = await server.ssrLoadModule('/src/mock/user-admin.js')
const mock = await server.ssrLoadModule('/src/mock/server.js'), pub = await server.ssrLoadModule('/src/mock/publishing.js'), col = await server.ssrLoadModule('/src/mock/collections.js'), cmd = await server.ssrLoadModule('/src/lib/collection-md.js')
const mutate = fn => { fn(db.getDb()); db.saveDb() }
const as = id => mutate(() => ua.simulateSignIn(id))
const doc = id => db.getDb().review.documents.find(d => d.id === id)
async function request(method, path, body, csrf = true) { const response = await window.fetch(path, { method, headers: csrf ? { 'X-XSRF-TOKEN': db.getDb().csrf } : {}, ...(body ? { body: JSON.stringify(body) } : {}) }); return { status: response.status, body: await response.json() } }
let serial = 0
const get = id => request('GET', `/api/me/collections/${id}/draft`)
const sections = (...entries) => [{ title: '章', description: '', entries }]
const note = text => ({ kind: 'NOTE', title: '', body: text, annotation: '' })
const fields = id => ({ name: doc(id).draft.title, description: doc(id).draft.summary, format: 'CUSTOM', audience: '', goal: '', visibility: doc(id).draft.visibility, sections: doc(id).draft.sections, revision: doc(id).draft.version })
const save = (id, patch = {}) => request('PUT', `/api/me/collections/${id}/draft`, { ...fields(id), ...patch })
const publish = (id, patch = {}, key = 'k' + (++serial)) => request('POST', `/api/me/collections/${id}/publish`, { ...fields(id), ...patch, requestId: key })
const pending = id => db.getDb().review.tasks.find(t => t.docId === id && t.status === 'PENDING')
const fresh = async () => (await request('POST', '/api/me/collections/drafts', { name: '新专题', templateKey: 'BLANK' })).body
beforeEach(() => { db.resetDb(); account.ensureAccount(); ua.simulateSignIn(3); mock.installMockServer(); pub.ensurePublishing(); col.ensureCollections(); db.saveDb() })
after(async () => { await server.close(); dom.window.close() })

test('COL-A01 create from template keeps a private draft; saving never publishes or detects; incomplete drafts save', async () => {
  const c = await fresh(); assert.equal(c.status.work, 'PRIVATE'); assert.equal(c.published, null)
  const before = db.getDb().review.tasks.length
  const s = await save(c.id, { sections: sections(note('这里有演示高敏词，只是保存')) }); assert.equal(s.status, 200)
  assert.equal(db.getDb().review.tasks.length, before); assert.equal(doc(c.id).published, null)
  assert.equal((await request('POST', '/api/me/collections/drafts', { name: ' ' })).status, 400)
  as(1); assert.equal((await get(c.id)).status, 404); as(null); assert.equal((await get(c.id)).status, 401)
})

test('COL-A02/A03 first publish uses the same three-level routing with no first-review rule; retry with the same request id is idempotent', async () => {
  const c = await fresh(); const none = await publish(c.id, { sections: sections(note('干净内容')) }); assert.equal(none.body.outcome, 'PUBLISHED'); assert.equal(doc(c.id).approvedOnce, true)
  const low = await publish(c.id, { sections: sections(note('演示低敏词')) }); assert.equal(low.body.level, 'LOW')
  const mid = await publish(c.id, { sections: sections(note('演示拦截词')) }, 'same'); assert.equal(mid.body.outcome, 'PENDING')
  const again = await publish(c.id, { sections: sections(note('演示拦截词')) }, 'same'); assert.equal(again.body.replay, true); assert.equal(db.getDb().review.tasks.filter(t => t.docId === c.id && t.status === 'PENDING').length, 1)
  const high = await publish(c.id, { sections: sections(note('演示高敏词')) }); assert.equal(high.body.outcome, 'AUTO_REJECTED'); assert.ok(high.body.content.flows[0].events.every(e => !e.detail))
  db.getDb().review.settings.detectFailure = true; db.saveDb()
  const c3 = await fresh(); assert.equal((await publish(c3.id, { sections: sections(note('x')) })).status, 503); assert.equal(doc(c3.id).published, null)
})

test('COL-A04/A05 three versions coexist: frozen submission is not replaced by later edits; pending update keeps the old public version', async () => {
  const v = (await get(1901)).body; assert.equal(v.publicVisible, true); assert.ok(v.submitted); assert.equal(v.status.work, 'REVIEWING'); assert.ok(v.revision > v.submitted.version)
  const frozen = pending(1901).snapshot.sections.length; await save(1901, { sections: sections(note('再改一次')) }); assert.equal(pending(1901).snapshot.sections.length, frozen)
  assert.equal((await request('GET', '/api/collections/1901/outline')).body.sections.length, 2, 'readers still see the old public version')
  const re = await publish(1901, { sections: sections(note('干净')) }); assert.equal(re.body.outcome, 'PUBLISHED'); assert.equal(pending(1901), undefined)
})

test('COL-A06 cancel keeps the old version; withdraw stops all public reads and keeps the private draft; recheck cannot be cancelled', async () => {
  const cancel = await request('POST', '/api/me/collections/1901/cancel', {}); assert.equal(cancel.status, 200); assert.equal(pending(1901), undefined); assert.equal(doc(1901).published.title, '独立开发者上手路线')
  assert.equal((await request('POST', '/api/me/collections/1901/cancel', {})).status, 409)
  assert.equal((await request('POST', '/api/me/collections/1907/cancel', {})).status, 409)
  assert.equal((await request('POST', '/api/me/collections/1903/withdraw', {})).status, 400, 'needs confirmation')
  assert.equal((await request('POST', '/api/me/collections/1903/withdraw', { confirm: true })).status, 200)
  assert.equal((await request('GET', '/api/collections/1903/outline')).status, 404); assert.ok(doc(1903).draft.sections.length)
  assert.ok(!(await request('GET', '/api/collections?size=50')).body.items.some(i => i.id === 1903))
  assert.equal((await publish(1903)).body.outcome, 'PUBLISHED'); assert.equal((await request('GET', '/api/collections/1903/outline')).status, 200)
})

test('COL-A07 manual takedown is not lifted by save, publish or withdraw', async () => {
  assert.equal((await save(1906, { name: '改名' })).status, 200); assert.equal(doc(1906).down, true)
  assert.equal((await publish(1906)).status, 409); assert.equal((await request('POST', '/api/me/collections/1906/withdraw', { confirm: true })).status, 409); assert.equal(doc(1906).down, true)
})

test('COL-A08 duplicates are new private collections inheriting no task/publication/approval; structure-only drops entries', async () => {
  const a = await request('POST', '/api/me/collections/1903/duplicate', { structureOnly: false }), b = await request('POST', '/api/me/collections/1903/duplicate', { structureOnly: true })
  assert.notEqual(a.body.id, 1903); assert.equal(doc(a.body.id).published, null); assert.equal(doc(a.body.id).approvedOnce, false); assert.equal(doc(a.body.id).flows.length, 0)
  assert.equal(doc(a.body.id).draft.sections[0].entries.length, 1); assert.equal(doc(b.body.id).draft.sections[0].entries.length, 0)
  as(2); assert.equal((await request('POST', '/api/me/collections/1903/duplicate', {})).status, 404)
})

test('COL-A09 drafts may keep invalid references; publish is refused with a locating message and the draft stays', async () => {
  const v = (await get(1902)).body; assert.equal(v.hasInvalidRefs, true); assert.equal(v.refs[1802].ok, false)
  const p = await publish(1902); assert.equal(p.status, 400); assert.match(p.body.message, /处理/); assert.equal(doc(1902).published, null)
})

test('COL-A09/A10 a reference that turns invalid after publish is a bare placeholder for readers only; the rest stays readable', async () => {
  const out = (await request('GET', '/api/collections/1908/outline')).body; const entries = out.sections[0].entries
  const bad = entries.find(e => e.kind === 'POST' && !e.postId); assert.ok(bad); assert.equal(bad.postTitle, '原文已不可用'); for (const k of ['title', 'annotation', 'postSummary', 'postAuthor']) assert.equal(bad[k], '')
  assert.ok(!JSON.stringify(out).includes('一篇后来被撤回的文章')); assert.ok(entries.some(e => e.postId === 1803) && entries.some(e => e.kind === 'NOTE'))
  doc(1803).withdrawn = true; db.saveDb(); assert.equal((await get(1908)).body.refs[1803].ok, false)
  assert.equal((await publish(1908)).status, 400, 'updating while a reference is invalid needs fixing first')
})

test('COL-A11 visibility: a draft change does not alter the old public version; restricted reads need the permission', async () => {
  await save(1903, { visibility: 'RESTRICTED' }); assert.equal((await request('GET', '/api/collections/1903/outline')).status, 200); assert.equal(doc(1903).published.visibility, 'PUBLIC')
  as(4); assert.equal((await request('GET', '/api/collections/1904/outline')).status, 403); as(null); assert.equal((await request('GET', '/api/collections/1904/outline')).status, 401)
  as(4); assert.equal((await request('POST', '/api/me/collections/drafts', { name: 'x' })).status, 403)
})

test('COL-A12 non-authors cannot write', async () => {
  as(2); for (const [m, p] of [['PUT', '/api/me/collections/1903/draft'], ['POST', '/api/me/collections/1903/publish'], ['DELETE', '/api/me/collections/1903']]) assert.equal((await request(m, p, {})).status, 404)
})

test('COL-A13/A14 stale revisions conflict and keep input; a failed write changes nothing; expiry is 401; limits refuse with input kept', async () => {
  const stale = await save(1903, { revision: 99 }); assert.equal(stale.status, 409); assert.match(stale.body.message, /已保留/)
  db.getDb().review.settings.failWriteOnce = true; db.saveDb(); const f = await save(1903, { name: '失败的改名' }); assert.equal(f.status, 503); assert.notEqual(doc(1903).draft.title, '失败的改名')
  assert.equal((await save(1903, { name: 'x'.repeat(81) })).status, 400); assert.equal((await save(1903, { description: 'd'.repeat(261) })).status, 400)
  as(null); assert.equal((await save(1903)).status, 401)
})

test('COL-D delete private drafts: only never-published, no pending task', async () => {
  const c = await fresh(); assert.equal((await request('DELETE', `/api/me/collections/${c.id}`)).status, 200); assert.equal((await get(c.id)).status, 404)
  for (const id of [1903, 1905, 1901, 1907]) assert.equal((await request('DELETE', `/api/me/collections/${id}`)).status, 409, String(id))
  assert.ok(!(await request('GET', '/api/me/collections?size=50')).body.items.some(i => i.id === c.id))
})

test('COL-A06 selection: withdrawing hides it from readers while the record is kept; republishing restores it', async () => {
  as(2); const ids = async () => (await request('GET', '/api/me/collections/selected')).body.items.map(i => i.id)
  assert.ok((await ids()).includes(1901)); assert.ok(!(await ids()).includes(1905), 'already withdrawn: hidden')
  as(3); assert.equal((await request('POST', '/api/me/collections/1901/withdraw', { confirm: true })).status, 200)
  as(2); assert.ok(!(await ids()).includes(1901)); assert.ok(db.getDb().selections[2].includes(1901), 'record kept')
  as(3); assert.equal((await publish(1901, { sections: sections(note('干净')) })).body.outcome, 'PUBLISHED')
  as(2); assert.ok((await ids()).includes(1901), 'restored after republish')
})

test('COL-A15 list: search, state filters, pagination', async () => {
  const all = (await request('GET', '/api/me/collections')).body; assert.equal(all.size, 5); assert.ok(all.total >= 8); assert.equal(all.items.length, 5)
  assert.ok((await request('GET', '/api/me/collections?page=1')).body.items.length >= 3)
  assert.ok((await request('GET', '/api/me/collections?state=LIVE')).body.items.every(i => i.publicVisible))
  const off = (await request('GET', '/api/me/collections?state=OFF')).body.items.map(i => i.id); assert.ok(off.includes(1905) && off.includes(1906))
  assert.equal((await request('GET', '/api/me/collections?q=' + encodeURIComponent('部署'))).body.items[0].id, 1903)
  assert.ok((await request('GET', '/api/me/collections?state=REVIEW')).body.items.every(i => ['REVIEWING', 'RECHECK', 'REJECTED'].includes(i.status.work)))
})

test('COL-A01 import: preview and commit create a private draft (idempotent); append lands in the working draft unpublished', async () => {
  const pv = await request('POST', '/api/me/collection-imports/preview', { text: '# 导入标题\n## 第一章\n内容一\n内容二' }); assert.equal(pv.body.document.name, '导入标题'); assert.equal(pv.body.document.sections[0].entries.length, 2)
  const a = await request('POST', '/api/me/collection-imports/commit', { idempotencyKey: 'x', document: pv.body.document }), b = await request('POST', '/api/me/collection-imports/commit', { idempotencyKey: 'x', document: pv.body.document })
  assert.equal(a.body.id, b.body.id); assert.equal(doc(a.body.id).published, null)
  const ap = await request('POST', `/api/me/collections/${a.body.id}/imports`, { document: { ...pv.body.document, revision: doc(a.body.id).draft.version } }); assert.equal(ap.status, 200); assert.equal(doc(a.body.id).draft.sections.length, 2); assert.equal(doc(a.body.id).published, null)
})

test('picker lists only the author\'s currently public articles', async () => {
  const items = (await request('GET', '/api/me/posts')).body.items.map(i => i.id); assert.ok(items.includes(1803)); assert.ok(!items.includes(1802) && !items.includes(1804) && !items.includes(1805))
})

test('markdown editing: round trip keeps chapters, intro and article references; headings split chapters; outline gives offsets', async () => {
  const d = (await get(1908)).body.draft, md = cmd.toMarkdown(d), back = cmd.fromMarkdown(md)
  assert.match(md, /^## 文章/m); assert.match(md, /\[\[文章:1803\|/); assert.equal(back.sections.length, d.sections.length)
  assert.deepEqual(back.sections[0].entries.map(e => e.kind), ['POST', 'POST', 'NOTE']); assert.equal(back.sections[0].entries[1].postId, 1804)
  const p = cmd.fromMarkdown(['简介一行', '', '## A', '正文 **粗**', '', '## B', '[[文章:1803|x]]', '后记'].join(String.fromCharCode(10))); assert.equal(p.description, '简介一行'); assert.deepEqual(p.sections.map(s => s.title), ['A', 'B'])
  assert.deepEqual(p.sections[1].entries.map(e => e.kind), ['POST', 'NOTE']); assert.equal(cmd.outlineOf(['x', '## A', 'y', '## B'].join(String.fromCharCode(10)))[1].offset, 9)
})

test('attribute filters for both lists: public state, work state, visibility, invalid references, name', async () => {
  const ids = async q => (await request('GET', '/api/me/collections?size=50&' + q)).body.items.map(i => i.id)
  assert.deepEqual((await ids('pub=WITHDRAWN')), [1905]); assert.ok((await ids('work=RECHECK')).includes(1907)); assert.deepEqual(await ids('vis=RESTRICTED'), [1904])
  assert.ok((await ids('bad=1')).includes(1902) && (await ids('bad=1')).includes(1908)); assert.deepEqual(await ids('name=' + encodeURIComponent('部署') + '&pub=LIVE'), [1903])
  const art = async q => (await request('GET', '/api/content/mine?' + q)).body.items.map(i => i.id)
  assert.ok((await art('pub=DOWN')).includes(1805)); assert.deepEqual(await art('title=' + encodeURIComponent('博客')), [1803]); assert.ok((await art('work=RECHECK')).includes(1806))
})
