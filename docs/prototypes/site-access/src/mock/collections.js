// FINNS-19 R1 (requirements revision 2/3): browser-only lifecycle model for traditional collections (outline editor).
// Reuses the FINNS-17 three-level pipeline and the FINNS-18 article model; collections live in review.documents (type COLLECTION, id >= 1900)
// and are mirrored into db.collections so that the existing public list / self-selection pages see them.
import { getDb, nextId } from './db'
import { currentPrototypeUser } from './user-admin'
import { ensureReview, document, pipeline, detect, invalidate, actor, guardWrite, log, sweep, copy, stamp, problem, authorFlow, authorName } from './review'
import { ensurePublishing, compositeStatus } from './publishing'

export const COLLECTION_LIMITS = { name: 80, description: 260, sections: 50, entries: 500, note: 4000 }
const PAGE_SIZE = 5
const blank = value => !String(value ?? '').trim()
const count = value => [...String(value ?? '')].length
export const TEMPLATES = [
  { key: 'BLANK', title: '空白专题', sections: ['第一章'], example: '从一段内容开始，章节之后再整理。' },
  { key: 'RENTING', title: '租房经验', sections: ['预算与区域', '看房检查清单', '签约与入住'], example: '例如：看房时要检查的十件事。' },
  { key: 'TUTORIAL', title: '教程步骤', sections: ['准备工作', '操作步骤', '常见问题'], example: '例如：从零部署一个个人站点。' },
  { key: 'EXERCISE', title: '练习计划', sections: ['目标', '每周安排', '复盘'], example: '例如：三个月的阅读计划。' },
  { key: 'RETROSPECTIVE', title: '项目复盘', sections: ['做法', '结果', '改进'], example: '例如：一次上线的复盘。' },
]

const articleDocs = () => ensurePublishing().documents.filter(d => d.type === 'ARTICLE')
const articleLive = d => !!d && !d.deleted && !!d.published && !d.down && !d.withdrawn
export function articleState(postId) {
  const d = articleDocs().find(x => x.id === Number(postId))
  if (!d || d.deleted) return { ok: false, reason: '原文已删除' }
  if (d.down) return { ok: false, reason: '原文已被下架' }
  if (d.withdrawn) return { ok: false, reason: '原文已被作者撤回' }
  if (!d.published) return { ok: false, reason: '原文尚未公开（发布后才能引用）' }
  return { ok: true, reason: '' }
}

let seq = 0
const entryOf = (e = {}) => ({ id: e.id || nextId() + (++seq) / 1000, kind: e.kind || 'NOTE', title: String(e.title ?? ''), body: String(e.body ?? ''), annotation: String(e.annotation ?? ''), externalUrl: String(e.externalUrl ?? ''), postId: e.kind === 'POST' ? (e.postId ?? null) : null, postTitle: e.kind === 'POST' ? String(e.postTitle ?? '') : '' })
const sectionOf = (s = {}) => ({ id: s.id || nextId() + (++seq) / 1000, title: String(s.title ?? ''), description: String(s.description ?? ''), entries: (s.entries || []).map(entryOf) })
// Text that the sensitivity check covers: every field a reader can see (referenced articles were checked on their own).
export function flatten(d) {
  const parts = [d.audience, d.goal]
  for (const s of d.sections) { parts.push(s.title, s.description); for (const e of s.entries) { if (e.kind !== 'POST') parts.push(e.title); parts.push(e.annotation); if (e.kind === 'NOTE') parts.push(e.body); if (e.kind === 'LINK') parts.push(e.externalUrl) } }
  return parts.filter(x => !blank(x)).join('\n')
}
const contentOf = d => JSON.stringify([d.title, d.summary, d.format, d.audience, d.goal, d.visibility, d.sections.map(s => [s.title, s.description, s.entries.map(e => [e.kind, e.title, e.body, e.annotation, e.externalUrl, e.postId])])])
const sizes = d => ({ sections: d.sections.length, entries: d.sections.reduce((n, s) => n + s.entries.length, 0) })

function build(id, name, description, sections, { visibility = 'PUBLIC', format = 'CUSTOM', audience = '', goal = '', authorId = 3 } = {}) {
  const doc = document(id, 'COLLECTION', authorId, name, '')
  doc.draft = { title: name, summary: description, body: '', visibility, version: 1, format, audience, goal, sections: sections.map(sectionOf) }
  doc.draft.body = flatten(doc.draft)
  return doc
}
const note = (title, body) => ({ kind: 'NOTE', title, body })
const post = (postId, annotation = '') => ({ kind: 'POST', postId, annotation, postTitle: articleDocs().find(d => d.id === postId)?.draft.title || '' })
const edit = (doc, patch) => { doc.draft = { ...doc.draft, ...patch, version: doc.draft.version + 1 }; if (patch.sections) doc.draft.sections = patch.sections.map(sectionOf); doc.draft.body = flatten(doc.draft) }

export function ensureCollections() {
  const state = ensureReview(); ensurePublishing()
  if (!state.collectionsSeeded) {
    state.collectionsSeeded = true
    const add = doc => { state.documents.push(doc); return doc }
    // 1901: public v1 + frozen pending update v2 + newer working draft v3
    const three = add(build(1901, '独立开发者上手路线', '从选题到上线的一条路径。', [{ title: '开始之前', entries: [note('', '先确定要解决的问题。')] }, { title: '核心资料', entries: [post(1803, '看看别人怎么选写作工具'), { kind: 'LINK', title: '官方入门文档', externalUrl: 'https://example.com/docs' }] }], { format: 'READING_PATH', audience: '想做第一个小项目的人' }))
    pipeline(three, { ago: 600 })
    edit(three, { sections: [...three.draft.sections.map(copy), { title: '补充', entries: [note('', '这一章包含演示拦截词，所以进入人工审核；旧公开版继续可读。')] }] }); pipeline(three, { ago: 90 })
    edit(three, { sections: [...three.draft.sections.map(copy), { title: '之后继续写的一章', entries: [note('', '这是之后继续修改的工作稿，不会替换已提交的内容。')] }] })
    // 1902: incomplete private draft (never published, deletable) with an empty note, a link without URL and a reference to a private article
    add(build(1902, '租房看房清单（未完成）', '', [{ title: '看房前', entries: [note('', ''), { kind: 'LINK', title: '房源平台', externalUrl: '' }, post(1802, '备份的想法')] }], { format: 'GUIDE' }))
    // 1903: clean public
    pipeline(add(build(1903, '个人站点部署笔记', '域名、反向代理与备份。', [{ title: '部署', entries: [note('反向代理', '用 Nginx 做反向代理，证书自动续期。')] }], { format: 'GUIDE' })), { ago: 300 })
    // 1904: public restricted
    pipeline(add(build(1904, '内部知识库（受限）', '只给受信成员阅读。', [{ title: '值班', entries: [note('', '值班交接步骤。')] }], { visibility: 'RESTRICTED' })), { ago: 280 })
    // 1905: published then withdrawn (selected by reader 2; the selection stays but is hidden)
    const withdrawn = add(build(1905, '一次已撤回的专题', '作者撤回后只保留私人稿。', [{ title: '内容', entries: [note('', '这个专题曾经公开。')] }]))
    pipeline(withdrawn, { ago: 800 }); invalidate(withdrawn, '作者撤回，旧提交失效。'); withdrawn.withdrawn = true
    withdrawn.flows[0].events.push({ id: nextId(), stage: 'RESULT', title: '作者撤回', note: '私人原稿保留', detail: '', actor: '', at: stamp(700) })
    // 1906: manually taken down
    const down = add(build(1906, '已被独立下架的专题', '需要另一位审批者复核恢复。', [{ title: '内容', entries: [note('', '这个专题已被人工下架。')] }]))
    pipeline(down, { ago: 900 }); down.down = true; log(down, '人工下架', '演示：内容被独立下架', { displayName: '你' })
    // 1907: high sensitivity auto-rejected
    pipeline(add(build(1907, '一次高敏感命中的专题', '自动不通过后稿件仍可继续编辑。', [{ title: '内容', entries: [note('', '这里出现了演示高敏词。')] }])), { ago: 25 })
    // 1908: published; one referenced article was withdrawn afterwards → readers see a placeholder for that entry only
    pipeline(add(build(1908, '写作工具与流程合集', '引用了几篇文章。', [{ title: '文章', entries: [post(1803, '工具'), post(1804, '一篇后来被撤回的文章'), note('', '收尾说明。')] }])), { ago: 200 })
    const picks = (getDb().selections[2] ||= []); for (const id of [1901, 1905]) if (!picks.includes(id)) picks.push(id)
  }
  for (const doc of state.documents.filter(d => d.type === 'COLLECTION' && d.id >= 1900)) syncPublic(doc)
  return state
}

const live = doc => !!doc.published && !doc.down && !doc.withdrawn && !doc.deleted
function syncPublic(doc) {
  const d = getDb(), published = doc.published
  let row = d.collections.find(c => c.id === doc.id)
  if (!row) { row = { id: doc.id, ownerId: doc.authorId, postIds: [] }; d.collections.push(row) }
  Object.assign(row, { name: published?.title || doc.draft.title, description: published?.summary || '', format: published?.format || doc.draft.format, audience: published?.audience || '', goal: published?.goal || '', visibility: published?.visibility || 'PUBLIC', status: live(doc) ? 'PUBLISHED' : 'ARCHIVED', postIds: published ? published.sections.flatMap(s => s.entries).filter(e => e.kind === 'POST' && e.postId).map(e => e.postId) : [] })
}

function mine(id) {
  const user = actor(), doc = ensureCollections().documents.find(x => x.id === Number(id) && x.type === 'COLLECTION' && x.id >= 1900)
  if (!(getDb().permissions[user.role] || []).includes('collection.manage')) problem(403, '当前角色不能管理专题')
  if (!doc || doc.deleted || doc.authorId !== user.id) problem(404, '专题不存在')
  return doc
}
const pendingTask = doc => ensureReview().tasks.find(t => t.docId === doc.id && t.status === 'PENDING')
const selectedCount = doc => Object.values(getDb().selections).filter(list => list.includes(doc.id)).length

function refsOf(draft) {
  const out = {}
  for (const s of draft.sections) for (const e of s.entries) if (e.kind === 'POST' && e.postId) out[e.postId] = articleState(e.postId)
  return out
}
function view(doc) {
  const task = pendingTask(doc), status = compositeStatus(doc), refs = refsOf(doc.draft), d = doc.draft
  const frozen = task ? { version: task.snapshot.version, name: task.snapshot.title, kind: task.kind, level: task.kind === 'RECHECK' ? 'HIGH' : task.level, ...sizes(task.snapshot) } : null
  return {
    id: doc.id, type: 'COLLECTION', revision: d.version, status, refs, selectedCount: selectedCount(doc),
    draft: { id: doc.id, name: d.title, description: d.summary, format: d.format, audience: d.audience, goal: d.goal, visibility: d.visibility, revision: d.version, sections: copy(d.sections) },
    published: doc.published ? { version: doc.published.version, name: doc.published.title, visibility: doc.published.visibility, ...sizes(doc.published) } : null,
    publicVisible: live(doc), submitted: frozen, down: doc.down, withdrawn: doc.withdrawn, canCancel: !!task && task.kind === 'REVIEW',
    hasInvalidRefs: Object.values(refs).some(r => !r.ok), flows: doc.flows.map(authorFlow), updatedAt: doc.history[0]?.at || doc.flows[0]?.startedAt || null,
  }
}
const deletable = doc => !doc.published && !doc.withdrawn && !doc.approvedOnce && !pendingTask(doc)
function row(doc) {
  const v = view(doc)
  return { id: doc.id, name: v.draft.name, description: v.draft.description, format: v.draft.format, itemCount: sizes(doc.draft).entries, status: v.status, version: v.revision, visibility: v.draft.visibility,
    publicName: v.published?.name || '', publicVisible: v.publicVisible, hasPublished: !!v.published, canCancel: v.canCancel, canWithdraw: v.publicVisible, canDelete: deletable(doc),
    hasInvalidRefs: v.hasInvalidRefs, selectedCount: v.selectedCount, updatedAt: v.updatedAt, flows: v.flows, editorType: 'CLASSIC' }
}

function clean(body) {
  const user = actor(), L = COLLECTION_LIMITS
  const name = String(body.name ?? '').trim()
  if (!name || count(name) > L.name) problem(400, `专题名称需要 1–${L.name} 个字符；输入已保留。`)
  if (count(body.description) > L.description) problem(400, `专题简介最多 ${L.description} 个字符；输入已保留。`)
  const sections = (Array.isArray(body.sections) ? body.sections : []).map(sectionOf)
  const total = sections.reduce((n, s) => n + s.entries.length, 0)
  if (sections.length > L.sections || total > L.entries) problem(400, `专题最多 ${L.sections} 章、${L.entries} 条；输入已保留。`)
  for (const s of sections) for (const e of s.entries) if (count(e.body) > L.note) problem(400, `「${s.title || '未命名章节'}」中有正文超过 ${L.note} 个字符；输入已保留。`)
  const visibility = body.visibility === 'RESTRICTED' ? 'RESTRICTED' : 'PUBLIC'
  for (const s of sections) for (const e of s.entries) if (e.kind === 'POST' && e.postId) e.postTitle = articleDocs().find(a => a.id === Number(e.postId))?.draft.title || e.postTitle
  const d = { title: name, summary: String(body.description ?? ''), format: ['GUIDE', 'READING_PATH', 'RESOURCE_LIST', 'CUSTOM'].includes(body.format) ? body.format : 'CUSTOM', audience: String(body.audience ?? ''), goal: String(body.goal ?? ''), visibility, sections }
  return { user, d }
}
function applyDraft(doc, d, user) {
  if (d.visibility === 'RESTRICTED' && doc.draft.visibility !== 'RESTRICTED' && !(getDb().permissions[user.role] || []).includes('content.restricted')) problem(403, '当前角色不能把专题设为受限')
  const next = { ...doc.draft, ...d }; next.body = flatten(next)
  if (contentOf(next) === contentOf(doc.draft)) return false
  doc.draft = { ...next, version: doc.draft.version + 1 }; return true
}
function issues(draft) {
  const out = []
  if (!draft.sections.length) out.push('至少添加一个章节')
  let n = 0
  for (const s of draft.sections) for (const [i, e] of s.entries.entries()) {
    n++; const at = `${s.title || '未命名章节'} / 第 ${i + 1} 条`
    if (e.kind === 'NOTE' && blank(e.body)) out.push(`${at}：补充正文，或删除这条空内容`)
    if (e.kind === 'LINK' && (blank(e.title) || !/^https?:\/\/\S+/.test(e.externalUrl))) out.push(`${at}：补充链接标题和有效的 http/https 地址`)
    if (e.kind === 'POST') { if (!e.postId) out.push(`${at}：选择一篇文章`); else if (!articleState(e.postId).ok) out.push(`${at}：引用的文章不可用（${articleState(e.postId).reason}），请替换或删除`) }
  }
  if (!n) out.push('至少添加一个有效条目')
  return out
}

function readerOutline(doc, source, ownerView = false) {
  const outline = (e) => {
    if (e.kind !== 'POST') return { id: e.id, kind: e.kind, sortOrder: 0, title: e.title, body: e.body, annotation: e.annotation, externalUrl: e.externalUrl }
    const st = e.postId ? articleState(e.postId) : { ok: false }
    if (!st.ok) return { id: e.id, kind: 'POST', sortOrder: 0, title: '', annotation: '', postId: null, postTitle: '原文已不可用', postSummary: '', postAuthor: '' } // nothing of the article or the author's note leaks
    const art = articleDocs().find(a => a.id === Number(e.postId))
    return { id: e.id, kind: 'POST', sortOrder: 0, title: '', annotation: e.annotation, postId: art.id, postTitle: art.published.title, postSummary: art.published.summary, postAuthor: authorName(art.authorId) }
  }
  return { collection: { id: doc.id, name: source.title, description: source.summary, ownerName: authorName(doc.authorId), format: source.format, audience: source.audience, goal: source.goal },
    sections: source.sections.map((s, i) => ({ id: s.id, title: s.title, description: s.description, sortOrder: i, entries: s.entries.map((e, j) => ({ ...outline(e), sortOrder: j })) })) }
}

function create(body, sections, user) {
  const { d } = clean({ ...body, sections })
  const doc = document(nextId() + 1900, 'COLLECTION', user.id, d.title, ''); doc.draft = { ...doc.draft, ...d, version: 1 }; doc.draft.body = flatten(doc.draft)
  ensureCollections().documents.push(doc); log(doc, '创建私人草稿', '来源：' + (body.source || '直接编写')); syncPublic(doc); return doc
}
const parseText = text => {
  const sections = []; let cur = null, name = ''
  for (const line of String(text || '').split(/\r?\n/)) {
    const h1 = /^#\s+(.+)/.exec(line), h2 = /^##\s+(.+)/.exec(line)
    if (h1 && !name && !sections.length) name = h1[1].trim()
    else if (h2 || h1) { cur = { title: (h2 || h1)[1].trim(), description: '', entries: [] }; sections.push(cur) }
    else if (line.trim()) { cur ||= (sections.push({ title: '导入内容', description: '', entries: [] }), sections.at(-1)); cur.entries.push({ kind: 'NOTE', title: '', body: line.trim(), annotation: '' }) }
  }
  return { name: name || '导入的专题', sections }
}

export function handleCollection(method, path, body = {}, params = new URLSearchParams()) {
  const mineList = path === '/api/me/collections', ids = /^\/api\/me\/collections\/(\d+)(\/\w+)?$/.exec(path), pub = /^\/api\/collections\/(\d+)\/outline$/.exec(path)
  const other = ['/api/me/collection-templates', '/api/me/collections/drafts', '/api/me/collection-imports/preview', '/api/me/collection-imports/commit', '/api/me/posts'].includes(path)
  if (!mineList && !other && !(ids && Number(ids[1]) >= 1900) && !(pub && Number(pub[1]) >= 1900)) { if (/^\/api\/(collections|me\/collections)/.test(path)) ensureCollections(); return { handled: false } }
  const state = ensureCollections(); let data
  if (pub) {
    const doc = state.documents.find(x => x.id === Number(pub[1])), user = currentPrototypeUser()
    if (!doc || !live(doc)) problem(404, '专题不存在或尚未公开')
    if (doc.published.visibility === 'RESTRICTED' && !(user && (getDb().permissions[user.role] || []).includes('content.restricted'))) problem(user ? 403 : 401, '这是受限专题，当前角色无权阅读')
    return { handled: true, data: readerOutline(doc, doc.published) }
  }
  if (method === 'GET' && path === '/api/me/posts') {
    const user = actor(), items = articleDocs().filter(a => a.authorId === user.id && articleLive(a)).map(a => ({ id: a.id, title: a.published.title, summary: a.published.summary }))
    return { handled: true, data: { items, page: 0, size: 50, total: items.length, hasMore: false } }
  }
  if (method === 'GET' && path === '/api/me/collection-templates') { actor(); return { handled: true, data: TEMPLATES } }
  if (method === 'GET' && mineList) {
    const user = actor(); sweep()
    const filter = params.get('state') || 'ALL', q = (params.get('q') || '').trim().toLowerCase(), p = Math.max(0, Number(params.get('page') || 0))
    const all = state.documents.filter(d => d.type === 'COLLECTION' && d.id >= 1900 && d.authorId === user.id && !d.deleted).map(row)
    const match = r => ({ ALL: true, DRAFT: ['PRIVATE', 'SAVED'].includes(r.status.work) && !r.hasPublished, REVIEW: ['REVIEWING', 'RECHECK', 'REJECTED'].includes(r.status.work), LIVE: r.publicVisible, OFF: ['WITHDRAWN', 'DOWN'].includes(r.status.publicState) }[filter])
    const name = (params.get('name') || '').trim().toLowerCase(), pub = params.get('pub') || '', work = params.get('work') || '', vis = params.get('vis') || '', bad = params.get('bad') || ''
    const list = all.filter(r => !q || `${r.name} ${r.description}`.toLowerCase().includes(q)).filter(match)
      .filter(r => (!name || r.name.toLowerCase().includes(name)) && (!pub || r.status.publicState === pub) && (!work || r.status.work === work) && (!vis || r.visibility === vis) && (!bad || r.hasInvalidRefs))
    data = { items: list.slice(p * PAGE_SIZE, p * PAGE_SIZE + PAGE_SIZE), page: p, size: PAGE_SIZE, total: list.length, hasMore: (p + 1) * PAGE_SIZE < list.length, counts: { ALL: all.length } }
    return { handled: true, data }
  }
  const user0 = () => { const u = actor(); if (!(getDb().permissions[u.role] || []).includes('collection.manage')) problem(403, '当前角色不能管理专题'); return u }
  if (method === 'GET' && path === '/api/me/collections/drafts') problem(404, '接口不存在')
  if (method === 'POST' && path === '/api/me/collections/drafts') {
    const user = user0(); if (blank(body.name)) problem(400, '先给专题起个名字。'); guardWrite()
    const tpl = TEMPLATES.find(t => t.key === body.templateKey) || TEMPLATES[0]
    const sections = tpl.sections.map(title => ({ title, description: '', entries: [] }))
    if (!blank(body.initialContent)) sections[0].entries.push({ kind: 'NOTE', title: '', body: body.initialContent, annotation: '' })
    data = view(create({ ...body, source: '模板：' + tpl.title }, sections, user))
  } else if (method === 'POST' && path === '/api/me/collection-imports/preview') {
    user0(); const doc = parseText(body.text); if (!doc.sections.length) problem(400, '没有识别到可导入的内容。')
    data = { document: { ...doc, description: '', format: 'CUSTOM', audience: '', goal: '', visibility: 'PUBLIC' }, warnings: [] }
  } else if (method === 'POST' && path === '/api/me/collection-imports/commit') {
    const user = user0(); guardWrite(); const key = 'import:' + body.idempotencyKey, hit = state.documents.find(d => d.receipts?.[key])
    if (hit) data = { id: hit.id }; else { const doc = create({ ...body.document, source: '导入' }, body.document.sections, user); doc.receipts[key] = { id: doc.id }; data = { id: doc.id } }
  } else if (ids) {
    const doc = mine(ids[1]), act = (ids[2] || '').slice(1)
    if (method === 'GET' && act === 'draft') data = view(doc)
    else if (method === 'GET' && act === 'preview') { data = readerOutline(doc, doc.draft); data.draftIssues = issues(doc.draft) }
    else if (method === 'PUT' && act === 'draft') {
      const { user, d } = clean(body)
      if (Number(body.revision) !== doc.draft.version) problem(409, `这个专题已在别处更新到 v${doc.draft.version}。你的输入已保留，请核对后再保存。`)
      guardWrite(); if (applyDraft(doc, d, user)) log(doc, '保存工作稿', `v${doc.draft.version}`); data = view(doc)
    } else if (method === 'POST' && act === 'publish') {
      const key = String(body.requestId || ''); if (!key) problem(400, '提交标识缺失，请重试。')
      if (doc.receipts[key]) data = { ...copy(doc.receipts[key]), replay: true, content: view(doc) }
      else {
        const { user, d } = clean(body)
        if (Number(body.revision) !== doc.draft.version) problem(409, `这个专题已在别处更新到 v${doc.draft.version}。你的输入已保留，请核对后再发布。`)
        if (doc.down) problem(409, '专题已被人工下架，保存或发布不能解除下架，请等待另一位审批者复核恢复。')
        const next = { ...doc.draft, ...d }; next.body = flatten(next); const found = issues(next)
        if (found.length) problem(400, `发布前还有 ${found.length} 处需要处理：${found.slice(0, 2).join('；')}${found.length > 2 ? '…' : ''}。草稿已保留，可以先保存。`)
        guardWrite(); if (applyDraft(doc, d, user)) log(doc, '保存工作稿', `v${doc.draft.version}（发布前保存）`)
        detect(doc.draft)
        const hadPublic = live(doc), response = pipeline(doc)
        if (response.outcome === 'PUBLISHED') log(doc, hadPublic ? '更新发布' : '发布', response.level === 'LOW' ? '低敏感放行并留痕' : '敏感检测通过')
        doc.receipts[key] = copy(response); syncPublic(doc); data = { ...response, replay: false, content: view(doc) }
      }
    } else if (method === 'POST' && act === 'cancel') {
      const task = pendingTask(doc)
      if (!task) problem(409, '当前没有待处理的提交，可能已被处理。请刷新查看最新状态。')
      if (task.kind === 'RECHECK') problem(409, '高敏感内容已自动不通过并转人工复核，不能取消；可以修改后重新提交。')
      guardWrite(); task.status = 'INVALID'; task.result = '作者取消送审'; task.closedAt = stamp(); doc.generation += 1
      const flow = doc.flows.find(f => f.id === task.flowId); if (flow) { flow.status = 'INVALID'; flow.events.push({ id: nextId(), stage: 'RESULT', title: '已取消送审', note: '只取消这次提交；工作稿与旧公开版保留。', detail: '', actor: '', at: stamp() }) }
      log(doc, '取消送审', '工作稿与旧公开版保留'); data = { message: '已取消本次送审，工作稿和旧公开版保留。', content: view(doc) }
    } else if (method === 'POST' && act === 'withdraw') {
      if (body.confirm !== true) problem(400, '撤回会停止公开并使待处理提交失效，需要二次确认。')
      if (doc.down) problem(409, '专题已被人工下架，撤回不会解除下架。')
      if (!doc.published || doc.withdrawn) problem(409, '当前没有公开版，无需撤回。')
      guardWrite(); invalidate(doc, '作者撤回，旧提交失效。'); doc.withdrawn = true
      doc.flows[0]?.events.push({ id: nextId(), stage: 'RESULT', title: '作者撤回', note: '私人原稿保留', detail: '', actor: '', at: stamp() })
      log(doc, '作者撤回', '私人原稿保留；读者自选记录保留，撤回期间隐藏'); syncPublic(doc)
      data = { message: '已撤回，公开入口不再显示；私人稿保留。读者的自选记录保留，重新发布并通过检测后自动恢复。', content: view(doc) }
    } else if (method === 'DELETE' && !act) {
      if (doc.published || doc.withdrawn || doc.approvedOnce) problem(409, '这个专题公开过，不能删除；可以保留为私人稿。')
      if (pendingTask(doc)) problem(409, '这个专题有待处理的提交，请先取消送审或等待处理结果。')
      guardWrite(); doc.deleted = true; log(doc, '删除私人草稿', '从未公开的私人稿'); syncPublic(doc)
      getDb().collections = getDb().collections.filter(c => c.id !== doc.id); data = { message: '私人草稿已删除。' }
    } else if (method === 'POST' && act === 'duplicate') {
      const user = user0(); guardWrite(); const sections = doc.draft.sections.map(s => ({ title: s.title, description: s.description, entries: body.structureOnly ? [] : s.entries.map(e => ({ ...copy(e), id: undefined })) }))
      const copyDoc = create({ ...doc.draft, name: doc.draft.title + '（副本）', description: doc.draft.summary, source: body.structureOnly ? '复制结构' : '复制内容' }, sections, user)
      data = { id: copyDoc.id }
    } else if (method === 'POST' && act === 'imports') {
      const user = user0(); if (Number(body.document?.revision) !== doc.draft.version) problem(409, '专题已更新，请重新读取后再追加。'); guardWrite()
      applyDraft(doc, { title: doc.draft.title, summary: doc.draft.summary, format: doc.draft.format, audience: doc.draft.audience, goal: doc.draft.goal, visibility: doc.draft.visibility, sections: [...doc.draft.sections, ...body.document.sections.map(sectionOf)] }, user)
      log(doc, '追加导入', '追加进私人工作稿，不自动发布'); data = { id: doc.id }
    } else problem(404, '接口不存在。')
  } else return { handled: false }
  return { handled: true, data }
}
