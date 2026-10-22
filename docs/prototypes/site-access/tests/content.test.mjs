// Browser-mock behavior evidence only (FINNS-18 R1 / requirements revision 2); does not validate real server security, persistence or concurrency.
import { test, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from '../../../../frontend/node_modules/vite/dist/node/index.js'
import { JSDOM } from '../../../../frontend/node_modules/jsdom/lib/api.js'
import { fileURLToPath } from 'node:url'
const dom = new JSDOM('', { url: 'http://localhost/' }); dom.window.fetch = globalThis.fetch
for (const key of ['window', 'document', 'location', 'localStorage', 'Event', 'CustomEvent']) globalThis[key] = key === 'window' ? dom.window : dom.window[key]
const server = await createServer({ configFile: fileURLToPath(new URL('../vite.config.mjs', import.meta.url)), server: { middlewareMode: true, hmr: false } })
const db = await server.ssrLoadModule('/src/mock/db.js'), account = await server.ssrLoadModule('/src/mock/account.js'), ua = await server.ssrLoadModule('/src/mock/user-admin.js')
const mock = await server.ssrLoadModule('/src/mock/server.js'), pub = await server.ssrLoadModule('/src/mock/publishing.js'), md = await server.ssrLoadModule('/src/lib/safe-markdown.js')
const mutate = fn => { fn(db.getDb()); db.saveDb() }
const as = id => mutate(() => ua.simulateSignIn(id))
const doc = id => db.getDb().review.documents.find(d => d.id === id)
async function request(method, path, body, csrf = true) { const response = await window.fetch(path, { method, headers: csrf ? { 'X-XSRF-TOKEN': db.getDb().csrf } : {}, ...(body ? { body: JSON.stringify(body) } : {}) }); return { status: response.status, body: await response.json() } }
let serial = 0
const mine = id => request('GET', '/api/content/mine/' + id)
const create = (type, fields) => request('POST', '/api/content/mine', { type, ...fields })
const save = (id, fields) => request('PUT', '/api/content/mine/' + id, { title: doc(id).draft.title, summary: doc(id).draft.summary, body: doc(id).draft.body, ...fields, expectedVersion: doc(id).draft.version })
const publish = (id, fields = {}, key = 'req-' + (++serial)) => request('POST', `/api/content/mine/${id}/publish`, { title: doc(id).draft.title, summary: doc(id).draft.summary, body: doc(id).draft.body, ...fields, expectedVersion: doc(id).draft.version, requestId: key })
const pending = id => db.getDb().review.tasks.find(t => t.docId === id && t.status === 'PENDING')
const full = { title: '标题', summary: '摘要', body: '正文内容' }
beforeEach(() => { db.resetDb(); account.ensureAccount(); ua.simulateSignIn(3); mock.installMockServer(); pub.ensurePublishing(); db.saveDb() })
after(async () => { await server.close(); dom.window.close() })

test('CT-A01/A02 article drafts are saved privately; incomplete drafts save, fully empty ones are not created, other accounts cannot see them', async () => {
  const article = await create('ARTICLE', { title: '只有标题' }); assert.equal(article.status, 200); assert.equal(article.body.status.work, 'PRIVATE'); assert.equal(article.body.published, null)
  const bodyOnly = await create('ARTICLE', { body: '只有正文的文章' }); assert.equal(bodyOnly.status, 200); assert.equal(bodyOnly.body.type, 'ARTICLE')
  const forced = await create('TOPIC', full); assert.equal(forced.body.type, 'ARTICLE', 'the ordinary-topic type no longer exists in the prototype')
  const empty = await create('ARTICLE', { title: '  ', summary: '', body: '\n' }); assert.equal(empty.status, 400)
  const list = await request('GET', '/api/content/mine'); assert.ok(list.body.items.some(i => i.id === bodyOnly.body.id))
  as(1); assert.equal((await mine(article.body.id)).status, 404); assert.ok(!(await request('GET', '/api/content/mine?type=ARTICLE')).body.items.some(i => i.id === article.body.id))
  as(null); assert.equal((await mine(article.body.id)).status, 401)
  as(4); assert.equal((await create('ARTICLE', full)).status, 403)
})

test('CT-A02 publish requires title, summary and body; 120/280/20000 limits are counted by characters and refused with the draft kept', async () => {
  const c = await create('ARTICLE', { title: '缺摘要', body: '正文' }); const id = c.body.id
  const lacking = await publish(id); assert.equal(lacking.status, 400); assert.match(lacking.body.message, /摘要/); assert.equal(doc(id).published, null)
  const long = await save(id, { title: '题'.repeat(121) }); assert.equal(long.status, 400); assert.match(long.body.message, /标题/)
  assert.equal((await save(id, { title: '😀'.repeat(120) })).status, 200, 'emoji counts as one character each')
  assert.equal((await save(id, { summary: 'x'.repeat(281) })).status, 400); assert.equal((await save(id, { body: 'x'.repeat(20001) })).status, 400)
  assert.equal((await save(id, { summary: 's'.repeat(280), body: 'b'.repeat(20000) })).status, 200)
  assert.equal(pub.measure('𠮷a😀'), 3)
})

test('CT-A01 saving never publishes, never triggers detection, and keeps the old public version unchanged', async () => {
  const before = db.getDb().review.tasks.length
  const hit = await save(1803, { body: '这里有演示高敏词，只是保存' }); assert.equal(hit.status, 200)
  assert.equal(db.getDb().review.tasks.length, before); assert.equal(doc(1803).published.body.includes('演示高敏词'), false)
  assert.equal((await request('GET', '/api/content/public/1803')).body.body.includes('演示高敏词'), false)
  assert.equal(hit.body.status.work, 'MODIFIED')
})

test('CT-A05 publish saves current input first, then routes by the highest level; retry with the same request id is idempotent', async () => {
  const c = (await create('ARTICLE', full)).body; const id = c.id
  const none = await publish(id, { body: '干净的正文' }); assert.equal(none.body.outcome, 'PUBLISHED'); assert.equal(doc(id).draft.body, '干净的正文')
  const low = await publish(id, { body: '这里有演示低敏词' }); assert.equal(low.body.outcome, 'PUBLISHED'); assert.equal(low.body.level, 'LOW')
  const key = 'stable'; const mid = await publish(id, { body: '演示拦截词 + 演示低敏词' }, key); assert.equal(mid.body.outcome, 'PENDING')
  const again = await publish(id, { body: '演示拦截词 + 演示低敏词' }, key); assert.equal(again.body.replay, true); assert.equal(db.getDb().review.tasks.filter(t => t.docId === id && t.status === 'PENDING').length, 1)
  const high = await publish(id, { body: '演示拦截词 和 演.示.高.敏.词' }); assert.equal(high.body.outcome, 'AUTO_REJECTED'); assert.equal(pending(id).kind, 'RECHECK')
  assert.ok(high.body.content.flows[0].events.every(e => !e.detail), 'high sensitivity never shows matched words to the author')
  assert.equal(db.getDb().review.tasks.some(t => t.strikes != null), false, 'no strike counting')
})

test('CT-A05 detection unavailable: nothing is published, the draft is saved and the old public version stays; no enabled rule behaves the same', async () => {
  db.getDb().review.settings.detectFailure = true; db.saveDb()
  const res = await publish(1803, { body: '更新后的文章正文' }); assert.equal(res.status, 503)
  assert.equal(doc(1803).draft.body, '更新后的文章正文'); assert.match(doc(1803).published.body, /本地 Markdown/); assert.equal(pending(1803), undefined)
  db.getDb().review.settings.detectFailure = false; db.getDb().review.rules.forEach(r => { r.enabled = false }); db.saveDb()
  assert.equal((await publish(1803)).status, 503)
})

test('CT-A06 old public version stays readable while a newer draft, a frozen submission and review coexist (1801 three versions)', async () => {
  const v = (await mine(1801)).body
  assert.equal(v.status.publicState, 'LIVE'); assert.equal(v.status.work, 'REVIEWING')
  assert.equal(v.published.version, 1); assert.equal(v.submitted.version, 2); assert.equal(v.draft.version, 3)
  assert.equal((await request('GET', '/api/content/public/1801')).body.version, 1)
  as(null); assert.equal((await request('GET', '/api/content/public/1801')).status, 200)
})

test('CT-A07 later edits do not replace the frozen submission; resubmitting supersedes the old task; cancel only cancels the task', async () => {
  const task = pending(1801); const frozen = task.snapshot.body
  await save(1801, { body: '又改了一版，仍然不会替换已提交内容' }); assert.equal(pending(1801).snapshot.body, frozen)
  const cancelled = await request('POST', '/api/content/mine/1801/cancel', {}); assert.equal(cancelled.status, 200)
  assert.equal(pending(1801), undefined); assert.equal(doc(1801).draft.body, '又改了一版，仍然不会替换已提交内容'); assert.equal(doc(1801).published.version, 1)
  assert.equal(db.getDb().review.tasks.find(t => t.id === task.id).status, 'INVALID')
  assert.equal((await request('POST', '/api/content/mine/1801/cancel', {})).status, 409, 'a second cancel is refused, not repeated')
  const again = await publish(1801, { body: '演示拦截词，重新提交' }); const first = pending(1801); assert.equal(again.body.outcome, 'PENDING')
  await publish(1801, { body: '演示拦截词，再次重新提交' }); assert.equal(db.getDb().review.tasks.find(t => t.id === first.id).status, 'INVALID'); assert.equal(db.getDb().review.tasks.filter(t => t.docId === 1801 && t.status === 'PENDING').length, 1)
})

test('CT-A07 a high-sensitivity automatic rejection cannot be cancelled by the author but can be revised and resubmitted', async () => {
  assert.equal(pending(1806).kind, 'RECHECK'); const cancel = await request('POST', '/api/content/mine/1806/cancel', {}); assert.equal(cancel.status, 409)
  const fixed = await publish(1806, { body: '改好的干净正文' }); assert.equal(fixed.body.outcome, 'PUBLISHED'); assert.equal(pending(1806), undefined)
})

test('CT-A08 withdraw needs confirmation, hides every public entry, voids pending submissions and re-publishing is re-detected', async () => {
  assert.equal((await request('POST', '/api/content/mine/1801/withdraw', {})).status, 400); assert.ok(doc(1801).published && !doc(1801).withdrawn)
  const w = await request('POST', '/api/content/mine/1801/withdraw', { confirm: true }); assert.equal(w.status, 200)
  assert.equal(pending(1801), undefined); assert.equal(doc(1801).draft.version, 3, 'private draft kept')
  assert.equal((await request('GET', '/api/content/public/1801')).status, 404)
  assert.ok(!(await request('GET', '/api/review/public')).body.items.some(i => i.id === 1801))
  as(1); assert.equal((await request('GET', '/api/content/public/1801')).status, 404)
  as(3); const count = db.getDb().review.tasks.length; const re = await publish(1801, { body: '重新公开的干净内容' }); assert.equal(re.body.outcome, 'PUBLISHED'); assert.ok(db.getDb().review.tasks.length >= count); assert.ok(re.body.content.flows[0].events.some(e => e.stage === 'CHECK'))
  assert.equal((await request('POST', '/api/content/mine/1801/withdraw', { confirm: true })).status, 200)
  assert.equal((await request('POST', '/api/content/mine/1801/withdraw', { confirm: true })).status, 409, 'second withdraw is not repeated')
})

test('CT-A09 a manual takedown is not undone by save, publish, withdraw or the author; another reviewer restores it', async () => {
  assert.equal(doc(1805).down, true)
  assert.equal((await save(1805, { body: '作者继续编辑' })).status, 200); assert.equal(doc(1805).down, true)
  assert.equal((await publish(1805)).status, 409); assert.equal((await request('POST', '/api/content/mine/1805/withdraw', { confirm: true })).status, 409); assert.equal(doc(1805).down, true)
  assert.equal((await request('GET', '/api/content/public/1805')).status, 404)
  as(1); const restore = await request('POST', '/api/review/publications/1805', { action: 'RESTORE', note: '复核后恢复', expectedVersion: doc(1805).published.version, expectedDown: true }); assert.equal(restore.status, 200); assert.equal(doc(1805).down, false)
})

test('CT-A10 expired session, account switch and disabled author never save or publish for someone else, and inputs are kept client-side', async () => {
  as(null); assert.equal((await save(1802, { title: '想改' })).status, 401); assert.equal((await publish(1802, full)).status, 401)
  as(1); assert.equal((await save(1802, { title: '站长不能改别人的稿' })).status, 404); assert.notEqual(doc(1802).draft.title, '站长不能改别人的稿')
  as(3); mutate(d => { d.users.find(u => u.id === 3).accountStatus = 'DISABLED' }); assert.equal((await save(1802, { title: '停用后' })).status, 401)
  const csrf = await window.fetch('/api/content/mine', { method: 'POST', body: JSON.stringify({ type: 'ARTICLE', ...full }) }); assert.equal(csrf.status, 403)
})

test('CT-A11 stale versions are refused without losing either side; double publish and a write failure keep the draft', async () => {
  const stale = doc(1802).draft.version; await save(1802, { title: '窗口甲' })
  const lost = await request('PUT', '/api/content/mine/1802', { ...full, expectedVersion: stale }); assert.equal(lost.status, 409); assert.equal(doc(1802).draft.title, '窗口甲')
  const conflictPublish = await request('POST', '/api/content/mine/1802/publish', { ...full, expectedVersion: stale, requestId: 'late' }); assert.equal(conflictPublish.status, 409); assert.equal(doc(1802).published, null)
  db.getDb().review.settings.failWriteOnce = true; db.saveDb(); const before = doc(1802).draft.title
  assert.equal((await save(1802, { title: '写入失败' })).status, 503); assert.equal(doc(1802).draft.title, before)
  const ok = await publish(1802, full, 'once'); const dup = await publish(1802, full, 'once'); assert.equal(ok.body.outcome, 'PUBLISHED'); assert.equal(dup.body.replay, true)
  assert.equal(doc(1802).flows.length, 1)
})

test('CT-A13 composite status separates public state from work state, and filters map to them', async () => {
  const states = Object.fromEntries((await request('GET', '/api/content/mine?type=ARTICLE')).body.items.map(i => [i.id, i.status]))
  assert.deepEqual([states[1801].publicState, states[1801].work], ['LIVE', 'REVIEWING']); assert.deepEqual([states[1804].publicState, states[1804].work], ['WITHDRAWN', 'SAVED'])
  assert.equal(states[1805].publicState, 'DOWN'); assert.equal(states[1806].work, 'RECHECK')
  const live = (await request('GET', '/api/content/mine?type=ARTICLE&state=LIVE')).body.items.map(i => i.id); assert.ok(live.includes(1801) && !live.includes(1804) && !live.includes(1805) && !live.includes(1806))
  const off = (await request('GET', '/api/content/mine?type=ARTICLE&state=OFF')).body.items.map(i => i.id).sort(); assert.deepEqual(off, [1804, 1805])
  const drafts = (await request('GET', '/api/content/mine?state=DRAFT')).body.items.map(i => i.id); assert.ok(drafts.includes(1802) && [1801, 1803, 1804, 1805, 1806].every(id => !drafts.includes(id)))
})

test('CT-A04 safe Markdown: HTML and scripts stay text, images never load, dangerous links are not links', () => {
  const { blocks, notes } = md.markdownBlocks('# 标题\n\n<script>alert(1)</script> **粗体** `code`\n\n![图](https://evil.example/x.png) [坏](javascript:alert(1)) [好](https://ok.example/a)\n\n- 列表')
  const inline = blocks.flatMap(b => b.inline || []); assert.ok(inline.some(t => t.k === 'image')); assert.ok(inline.some(t => t.k === 'blocked' && t.href.startsWith('javascript:'))); assert.ok(inline.some(t => t.k === 'link' && t.href === 'https://ok.example/a'))
  assert.ok(inline.some(t => t.k === 'text' && t.text.includes('<script>')), 'script is plain text, rendered through text interpolation'); assert.ok(notes.html >= 1 && notes.images === 1 && notes.links === 1)
  assert.deepEqual(md.markdownBlocks('').blocks, []); assert.ok(md.noteText(notes).length >= 3)
})

test('list CRUD: search, kind filter with counts, row action flags, and deleting only never-published private drafts', async () => {
  const all = (await request('GET', '/api/content/mine')).body; assert.equal(all.counts.ARTICLE, all.items.length)
  assert.ok(all.items.every(i => i.type === 'ARTICLE'))
  const found = (await request('GET', '/api/content/mine?q=' + encodeURIComponent('备份'))).body.items; assert.deepEqual(found.map(i => i.id), [1802])
  assert.equal((await request('GET', '/api/content/mine?q=' + encodeURIComponent('不存在的词'))).body.items.length, 0)
  const flags = Object.fromEntries(all.items.map(i => [i.id, [i.canCancel, i.canWithdraw, i.canDelete]]))
  assert.deepEqual(flags[1801], [true, true, false]); assert.deepEqual(flags[1802], [false, false, true]); assert.deepEqual(flags[1806], [false, false, false])
  assert.equal((await request('DELETE', '/api/content/mine/1803')).status, 409, 'published content is never deleted'); assert.equal((await request('DELETE', '/api/content/mine/1804')).status, 409, 'withdrawn content keeps its private copy')
  assert.equal((await request('DELETE', '/api/content/mine/1806')).status, 409, 'pending manual review blocks deletion')
  as(1); assert.equal((await request('DELETE', '/api/content/mine/1802')).status, 404); as(null); assert.equal((await request('DELETE', '/api/content/mine/1802')).status, 401); as(3)
  assert.equal((await request('DELETE', '/api/content/mine/1802', undefined, false)).status, 403, 'CSRF')
  const removed = await request('DELETE', '/api/content/mine/1802'); assert.equal(removed.status, 200)
  assert.equal((await mine(1802)).status, 404); assert.ok(!(await request('GET', '/api/content/mine')).body.items.some(i => i.id === 1802))
  assert.equal((await request('DELETE', '/api/content/mine/1802')).status, 404, 'second delete is not repeated')
  const fresh = (await create('ARTICLE', { title: '一次性草稿' })).body.id; assert.equal((await request('DELETE', '/api/content/mine/' + fresh)).status, 200)
})
