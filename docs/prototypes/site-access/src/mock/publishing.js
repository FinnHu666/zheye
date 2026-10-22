// FINNS-18 R1 (requirements revision 2/3): browser-only lifecycle model for articles (the ordinary-topic type is removed from the prototype).
// Reuses the FINNS-17 R3 pipeline (detect → route → manual handling) and adds the author-side lifecycle:
// working draft / frozen submission / public version, cancel-submission vs withdraw, empty-draft rule, limits, idempotent publish.
import { getDb, nextId } from './db'
import { currentPrototypeUser } from './user-admin'
import { ensureReview, document, pipeline, detect, invalidate, publish, actor, findDoc, guardWrite, log, sweep, copy, stamp, problem, authorFlow, authorName } from './review'

export const LIMITS = { title: 120, summary: 280, body: 20000 }
export const TYPES = ['ARTICLE']
// Counted by Unicode code points; the real counting rule is still open item CT-L01 and is decided at G3/G4.
export const measure = value => [...String(value ?? '')].length
const blank = value => !String(value ?? '').trim()
const fields = ['title', 'summary', 'body']
const fieldLabel = { title: '标题', summary: '摘要', body: '正文' }

export function ensurePublishing() {
  const state = ensureReview()
  if (state.publishingSeeded) return state
  state.publishingSeeded = true
  const make = (id, type, title, summary, body, authorId = 3) => {
    const doc = document(id, type, authorId, title, body)
    doc.draft.summary = summary
    state.documents.push(doc)
    return doc
  }
  const edit = (doc, patch) => { doc.draft = { ...doc.draft, ...patch, version: doc.draft.version + 1 } }
  // 1801: public v1 + pending update (frozen v2) + newer unsaved-to-review working draft v3 → three versions coexist.
  const three = make(1801, 'ARTICLE', '写作流程的一次更新', '先保存，再预览，最后发布。', '这是已经公开的第一版正文。')
  pipeline(three, { ago: 600 })
  edit(three, { body: '这一版包含演示拦截词，所以进入人工审核；旧公开版继续可读。' }); pipeline(three, { ago: 90 })
  edit(three, { body: '这是之后继续修改的工作稿，不会替换已提交的内容，也不会改变公开版。' })
  // 1802: incomplete private article draft (saved, never submitted; deletable).
  const incomplete = make(1802, 'ARTICLE', '备份策略的几点想法', '', '')
  incomplete.draft.summary = ''
  // 1803: clean public article.
  const topic = make(1803, 'ARTICLE', '我的博客写作工具', '本地 Markdown 加网页发布的小结。', '我目前用本地 Markdown 加网页发布，欢迎分享你的组合。')
  pipeline(topic, { ago: 300 })
  // 1804: published then withdrawn by the author; private copy retained.
  const withdrawn = make(1804, 'ARTICLE', '一次已撤回的文章', '作者撤回后只保留私人稿。', '这篇文章曾经公开，现在只有作者自己可见。')
  pipeline(withdrawn, { ago: 800 }); invalidate(withdrawn, '作者撤回，旧提交失效。'); withdrawn.withdrawn = true
  withdrawn.flows[0].events.push({ id: nextId(), stage: 'RESULT', title: '作者撤回', note: '私人原稿保留', detail: '', actor: '', at: stamp(700) })
  // 1805: manually taken down; author save/withdraw cannot restore.
  const down = make(1805, 'ARTICLE', '已被独立下架的文章', '需要另一位审批者复核恢复。', '这篇文章已被人工下架。')
  pipeline(down, { ago: 900 }); down.down = true; log(down, '人工下架', '演示：内容被独立下架', { displayName: '你' })
  // 1806: high sensitivity auto-rejected; draft stays editable.
  const high = make(1806, 'ARTICLE', '一次高敏感命中的示例', '自动不通过后稿件仍可继续编辑。', '这里出现了演示高敏词，用于体验自动不通过与人工复核。')
  pipeline(high, { ago: 25 })
  return state
}

function authorGuard(perm = 'post.create') {
  const user = actor()
  if (!(getDb().permissions[user.role] || []).includes(perm)) problem(403, '当前账号没有创作权限。')
  return user
}
function mine(id) {
  const user = actor(), doc = ensurePublishing().documents.find(x => x.id === Number(id) && TYPES.includes(x.type))
  if (!doc || doc.deleted || doc.authorId !== user.id) problem(404, '未找到属于当前账号的内容。')
  return doc
}
const pendingTask = doc => ensureReview().tasks.find(t => t.docId === doc.id && t.status === 'PENDING')
const sameContent = (a, b) => !!a && !!b && fields.every(k => a[k] === b[k]) && a.visibility === b.visibility

// Composite status: public state and work state are shown separately so that a draft never looks like the public version.
export function compositeStatus(doc) {
  const task = pendingTask(doc), flow = doc.flows[0]
  const publicState = doc.down ? 'DOWN' : doc.withdrawn ? 'WITHDRAWN' : doc.published ? 'LIVE' : 'NONE'
  let work = 'SAVED'
  if (task) work = task.kind === 'RECHECK' ? 'RECHECK' : 'REVIEWING'
  else if (flow && ['REJECTED', 'UPHELD'].includes(flow.status)) work = 'REJECTED'
  else if (publicState === 'LIVE' && !sameContent(doc.draft, doc.published)) work = 'MODIFIED'
  else if (!doc.published && !doc.flows.length) work = 'PRIVATE'
  const publicText = { NONE: '未公开', LIVE: '已发布', DOWN: '已下架', WITHDRAWN: '已撤回' }[publicState]
  const workText = { PRIVATE: '私人草稿', SAVED: '已保存', MODIFIED: '有改动未发布', REVIEWING: '待审核', RECHECK: '未通过 · 人工复核中', REJECTED: '未通过' }[work]
  const tone = doc.down || ['RECHECK', 'REJECTED'].includes(work) ? 'danger' : work === 'REVIEWING' || work === 'MODIFIED' ? 'amber' : publicState === 'LIVE' ? 'green' : 'neutral'
  return { publicState, work, publicText, workText, tone }
}

const frozenView = task => task ? { version: task.snapshot.version, title: task.snapshot.title, summary: task.snapshot.summary, body: task.snapshot.body, kind: task.kind, level: task.kind === 'RECHECK' ? 'HIGH' : task.level, submittedAt: task.createdAt } : null
function view(doc) {
  const task = pendingTask(doc), status = compositeStatus(doc)
  return {
    id: doc.id, type: doc.type, draft: copy(doc.draft), status,
    published: doc.published ? copy(doc.published) : null, publicVisible: !!doc.published && !doc.down && !doc.withdrawn,
    submitted: frozenView(task), down: doc.down, withdrawn: doc.withdrawn, canCancel: !!task && task.kind === 'REVIEW',
    flows: doc.flows.map(authorFlow), history: copy(doc.history).filter(h => h.action !== '审核通过'),
    updatedAt: doc.history[0]?.at || doc.flows[0]?.startedAt || null,
  }
}
const deletable = doc => !doc.published && !pendingTask(doc)
const row = doc => { const v = view(doc); return { canCancel: v.canCancel, canWithdraw: v.publicVisible, canDelete: deletable(doc), id: v.id, type: v.type, title: v.draft.title, summary: v.draft.summary, version: v.draft.version, status: v.status, publicTitle: v.published?.title || '', publicVisible: v.publicVisible, hasPublished: !!v.published, updatedAt: v.updatedAt } }

function clean(body) {
  const draft = { title: String(body.title ?? ''), summary: String(body.summary ?? ''), body: String(body.body ?? ''), visibility: 'PUBLIC' }
  for (const k of fields) if (measure(draft[k]) > LIMITS[k]) problem(400, `${fieldLabel[k]}不能超过 ${LIMITS[k]} 个字符，当前 ${measure(draft[k])}；输入已保留。`)
  return draft
}
function requireComplete(draft) {
  const missing = fields.filter(k => blank(draft[k]))
  if (missing.length) problem(400, `发布前请补全：${missing.map(k => fieldLabel[k]).join('、')}。草稿已保留，可以先保存。`)
}
function applyDraft(doc, draft) {
  if (fields.every(k => doc.draft[k] === draft[k])) return false
  doc.draft = { ...draft, visibility: doc.draft.visibility, version: doc.draft.version + 1 }
  return true
}

export function handleContent(method, path, body = {}, params = new URLSearchParams()) {
  if (!path.startsWith('/api/content/')) return { handled: false }
  const state = ensurePublishing(); let data
  if (method === 'GET' && path === '/api/content/mine') {
    const user = actor(); sweep()
    const filter = params.get('state') || 'ALL', q = (params.get('q') || '').trim().toLowerCase()
    const all = state.documents.filter(d => d.authorId === user.id && !d.deleted && TYPES.includes(d.type)).map(row)
    const match = r => ({ ALL: true, DRAFT: ['PRIVATE', 'SAVED'].includes(r.status.work) && !r.hasPublished, REVIEW: ['REVIEWING', 'RECHECK', 'REJECTED'].includes(r.status.work), LIVE: r.publicVisible, OFF: ['WITHDRAWN', 'DOWN'].includes(r.status.publicState) }[filter])
    const inType = all.filter(r => (!q || `${r.title} ${r.summary}`.toLowerCase().includes(q)))
    data = { items: inType.filter(match), counts: { ARTICLE: all.length } }
  } else if (method === 'POST' && path === '/api/content/mine') {
    const user = authorGuard(); const type = 'ARTICLE', draft = clean(body)
    if (fields.every(k => blank(draft[k]))) problem(400, '标题、摘要和正文都为空，不会创建草稿。')
    guardWrite()
    const doc = document(nextId(), type, user.id, draft.title, draft.body); doc.draft = { ...doc.draft, ...draft, version: 1 }
    state.documents.push(doc); log(doc, '创建私人草稿', '手动保存'); data = view(doc)
  } else if (method === 'GET' && /^\/api\/content\/mine\/\d+$/.test(path)) { sweep(); data = view(mine(path.split('/')[4])) }
  else if (method === 'PUT' && /^\/api\/content\/mine\/\d+$/.test(path)) {
    const doc = mine(path.split('/')[4]); authorGuard(); const draft = clean(body)
    if (Number(body.expectedVersion) !== doc.draft.version) problem(409, `这份草稿已在别处更新到 v${doc.draft.version}。你的输入已保留，请核对后再保存。`)
    guardWrite(); if (applyDraft(doc, draft)) log(doc, '保存工作稿', `v${doc.draft.version}`)
    data = view(doc)
  } else if (method === 'POST' && /^\/api\/content\/mine\/\d+\/publish$/.test(path)) {
    const doc = mine(path.split('/')[4]); authorGuard(); const key = String(body.requestId || '')
    if (!key) problem(400, '提交标识缺失，请重试。')
    if (doc.receipts[key]) { data = { ...copy(doc.receipts[key]), replay: true, content: view(doc) } }
    else {
      const draft = clean(body); requireComplete(draft)
      if (Number(body.expectedVersion) !== doc.draft.version) problem(409, `这份草稿已在别处更新到 v${doc.draft.version}。你的输入已保留，请核对后再发布。`)
      if (doc.down) problem(409, '内容已被人工下架，保存或发布不能解除下架，请等待另一位审批者复核恢复。')
      guardWrite()
      const changed = applyDraft(doc, draft); if (changed) log(doc, '保存工作稿', `v${doc.draft.version}（发布前保存）`)
      detect(doc.draft) // detection unavailable: nothing is published; the saved draft and old public version stay
      const hadPublic = !!doc.published && !doc.withdrawn
      const response = pipeline(doc)
      if (response.outcome === 'PUBLISHED') log(doc, hadPublic ? '更新发布' : '发布', response.level === 'LOW' ? '低敏感放行并留痕' : '敏感检测通过')
      doc.receipts[key] = copy(response); data = { ...response, replay: false, content: view(doc) }
    }
  } else if (method === 'POST' && /^\/api\/content\/mine\/\d+\/cancel$/.test(path)) {
    const doc = mine(path.split('/')[4]), task = pendingTask(doc)
    if (!task) problem(409, '当前没有待处理的提交，可能已被处理。请刷新查看最新状态。')
    if (task.kind === 'RECHECK') problem(409, '高敏感内容已自动不通过并转人工复核，不能取消；可以修改后重新提交。')
    guardWrite()
    task.status = 'INVALID'; task.result = '作者取消送审'; task.closedAt = stamp(); doc.generation += 1
    const flow = doc.flows.find(f => f.id === task.flowId)
    if (flow) { flow.status = 'INVALID'; flow.events.push({ id: nextId(), stage: 'RESULT', title: '已取消送审', note: '只取消这次提交；工作稿与旧公开版保留。', detail: '', actor: '', at: stamp() }) }
    log(doc, '取消送审', '工作稿与旧公开版保留'); data = { message: '已取消本次送审，工作稿和旧公开版保留。', content: view(doc) }
  } else if (method === 'DELETE' && /^\/api\/content\/mine\/\d+$/.test(path)) {
    const doc = mine(path.split('/')[4])
    if (doc.published) problem(409, '这条内容公开过，不能删除；可以撤回公开后保留私人稿。')
    if (pendingTask(doc)) problem(409, '这条内容有待处理的提交，请先取消送审或等待处理结果。')
    guardWrite(); doc.deleted = true; log(doc, '删除私人草稿', '从未公开的私人稿')
    data = { message: '私人草稿已删除。' }
  } else if (method === 'POST' && /^\/api\/content\/mine\/\d+\/withdraw$/.test(path)) {
    const doc = mine(path.split('/')[4])
    if (body.confirm !== true) problem(400, '撤回会停止公开并使待处理提交失效，需要二次确认。')
    if (doc.down) problem(409, '内容已被人工下架，撤回不会解除下架。')
    if (!doc.published || doc.withdrawn) problem(409, '当前没有公开版，无需撤回。')
    guardWrite(); invalidate(doc, '作者撤回，旧提交失效。'); doc.withdrawn = true
    const flow = doc.flows[0]; if (flow) flow.events.push({ id: nextId(), stage: 'RESULT', title: '作者撤回', note: '私人原稿保留', detail: '', actor: '', at: stamp() })
    log(doc, '作者撤回', '私人原稿保留'); data = { message: '已撤回，公开入口不再显示；私人稿保留，重新发布会重新检测。', content: view(doc) }
  } else if (method === 'GET' && /^\/api\/content\/public\/\d+$/.test(path)) {
    const doc = state.documents.find(x => x.id === Number(path.split('/')[4]) && TYPES.includes(x.type))
    if (!doc || !doc.published || doc.down || doc.withdrawn) problem(404, '这条内容不可用或已不再公开。')
    data = { id: doc.id, type: doc.type, authorName: authorName(doc.authorId), ...copy(doc.published) }
  } else problem(404, '接口不存在。')
  return { handled: true, data }
}

export function loadContentScene() {
  const d = getDb(); ensureReview(d); d.review = null; ensureReview(d); ensurePublishing()
  const user = currentPrototypeUser(); return user
}
