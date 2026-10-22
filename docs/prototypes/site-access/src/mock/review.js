// FINNS-17 R3 (requirements revision 6): browser-only scenario model. No production moderation or database.
// Three sensitivity levels, no strike counting, one pipeline for articles / comments / collections, visible flow timeline.
import { getDb, now, nextId } from './db'
import { currentPrototypeUser, ensureUserAdmin, isSiteOwner } from './user-admin'
import { longReviewSample } from './review-samples'

export const REVIEW_PERMISSION = 'content.review'
export const LEVELS = { NONE: 0, LOW: 1, MEDIUM: 2, HIGH: 3 }
export const copy = value => JSON.parse(JSON.stringify(value))
export const stamp = (ago = 0, plus = 0) => new Date(now() - ago * 60000 + plus * 1000).toISOString()
export const problem = (status, message) => { throw Object.assign(new Error(message), { status }) }
// Same normalization as the production detector: width/case folded; whitespace, punctuation, symbols and zero-width characters removed.
export const normalize = value => String(value || '').normalize('NFKC').toLowerCase().replace(/[\s\p{P}\p{S}\p{Z}\p{C}]/gu, '')
const permitted = user => !!user && user.accountStatus !== 'DISABLED' && (getDb().permissions[user.role] || []).includes(REVIEW_PERMISSION)
export const authorName = id => getDb().users.find(u => u.id === id)?.displayName || '作者'

export function document(id, type, authorId, title, body, published = false, legacy = false) {
  const draft = { title, summary: type === 'COMMENT' ? '' : '把经验整理成能复用的知识。', body, visibility: 'PUBLIC', version: 1 }
  return { id, type, authorId, draft, published: published ? copy(draft) : null, approvedOnce: published, legacy, generation: 1, down: false, withdrawn: false, deleted: false, receipts: {}, history: [], flows: [] }
}

// ---------------------------------------------------------------- flow timeline
// Stage keys are stable so that FINNS-18/19 can plug creation/draft/update stages in front of and behind the review stages.
export const REVIEW_STAGES = ['SUBMIT', 'CHECK', 'ROUTE', 'REVIEW', 'RESULT']
function addEvent(flow, stage, title, note, { detail = '', actor = '', ago = 0, plus = 0 } = {}) {
  flow.events.push({ id: nextId(), stage, title, note, detail, actor, at: stamp(ago, plus) })
}
const levelText = { LOW: '低', MEDIUM: '中', HIGH: '高' }

export function detect(snapshot) {
  const state = ensureReview()
  const active = state.rules.filter(r => !r.deleted && r.enabled && normalize(r.phrase))
  if (state.settings.detectFailure || !active.length) problem(503, '敏感检测暂不可用，本次未发布。草稿和旧发布版已保留。')
  const value = normalize([snapshot.title, snapshot.summary, snapshot.body].join('\n'))
  const hits = active.filter(r => value.includes(normalize(r.phrase))).map(r => ({ phrase: r.phrase, level: r.level }))
  const level = hits.reduce((top, h) => LEVELS[h.level] > LEVELS[top] ? h.level : top, 'NONE')
  return { hits, level }
}

export function invalidate(doc, reason) {
  doc.generation += 1
  for (const task of ensureReview().tasks.filter(t => t.docId === doc.id && t.status === 'PENDING')) {
    task.status = 'INVALID'; task.result = reason; task.closedAt = stamp()
    const flow = doc.flows.find(f => f.id === task.flowId)
    if (flow && ['IN_REVIEW', 'AUTO_REJECTED'].includes(flow.status)) { flow.status = 'INVALID'; addEvent(flow, 'RESULT', '提交已失效', reason) }
  }
}
export function publish(doc, snapshot) { doc.published = copy(snapshot); doc.approvedOnce = true; doc.withdrawn = false }
function newTask(doc, flow, kind, level, matches, ago = 0) {
  const task = { id: nextId(), docId: doc.id, flowId: flow.id, authorId: doc.authorId, type: doc.type, kind, level, snapshot: copy(doc.draft), generation: doc.generation, triggers: [kind === 'RECHECK' ? 'HIGH' : 'SENSITIVE'], matches, status: 'PENDING', createdAt: stamp(ago, 40), result: '' }
  ensureReview().tasks.unshift(task)
  return task
}

// One pipeline for every content type: submit → detect → route → (manual handling) → result.
export function pipeline(doc, { ago = 0 } = {}) {
  const snapshot = copy(doc.draft), { hits, level } = detect(snapshot)
  invalidate(doc, '作者已重新提交，旧提交失效。')
  const flow = { id: nextId(), docId: doc.id, version: snapshot.version, level, status: 'IN_REVIEW', startedAt: stamp(ago), events: [] }
  doc.flows.unshift(flow)
  const words = hits.map(h => h.phrase).join('、')
  addEvent(flow, 'SUBMIT', '已提交', `提交版 v${snapshot.version}`, { ago })
  let outcome, message, task = null
  if (level === 'NONE') {
    addEvent(flow, 'CHECK', '敏感检测完成', '未发现敏感内容', { ago, plus: 10 })
    addEvent(flow, 'ROUTE', '直接发布', '无需人工审核', { ago, plus: 20 })
    publish(doc, snapshot); addEvent(flow, 'RESULT', '已发布', `发布版 v${snapshot.version}`, { ago, plus: 30 }); flow.status = 'PUBLISHED'
    outcome = 'PUBLISHED'; message = '敏感检测通过，内容已发布。'
  } else if (level === 'LOW') {
    addEvent(flow, 'CHECK', '敏感检测完成', '命中低敏感内容，不拦截', { detail: `命中：${words}`, ago, plus: 10 })
    addEvent(flow, 'ROUTE', '放行并留痕', '低敏感不需要人工审核，已记录供站长事后查看', { ago, plus: 20 })
    ensureReview().noted.unshift({ docId: doc.id, level: 'LOW', words: hits.map(h => h.phrase), at: stamp(ago, 20) })
    publish(doc, snapshot); addEvent(flow, 'RESULT', '已发布', `发布版 v${snapshot.version}`, { ago, plus: 30 }); flow.status = 'PUBLISHED'
    outcome = 'PUBLISHED'; message = '内容已发布；检测到低敏感内容，已留痕。'
  } else if (level === 'MEDIUM') {
    addEvent(flow, 'CHECK', '敏感检测完成', '命中中敏感内容', { detail: `命中：${words}`, ago, plus: 10 })
    addEvent(flow, 'ROUTE', '送审', '内容暂不公开，等待另一位有权限的审核员处理', { ago, plus: 20 })
    task = newTask(doc, flow, 'REVIEW', 'MEDIUM', hits.map(h => h.phrase), ago)
    outcome = 'PENDING'; message = '内容已送审，审核通过后才会公开。'
  } else {
    addEvent(flow, 'CHECK', '敏感检测完成', '检测发现不允许发布的内容', { detail: `命中：${words}`, ago, plus: 10 })
    addEvent(flow, 'ROUTE', '自动不通过', '内容不会公开；已同时转人工复核', { ago, plus: 20 })
    task = newTask(doc, flow, 'RECHECK', 'HIGH', hits.map(h => h.phrase), ago); flow.status = 'AUTO_REJECTED'
    addEvent(flow, 'RESULT', '未通过', '包含不允许发布的内容。人工复核结果会更新到这里；你也可以修改后重新提交。', { ago, plus: 30 })
    outcome = 'AUTO_REJECTED'; message = '未通过：包含不允许发布的内容。已转人工复核，你可以修改后重新提交。'
  }
  flow.taskId = task?.id || null
  return { outcome, message, level, flowId: flow.id, taskId: task?.id || null }
}

export function ensureReview(d = getDb()) {
  ensureUserAdmin(d)
  d.permissions.OWNER ||= []
  if (!d.permissions.OWNER.includes(REVIEW_PERMISSION)) d.permissions.OWNER.push(REVIEW_PERMISSION)
  if (d.review) return d.review
  const documents = [
    document(1701, 'COLLECTION', 3, '独立开发者的知识整理路径', longReviewSample + '\n\n## 07 一段用于体验命中的示例\n\n这一段包含演示拦截词，只用来体验命中高亮与人工审核。'),
    document(1702, 'ARTICLE', 3, '让每次发布都更从容', '先保存工作稿，再预览和发布。', true),
    document(1703, 'ARTICLE', 3, '我的新文章', '写下一条有用的经验。'),
    document(1704, 'COMMENT', 5, '关于知识整理的一条评论', '这里包含演示拦截词，用于体验人工审核。'),
    document(1705, 'COLLECTION', 1, '站长的公开写作手册', '如何把个人记录整理成公开专题。这里有一句演示拦截词。'),
    document(1706, 'COLLECTION', 2, '从零部署个人站点', '一份历史发布的入门路线，不补造历史审批。', true, true),
    document(1707, 'ARTICLE', 5, '待复核恢复的旧文章', '这篇文章已被独立下架，需要人工复核恢复。', true, true),
    document(1708, 'COLLECTION', 3, '我的新专题', '先自由编辑私人草稿，没有命中时可直接发布。'),
    document(1709, 'ARTICLE', 3, '一次高敏感命中的示例', '这里出现了演示高敏词，用于体验自动不通过与人工复核。'),
    document(1710, 'ARTICLE', 3, '低敏感命中后直接发布', '这里有一句演示低敏词，它不会被拦截，只会留下记录。'),
  ]
  d.review = { documents, tasks: [], rules: [
    { id: 1, phrase: '演示拦截词', note: '中性演示词：命中后直接送审', enabled: true, level: 'MEDIUM', version: 1 },
    { id: 2, phrase: '测试联系方式', note: '仅为交互样例，不是真实敏感词库', enabled: true, level: 'MEDIUM', version: 1 },
    { id: 3, phrase: '演示推广语', note: '停用样例：不参与检测', enabled: false, level: 'MEDIUM', version: 1 },
    { id: 4, phrase: '演示低敏词', note: '低敏感：放行并留痕', enabled: true, level: 'LOW', version: 1 },
    { id: 5, phrase: '演示高敏词', note: '高敏感：自动不通过并转人工复核（中性占位词，不是真实词库）', enabled: true, level: 'HIGH', version: 1 },
  ], settings: { detectFailure: false, failWriteOnce: false, failLoadOnce: false, slowLoad: false, conflictOnce: false }, ruleHistory: [], noted: [] }
  const byId = id => documents.find(x => x.id === id)
  byId(1707).down = true
  pipeline(byId(1701), { ago: 190 })
  const updating = byId(1702); updating.draft = { ...updating.draft, version: 2, body: '更新稿包含演示拦截词，旧发布版继续可见。' }
  pipeline(byId(1704), { ago: 130 }); pipeline(byId(1705), { ago: 75 }); pipeline(updating, { ago: 40 })
  pipeline(byId(1709), { ago: 20 }); pipeline(byId(1710), { ago: 8 })
  return d.review
}

export function actor(permission = false, owner = false) {
  ensureReview()
  const user = currentPrototypeUser()
  if (!user) problem(401, '登录已失效，请重新登录。内容输入已保留。')
  if (permission && !permitted(user)) problem(403, '你没有内容审批权限；用户管理权限不能代替审批权限。')
  if (owner && !isSiteOwner(user)) problem(403, '只有当前站长可以维护敏感词与规则。')
  return user
}
function owned(id) {
  const user = actor(), doc = ensureReview().documents.find(x => x.id === Number(id))
  if (!doc || doc.authorId !== user.id) problem(404, '未找到属于当前账号的内容。')
  if (doc.deleted) problem(409, '这条内容已删除。')
  return doc
}
export function findDoc(id) { const doc = ensureReview().documents.find(x => x.id === Number(id)); if (!doc) problem(404, '内容不存在。'); return doc }
export function guardWrite() {
  const settings = ensureReview().settings
  if (settings.failWriteOnce) { settings.failWriteOnce = false; problem(503, '暂时无法保存，输入与旧发布版已保留，请重试。') }
  if (settings.conflictOnce) { settings.conflictOnce = false; problem(409, '内容已在别处处理，请关闭后重新读取最新状态。') }
}
export function log(doc, action, note, user = currentPrototypeUser()) { doc.history.unshift({ id: nextId(), action, note, operator: user?.displayName || '系统', version: doc.published?.version || doc.draft.version, at: stamp() }) }
export function sweep() {
  for (const task of ensureReview().tasks.filter(t => t.status === 'PENDING')) {
    const doc = findDoc(task.docId), author = getDb().users.find(u => u.id === doc.authorId)
    if (author?.accountStatus === 'DISABLED' || doc.deleted || doc.down || task.generation !== doc.generation) {
      task.status = 'INVALID'; task.result = '提交已失效，不能通过旧审批重新公开。'; task.closedAt = stamp()
      const flow = doc.flows.find(f => f.id === task.flowId); if (flow && ['IN_REVIEW', 'AUTO_REJECTED'].includes(flow.status)) { flow.status = 'INVALID'; addEvent(flow, 'RESULT', '提交已失效', task.result) }
    }
  }
}

// ---------------------------------------------------------------- views
const reviewerEvents = flow => flow.events.map(e => ({ ...copy(e) }))
// Author side: reviewer names become “审核员”; matched words are never shown for high sensitivity (detail removed entirely for HIGH).
const authorEvents = flow => flow.events.map(e => ({ id: e.id, stage: e.stage, title: e.title, note: e.note, at: e.at, actor: e.actor ? '审核员' : '', detail: flow.level === 'HIGH' ? '' : e.detail }))
export const authorFlow = flow => ({ id: flow.id, version: flow.version, level: flow.level, status: flow.status, startedAt: flow.startedAt, events: authorEvents(flow) })
function taskView(task) {
  const doc = findDoc(task.docId), user = currentPrototypeUser(), flow = doc.flows.find(f => f.id === task.flowId)
  const other = getDb().users.some(u => u.id !== doc.authorId && permitted(u))
  return { ...copy(task), authorName: authorName(task.authorId), published: doc.down || doc.withdrawn ? null : copy(doc.published), flow: flow ? { ...copy(flow), events: reviewerEvents(flow) } : null, history: copy(doc.history), self: task.authorId === user?.id, otherReviewerAvailable: other }
}
const mineView = doc => ({ id: doc.id, type: doc.type, draft: copy(doc.draft), published: copy(doc.published), approvedOnce: doc.approvedOnce, legacy: doc.legacy, down: doc.down, withdrawn: doc.withdrawn, flows: doc.flows.map(authorFlow), pending: ensureReview().tasks.some(t => t.docId === doc.id && t.status === 'PENDING') })
const publicSnapshot = doc => ({ id: doc.id, type: doc.type, authorName: authorName(doc.authorId), ...copy(doc.published), legacy: doc.legacy })

// ---------------------------------------------------------------- author actions
function saveDraft(doc, body) {
  if (Number(body.expectedVersion) !== doc.draft.version) problem(409, '工作稿版本已变化，请重新读取；当前输入已保留。')
  const draft = { title: String(body.title || '').trim(), summary: String(body.summary || ''), body: String(body.body || ''), visibility: body.visibility === 'RESTRICTED' ? 'RESTRICTED' : 'PUBLIC', version: doc.draft.version + 1 }
  if (draft.visibility === 'RESTRICTED' && !(getDb().permissions[actor().role] || []).includes('content.restricted')) problem(403, '当前角色不能设置受限可见范围。')
  doc.draft = draft
}
function submit(doc, body) {
  const key = String(body.requestId || '')
  if (!key) problem(400, '提交标识缺失，请重试。')
  if (doc.receipts[key]) return copy(doc.receipts[key])
  if (Number(body.expectedVersion) !== doc.draft.version) problem(409, '工作稿已更新，请保存并核对后重新提交。')
  if (!doc.draft.title.trim() || !doc.draft.body.trim()) problem(400, '请补全标题和正文后提交。')
  if (doc.down) problem(409, '内容已下架，保存或发布不能解除下架，请等待人工复核恢复。')
  detect(doc.draft)
  guardWrite()
  const response = pipeline(doc)
  if (response.outcome === 'PUBLISHED') log(doc, '发布', response.level === 'LOW' ? '低敏感放行并留痕' : '敏感检测通过')
  doc.receipts[key] = copy(response)
  return response
}

// ---------------------------------------------------------------- reviewer actions
function decide(id, body) {
  const user = actor(true); sweep()
  const task = ensureReview().tasks.find(t => t.id === Number(id))
  if (!task) problem(404, '提交不存在。')
  const doc = findDoc(task.docId), flow = doc.flows.find(f => f.id === task.flowId)
  if (task.authorId === user.id) problem(403, '禁止审核本人内容，请由另一位有审批权限的人处理。')
  if (task.status !== 'PENDING' || task.generation !== Number(body.expectedGeneration)) problem(409, '该提交已处理或失效，请重新读取，未重复发布。')
  const allowed = task.kind === 'RECHECK' ? ['UPHOLD', 'OVERTURN'] : ['APPROVE', 'REJECT']
  if (!allowed.includes(body.action)) problem(400, '处理操作无效。')
  const note = String(body.note || '').trim()
  if (task.kind === 'RECHECK' && !note) problem(400, '请填写复核原因。')
  if (body.action === 'REJECT' && !note) problem(400, '请填写驳回原因。')
  if (note.length > 200) problem(400, '处理说明最多200字。')
  guardWrite()
  const published = ['APPROVE', 'OVERTURN'].includes(body.action)
  task.status = { APPROVE: 'APPROVED', REJECT: 'REJECTED', UPHOLD: 'UPHELD', OVERTURN: 'OVERTURNED' }[body.action]; task.result = note || '审核通过'; task.operator = user.displayName; task.closedAt = stamp()
  const title = { APPROVE: '审核通过', REJECT: '审核驳回', UPHOLD: '复核：维持不通过', OVERTURN: '复核：判定误判并放行' }[body.action]
  addEvent(flow, 'REVIEW', title, note || '审核员已处理', { actor: user.displayName })
  if (published) { publish(doc, task.snapshot); invalidate(doc, '同一内容已有审批发布，旧提交失效。'); flow.status = 'PUBLISHED'; addEvent(flow, 'RESULT', '已发布', `发布版 v${task.snapshot.version}`) }
  else { flow.status = body.action === 'UPHOLD' ? 'UPHELD' : 'REJECTED'; addEvent(flow, 'RESULT', '未通过', note) }
  log(doc, title, task.result, user)
  return { message: published ? `已放行并发布提交版本 v${task.snapshot.version}。` : '已记录处理结果，原因已向作者展示；旧有效版保持。' }
}
function managePublication(id, body) {
  const user = actor(true), doc = findDoc(id), note = String(body.note || '').trim()
  if (!note) problem(400, '请填写处理原因。')
  if (!doc.published || doc.deleted || doc.withdrawn) problem(409, '当前没有可处置的发布版。')
  if (body.expectedVersion !== doc.published.version || body.expectedDown !== doc.down) problem(409, '发布状态已变化，请重新读取。')
  if (!['TAKE_DOWN', 'RESTORE'].includes(body.action)) problem(400, '处理操作无效。')
  if (body.action === 'RESTORE' && doc.authorId === user.id) problem(403, '禁止批准恢复本人作品，请交由另一位审批者。')
  if (body.action === 'RESTORE' && !doc.down || body.action === 'TAKE_DOWN' && doc.down) problem(409, '该内容状态已发生变化。')
  if (body.action === 'RESTORE') detect(doc.published)
  guardWrite(); invalidate(doc, '内容处置使旧提交失效。'); doc.down = body.action === 'TAKE_DOWN'
  log(doc, doc.down ? '人工下架' : '复核恢复', note, user)
  return { message: doc.down ? '已下架，公开入口不再显示该内容。' : '已复核恢复指定发布版，工作稿未替换它。' }
}

// ---------------------------------------------------------------- rule import (browser-only sample; no real lexicon is downloaded)
export const IMPORT_SOURCE = {
  name: 'houbb/sensitive-word（候选来源）', license: 'Apache-2.0（仓库许可；词库自身来源未核实）', readAt: '2026-10-20',
  claim: 'README 称词库 6W+（源文件 18W+，经删减），分类“难免存在疏漏”', mock: true,
}
const importSample = [
  { phrase: '加微信', tag: '引流', suspect: false }, { phrase: '加QQ群', tag: '引流', suspect: false }, { phrase: '私信我领取', tag: '引流', suspect: false },
  { phrase: '代开发票', tag: '广告', suspect: false }, { phrase: 'spam', tag: '广告', suspect: false }, { phrase: '低价代刷', tag: '广告', suspect: false },
  { phrase: '刷单', tag: '诈骗', suspect: false }, { phrase: '私聊转账', tag: '诈骗', suspect: false }, { phrase: '先交保证金', tag: '诈骗', suspect: false },
  { phrase: '演示政治词甲', tag: '政治', suspect: false }, { phrase: '演示政治词乙', tag: '政治', suspect: false },
  { phrase: '五星红旗', tag: '政治', suspect: true, why: '正常爱国表达也会命中，误报风险高' }, { phrase: '天安门', tag: '政治', suspect: true, why: '地名/旅游内容会大量误报' },
  { phrase: '毛主席', tag: '政治', suspect: true, why: '历史、教育内容会误报' }, { phrase: '习', tag: '政治', suspect: true, why: '单字规则，会命中“学习”等大量正常词' },
]
// Suggested level per tag: derived from the tag, so the owner does not have to judge every word by hand.
export const TAG_LEVELS = { 政治: 'HIGH', 诈骗: 'MEDIUM', 引流: 'MEDIUM', 广告: 'LOW' }
function importPreview() {
  const state = ensureReview(), existing = new Set(state.rules.filter(r => !r.deleted).map(r => normalize(r.phrase)))
  const items = importSample.map(i => ({ ...i, tags: [i.tag], duplicate: existing.has(normalize(i.phrase)) }))
  const groups = [...new Set(importSample.map(i => i.tag))].map(tag => ({ tag, level: TAG_LEVELS[tag] || 'MEDIUM', count: items.filter(i => i.tag === tag).length, suspect: items.filter(i => i.tag === tag && i.suspect).length, duplicate: items.filter(i => i.tag === tag && i.duplicate).length }))
  return { source: copy(IMPORT_SOURCE), totalClaimed: '6W+（README 称）', sampleSize: items.length, groups, items,
    rules: ['按标签批量导入：每个标签自动给出建议等级（政治→高、诈骗/引流→中、广告→低），无需逐词人工审批；可改等级、选择导入后启用或停用。', '疑似误报词（如单字、常见地名人名）默认不导入，需要时勾选“包含疑似误报词”。', '导入后可在规则列表按标签批量启用/停用；整库启用会严重误报，请先查看下方检索到的样例。'] }
}
function importCommit(body) {
  const state = ensureReview(), groups = Array.isArray(body.groups) ? body.groups : []
  const existing = new Set(state.rules.filter(r => !r.deleted).map(r => normalize(r.phrase))); let added = 0, skipped = 0, suspectSkipped = 0
  guardWrite()
  for (const group of groups) for (const item of importSample.filter(i => i.tag === group.tag)) {
    if (item.suspect && !body.includeSuspect) { suspectSkipped += 1; continue }
    if (existing.has(normalize(item.phrase))) { skipped += 1; continue }
    const level = ['LOW', 'MEDIUM', 'HIGH'].includes(group.level) ? group.level : 'MEDIUM'
    state.rules.unshift({ id: nextId(), phrase: item.phrase, note: `导入：${item.tag}${item.suspect ? '（疑似误报）' : ''}`, tag: item.tag, enabled: !!group.enabled, level, version: 1, updatedAt: stamp() }); existing.add(normalize(item.phrase)); added += 1
  }
  state.ruleHistory.unshift({ at: stamp(), operator: currentPrototypeUser().displayName, action: `按标签批量导入 ${added} 条（${groups.map(g => `${g.tag}→${{ HIGH: '高', MEDIUM: '中', LOW: '低' }[g.level] || '中'}${g.enabled ? '·启用' : '·停用'}`).join('，')}）`, phrase: IMPORT_SOURCE.name, enabled: false })
  return { added, skipped, suspectSkipped, message: `已导入 ${added} 条；跳过重复 ${skipped} 条、疑似误报 ${suspectSkipped} 条。` }
}
function batchToggle(body) {
  const state = ensureReview(), tag = String(body.tag || ''), list = state.rules.filter(r => !r.deleted && r.tag === tag)
  if (!tag || !list.length) problem(404, '没有该标签的规则。')
  guardWrite(); for (const r of list) { r.enabled = !!body.enabled; r.version = (r.version || 1) + 1; r.updatedAt = stamp() }
  state.ruleHistory.unshift({ at: stamp(), operator: currentPrototypeUser().displayName, action: `按标签批量${body.enabled ? '启用' : '停用'}「${tag}」${list.length} 条`, phrase: tag, enabled: !!body.enabled })
  return { count: list.length, message: `已${body.enabled ? '启用' : '停用'}标签「${tag}」下 ${list.length} 条规则，仅影响后续提交。` }
}

// ---------------------------------------------------------------- routes
export function handleReview(method, path, body = {}, params = new URLSearchParams()) {
  if (!path.startsWith('/api/review/')) return { handled: false }
  const state = ensureReview(); let data
  if (method === 'GET' && path === '/api/review/public') {
    const user = currentPrototypeUser(), restricted = (getDb().permissions[user?.role] || []).includes('content.restricted')
    const list = state.documents.filter(d => d.published && !d.down && !d.withdrawn && !d.deleted && (d.published.visibility !== 'RESTRICTED' || restricted) && normalize([d.published.title, d.published.summary].join(' ')).includes(normalize(params.get('q'))))
    data = { items: list.map(publicSnapshot), total: list.length }
  } else if (method === 'GET' && path === '/api/review/mine') {
    const user = actor(); sweep(); data = { items: state.documents.filter(d => d.authorId === user.id && !d.deleted).map(mineView) }
  } else if (method === 'POST' && path === '/api/review/mine') {
    const user = actor(); if (!(getDb().permissions[user.role] || []).includes(body.type === 'COMMENT' ? 'post.interact' : body.type === 'COLLECTION' ? 'collection.manage' : 'post.create')) problem(403, '当前账号没有对应创作权限。')
    guardWrite(); const doc = document(nextId(), ['ARTICLE', 'COMMENT', 'COLLECTION'].includes(body.type) ? body.type : 'ARTICLE', user.id, '未命名内容', ''); state.documents.push(doc); data = mineView(doc)
  } else if (/^\/api\/review\/mine\/\d+/.test(path)) {
    const doc = owned(path.split('/')[4])
    if (method === 'PUT' && /^\/api\/review\/mine\/\d+$/.test(path)) { guardWrite(); saveDraft(doc, body); data = mineView(doc) }
    else if (method === 'POST' && path.endsWith('/submit')) { const user = actor(); if (!(getDb().permissions[user.role] || []).includes(doc.type === 'COMMENT' ? 'post.interact' : doc.type === 'COLLECTION' ? 'collection.manage' : 'post.create')) problem(403, '当前账号没有对应发布权限。'); data = submit(doc, body) }
    else if (method === 'POST' && (path.endsWith('/withdraw') || path.endsWith('/delete'))) {
      if (path.endsWith('/delete') && doc.type !== 'COMMENT') problem(400, '首期只支持本人删除评论。')
      guardWrite(); invalidate(doc, '作者撤回或删除，旧提交失效。'); doc.withdrawn = true; if (path.endsWith('/delete')) doc.deleted = true
      const flow = doc.flows[0]; if (flow) addEvent(flow, 'RESULT', doc.deleted ? '作者删除' : '作者撤回', '私人原稿保留')
      log(doc, doc.deleted ? '作者删除' : '作者撤回', '私人原稿保留'); data = { message: doc.deleted ? '评论已删除。' : '已撤回，私人工作稿保留。' }
    } else problem(404, '接口不存在。')
  } else if (method === 'GET' && path === '/api/review/queue') {
    actor(true); sweep()
    if (state.settings.failLoadOnce) { state.settings.failLoadOnce = false; problem(503, '审批列表暂时无法读取，请重试。') }
    const mode = params.get('status') || 'PENDING', q = normalize(params.get('q')), type = params.get('type'), level = params.get('level'), days = Number(params.get('days') || 0)
    const all = state.tasks.map(taskView)
    const list = all.filter(t => (mode === 'ALL' || (mode === 'DONE' ? t.status !== 'PENDING' : t.status === mode)) && (!type || t.type === type) && (!level || t.level === level) && (!days || now() - Date.parse(t.createdAt) <= days * 86400000) && normalize(t.snapshot.title + t.authorName).includes(q))
    const page = Math.max(0, Number(params.get('page')) || 0), size = 6
    const today = new Date(now()).toDateString()
    data = { items: list.slice(page * size, (page + 1) * size), total: list.length, page, size,
      pending: all.filter(t => t.status === 'PENDING').length, medium: all.filter(t => t.status === 'PENDING' && t.level === 'MEDIUM').length, high: all.filter(t => t.status === 'PENDING' && t.level === 'HIGH').length,
      doneToday: all.filter(t => t.status !== 'PENDING' && t.status !== 'INVALID' && t.closedAt && new Date(t.closedAt).toDateString() === today).length, low: state.noted.length }
    if (state.settings.slowLoad) return { handled: true, data: new Promise(resolve => setTimeout(() => resolve(data), 1500)) }
  } else if (method === 'GET' && path === '/api/review/noted') { actor(true); data = { items: state.noted.map(n => ({ ...n, title: findDoc(n.docId).draft.title, authorName: authorName(findDoc(n.docId).authorId), excerpt: String(findDoc(n.docId).published?.body || findDoc(n.docId).draft.body).slice(0, 120), published: !!findDoc(n.docId).published && !findDoc(n.docId).down })) } }
  else if (method === 'POST' && path === '/api/review/noted') { actor(true); const n = state.noted.find(x => x.docId === Number(body.docId)); if (!n) problem(404, '记录不存在。'); n.reviewed = body.action === 'REVIEWED'; n.reviewedBy = n.reviewed ? currentPrototypeUser().displayName : ''; data = { message: n.reviewed ? '已标记为已阅。' : '已取消已阅标记。' } }
  else if (method === 'GET' && path === '/api/review/publications') { actor(true); sweep(); data = { items: state.documents.filter(d => d.published && !d.withdrawn && !d.deleted).map(d => ({ ...publicSnapshot(d), down: d.down, self: d.authorId === currentPrototypeUser().id, history: copy(d.history) })) } }
  else if (method === 'GET' && /^\/api\/review\/tasks\/\d+$/.test(path)) { actor(true); sweep(); const task = state.tasks.find(t => t.id === Number(path.split('/')[4])); if (!task) problem(404, '未找到这条审批提交，请返回列表核对。'); data = taskView(task) }
  else if (method === 'GET' && /^\/api\/review\/publications\/\d+$/.test(path)) { actor(true); sweep(); const doc = findDoc(path.split('/')[4]); if (!doc.published || doc.withdrawn || doc.deleted) problem(404, '未找到可处置的发布版。'); data = { ...publicSnapshot(doc), down: doc.down, self: doc.authorId === currentPrototypeUser().id, history: copy(doc.history) } }
  else if (method === 'POST' && /^\/api\/review\/tasks\/\d+$/.test(path)) data = decide(path.split('/')[4], body)
  else if (method === 'POST' && /^\/api\/review\/publications\/\d+$/.test(path)) data = managePublication(path.split('/')[4], body)
  else if (method === 'GET' && path === '/api/review/rules/import-preview') { actor(false, true); data = importPreview() }
  else if (method === 'POST' && path === '/api/review/rules/import') { actor(false, true); data = importCommit(body) }
  else if (method === 'POST' && path === '/api/review/rules/batch') { actor(false, true); data = batchToggle(body) }
  else if (path === '/api/review/rules') {
    actor(false, true)
    if (method === 'GET') {
      const all = state.rules.filter(r => !r.deleted), q = normalize(params.get('q')), status = params.get('status'), level = params.get('level'), tag = params.get('tag'), page = Math.max(0, Number(params.get('page')) || 0), size = 8
      const items = all.filter(r => (!status || status === 'ENABLED' && r.enabled || status === 'DISABLED' && !r.enabled) && (!level || r.level === level) && (!tag || r.tag === tag) && normalize(r.phrase + ' ' + r.note).includes(q))
      data = { items: copy(items.slice(page * size, (page + 1) * size)), total: items.length, page, size, enabledCount: all.filter(r => r.enabled).length, disabledCount: all.filter(r => !r.enabled).length, levels: { HIGH: all.filter(r => r.enabled && r.level === 'HIGH').length, MEDIUM: all.filter(r => r.enabled && r.level === 'MEDIUM').length, LOW: all.filter(r => r.enabled && r.level === 'LOW').length }, tags: [...new Set(all.filter(r => r.tag).map(r => r.tag))].map(t => ({ tag: t, total: all.filter(r => r.tag === t).length, enabled: all.filter(r => r.tag === t && r.enabled).length })), history: copy(state.ruleHistory) }
    } else if (method === 'POST') {
      const phrase = String(body.phrase || '').trim(), note = String(body.note || '').trim(), level = ['LOW', 'MEDIUM', 'HIGH'].includes(body.level) ? body.level : 'MEDIUM'
      if (!phrase || phrase.length > 40) problem(400, '请输入1–40字的词或短语。')
      if (!normalize(phrase)) problem(400, '规则只包含标点或空白，无法用于匹配。')
      if (note.length > 200) problem(400, '规则备注最多200字。')
      if (state.rules.some(r => !r.deleted && r.id !== body.id && normalize(r.phrase) === normalize(phrase))) problem(400, '此词或短语已存在，请直接编辑原规则。')
      const existing = state.rules.find(r => r.id === body.id && !r.deleted)
      if (body.id && !existing) problem(404, '规则已被删除，请重新读取列表。')
      if (existing && Number(body.expectedVersion) !== (existing.version || 1)) problem(409, '规则已在别处修改，请重新读取后再编辑。')
      guardWrite(); const rule = { id: existing?.id || nextId(), phrase, note, enabled: body.enabled !== false, level, version: existing ? (existing.version || 1) + 1 : 1, updatedAt: stamp() }
      if (existing) Object.assign(existing, rule); else state.rules.unshift(rule)
      state.ruleHistory.unshift({ at: stamp(), operator: currentPrototypeUser().displayName, action: existing ? '修改规则' : '新增规则', phrase, enabled: rule.enabled, level }); data = { message: '规则已保存，仅影响后续提交；未扫描或下架旧内容。' }
    } else problem(404, '接口不存在。')
  } else if (method === 'DELETE' && /^\/api\/review\/rules\/\d+$/.test(path)) {
    actor(false, true); const rule = state.rules.find(r => r.id === Number(path.split('/')[4]) && !r.deleted)
    if (!rule) problem(404, '规则已被删除，请重新读取列表。')
    if (Number(body.expectedVersion) !== (rule.version || 1)) problem(409, '规则已在别处修改，请核对后再删除。')
    guardWrite(); rule.deleted = true; rule.enabled = false; rule.version = (rule.version || 1) + 1; rule.updatedAt = stamp()
    state.ruleHistory.unshift({ at: stamp(), operator: currentPrototypeUser().displayName, action: '删除规则', phrase: rule.phrase, enabled: false })
    data = { message: '规则已删除，不再参与后续检测；既有发布内容不变，操作记录保留。' }
  } else problem(404, '接口不存在。')
  return { handled: true, data }
}

// Explicit scene tool only. It never changes the production roles or default grants.
export function loadReviewScene() {
  const d = getDb(); ensureReview(d)
  d.review = null; ensureReview(d)
  const author = d.users.find(u => u.id === 3)
  if (author.accountStatus === 'DISABLED') { author.accountStatus = 'ACTIVE'; author.sessionEpoch += 1 }
  if (!d.roles.some(r => r.key === 'REVIEW_DEMO')) d.roles.push({ key: 'REVIEW_DEMO', label: '审核员（演示授权）', description: '演示面板明确授权的角色，只能审批内容' })
  d.permissions.REVIEW_DEMO = ['content.public', 'profile.manage', REVIEW_PERMISSION]
  const reviewer = d.users.find(u => u.id === 4); reviewer.role = 'REVIEW_DEMO'; reviewer.accountStatus = 'ACTIVE'
}
