// 原型模拟后端：拦截页面发出的 /api/* fetch，让真实 FINNS 页面组件按原样运行。
// 这里演示的是“后端也必须检查”的规则，不是可上线的实现。
import { getDb, reloadDb, saveDb, now, nextId, MINUTE, DAY } from './db'
import { CATALOG, permLabel, isLocked, MAX_ROLES } from './roles'
import { handleAccount, ensureAccount } from './account'
import { handleReview, ensureReview } from './review'
import { handleContent } from './publishing'
import { handleCollection } from './collections'
import { ensureUserAdmin, currentPrototypeUser, normalManagerCount, changeAccountStatus, isSiteOwner, protectedRole, managementRestriction, ownershipOptions, transferOwnership } from './user-admin'

// 角色清单保存在服务端（R3 起可新增、改名、删除）。
const allRoles = () => getDb().roles
const roleKeys = () => allRoles().map((role) => role.key)
const findRole = (key) => allRoles().find((role) => role.key === key)
const roleLabel = (key) => !key || key === 'GUEST' ? '游客' : findRole(key)?.label || '已删除的角色'
// “最后一位管理者”= 角色拥有“管理角色”权限的用户。至少保留一位，防止无人能再管理。
const managerCount = normalManagerCount

// 权限以服务端保存的角色权限为准；管理员修改后，下一次请求即生效。
const permissionsOf = (role) => role === 'GUEST' ? CATALOG.filter((perm) => perm.guest).map((perm) => perm.key) : [...(getDb().permissions[role] || [])]

const VISITOR_KEY = 'finns-proto-visitor' // 模拟服务端下发的长期第一方 Cookie
const realFetch = window.fetch.bind(window)

class Problem extends Error { constructor(status, message) { super(message); this.status = status } }
const fail = (status, message) => { throw new Problem(status, message) }

const userById = (id) => getDb().users.find((user) => user.id === id)
const currentUser = currentPrototypeUser
const roleOf = (user) => user?.role || 'GUEST'
const can = (user, perm) => permissionsOf(roleOf(user)).includes(perm)

function requireUser() { const user = currentUser(); if (!user) fail(401, '登录后才能进行此操作'); return user }
function requirePerm(perm, message) {
  const user = requireUser()
  if (!can(user, perm)) fail(403, message || `当前角色（${roleLabel(user.role)}）没有此操作权限`)
  return user
}

const canReadCollection = (user, collection) => collection.visibility === 'PUBLIC' || can(user, 'content.restricted')
const visibleCollections = (user) => getDb().collections.filter((c) => c.status === 'PUBLISHED' && canReadCollection(user, c))
const page = (list, params, size = 20) => {
  const p = Math.max(0, Number(params.get('page') || 0)); const s = Math.min(50, Number(params.get('size') || size))
  return { items: list.slice(p * s, p * s + s), page: p, size: s, total: list.length, hasMore: (p + 1) * s < list.length }
}

const postSummary = (post) => ({ id: post.id, type: post.type, title: post.title, summary: post.summary, authorName: userById(post.authorId)?.displayName || '已注销用户', createdAt: post.createdAt, likes: post.likes })
const postView = (post) => ({ ...postSummary(post), body: post.body, authorId: post.authorId })
const collectionView = (c) => ({ id: c.id, name: c.name, description: c.description, ownerName: userById(c.ownerId)?.displayName, itemCount: c.postIds.length, updatedAt: new Date(now()).toISOString(), visibility: c.visibility })
const me = (user) => ({ id: user.id, email: user.email, displayName: user.displayName, role: user.role, roleLabel: roleLabel(user.role), permissions: permissionsOf(user.role) })
const adminUserView = (user) => { const operator = currentUser(); const restriction = managementRestriction(operator, user); const self = user.id === operator?.id; return ({ id: user.id, displayName: user.displayName, email: user.email, role: user.role, roleLabel: roleLabel(user.role), createdAt: user.createdAt, accountStatus: user.accountStatus, statusVersion: user.statusVersion, canManage: permissionsOf(user.role).includes('admin.roles'), isOwner: user.id === getDb().siteOwnerId, canAssignRole: !restriction, roleBlockReason: restriction, canChangeStatus: !restriction && !self, statusBlockReason: restriction || (self ? '不能停用自己的账号。' : '') }) }

function outline(c) {
  const posts = c.postIds.map((id) => getDb().posts.find((p) => p.id === id)).filter(Boolean)
  return {
    collection: { id: c.id, name: c.name, description: c.description, ownerName: userById(c.ownerId)?.displayName, format: c.format, audience: c.audience || '', goal: c.goal || '' },
    sections: [
      { id: c.id * 10 + 1, title: '开始之前', description: '先了解这条路径要解决的问题。', sortOrder: 0, entries: [{ id: c.id * 100 + 1, kind: 'NOTE', sortOrder: 0, title: '阅读说明', body: `${c.description}。（原型示例章节）`, annotation: '' }] },
      { id: c.id * 10 + 2, title: '核心资料', description: '', sortOrder: 1, entries: posts.map((p, i) => ({ id: c.id * 100 + 10 + i, kind: 'POST', sortOrder: i, postId: p.id, postTitle: p.title, postSummary: p.summary, postAuthor: userById(p.authorId)?.displayName, annotation: '' })) },
    ],
  }
}

// —— FINNS-10：网站访问计数（规则可在原型控制台切换） ——
function recordVisit(kind) {
  const d = getDb(); const user = currentUser(); const t = now()
  let visitorId = localStorage.getItem(VISITOR_KEY)
  if (!visitorId) { visitorId = `v-${Math.random().toString(36).slice(2, 8)}`; localStorage.setItem(VISITOR_KEY, visitorId) }
  const state = d.visits.visitors[visitorId] || { lastSeen: 0, lastCountedDay: '' }
  const day = new Date(t).toISOString().slice(0, 10)
  if (kind === 'touch') { state.lastSeen = t; d.visits.visitors[visitorId] = state; return { counted: false, reason: '站内切换：不计数，只刷新活跃时间', total: d.visits.total } }
  if (d.settings.failVisitWrite) fail(503, '访问计数暂不可用')
  let counted; let reason
  if (d.settings.excludeAdmin && user?.role === 'ADMIN') { counted = false; reason = '管理员访问不计入（按当前设置）' }
  else if (d.settings.rule === 'day') { counted = state.lastCountedDay !== day; reason = counted ? '今天首次进入网站' : '同一浏览器今天已计数' }
  else {
    const windowMs = (d.settings.rule === 'idle60' ? 60 : 30) * MINUTE
    const idle = state.lastSeen ? t - state.lastSeen : Infinity
    counted = idle > windowMs
    reason = counted ? (state.lastSeen ? `距上次活动 ${Math.round(idle / MINUTE)} 分钟，超过窗口，记为新访问` : '新浏览器首次进入网站') : `距上次活动 ${Math.round(idle / MINUTE)} 分钟，仍在同一次访问内（刷新/新标签页不重复计）`
  }
  state.lastSeen = t
  if (counted) { d.visits.total += 1; state.lastCountedDay = day }
  d.visits.visitors[visitorId] = state
  return { counted, reason, total: d.visits.total }
}

// —— 路由表 ——
const routes = []
const on = (method, pattern, handler) => routes.push({ method, pattern, handler })

on('GET', /^\/api\/auth\/csrf$/, () => { document.cookie = `XSRF-TOKEN=${encodeURIComponent(getDb().csrf)}; path=/`; return {} })
on('GET', /^\/api\/stats$/, async () => {
  const d = getDb()
  if (d.settings.slowStats) await new Promise((resolve) => setTimeout(resolve, 2200))
  const publicCollections = d.collections.filter((c) => c.status === 'PUBLISHED' && c.visibility === 'PUBLIC')
  return {
    creatorCount: new Set(d.posts.map((p) => p.authorId)).size,
    contentCount: d.posts.length,
    collectionCount: publicCollections.length, // 受限专题不进入公开统计，避免泄漏数量
    visits: d.settings.failVisitRead ? { available: false } : { available: true, total: d.visits.total, since: d.visits.since },
  }
})
on('POST', /^\/api\/site-visits$/, ({ body }) => recordVisit(body.kind === 'touch' ? 'touch' : 'entry'))

on('GET', /^\/api\/posts$/, ({ params }) => {
  const type = params.get('type'); const list = getDb().posts.filter((p) => !type || p.type === type).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  const result = page(list, params); return { ...result, items: result.items.map(postSummary) }
})
on('GET', /^\/api\/posts\/(\d+)$/, ({ match }) => { const post = getDb().posts.find((p) => p.id === Number(match[1])); if (!post) fail(404, '内容不存在'); return postView(post) })
on('POST', /^\/api\/posts$/, ({ body }) => {
  const user = requirePerm('post.create', '当前角色不能发布内容')
  const post = { id: nextId(), type: body.type, title: body.title, summary: body.summary, body: body.body, authorId: user.id, likes: 0, createdAt: new Date(now()).toISOString() }
  getDb().posts.unshift(post); return postView(post)
})
on('POST', /^\/api\/posts\/(\d+)\/like$/, ({ match }) => { requirePerm('post.interact', '当前角色不能点赞'); const post = getDb().posts.find((p) => p.id === Number(match[1])); if (!post) fail(404, '内容不存在'); post.likes += 1; return postView(post) })
on('GET', /^\/api\/posts\/(\d+)\/comments$/, ({ match, params }) => {
  const list = getDb().comments.filter((c) => c.postId === Number(match[1])).map((c) => ({ id: c.id, authorName: userById(c.authorId)?.displayName, body: c.body, createdAt: c.createdAt }))
  return page(list, params)
})
on('POST', /^\/api\/posts\/(\d+)\/comments$/, ({ match, body }) => {
  const user = requirePerm('post.interact', '当前角色不能参与讨论')
  const comment = { id: nextId(), postId: Number(match[1]), authorId: user.id, body: body.body, createdAt: new Date(now()).toISOString() }
  getDb().comments.push(comment); return { id: comment.id, authorName: user.displayName, body: comment.body, createdAt: comment.createdAt }
})

on('GET', /^\/api\/collections$/, ({ params }) => { const result = page(visibleCollections(currentUser()), params); return { ...result, items: result.items.map(collectionView) } })
on('GET', /^\/api\/collections\/(\d+)\/outline$/, ({ match }) => {
  const c = getDb().collections.find((x) => x.id === Number(match[1]) && x.status === 'PUBLISHED')
  if (!c) fail(404, '专题不存在或尚未公开')
  const user = currentUser()
  if (!canReadCollection(user, c)) fail(user ? 403 : 401, user ? `这是受限专题，当前角色（${roleLabel(user.role)}）无权阅读` : '这是受限专题，请先登录后再访问')
  return outline(c)
})
on('GET', /^\/api\/vps$/, () => getDb().vps)

on('GET', /^\/api\/me\/posts$/, ({ params }) => { const user = requireUser(); const result = page(getDb().posts.filter((p) => p.authorId === user.id), params); return { ...result, items: result.items.map(postSummary) } })
on('GET', /^\/api\/me\/collections$/, ({ params }) => {
  const user = requirePerm('collection.manage', '当前角色不能管理专题')
  const list = getDb().collections.filter((c) => c.ownerId === user.id).map((c) => ({ ...collectionView(c), name: c.draft?.name || c.name, format: c.format, status: c.status, revision: c.draft?.revision || 1, editorType: 'CLASSIC' }))
  return page(list, params)
})
on('PUT', /^\/api\/me\/collections\/(\d+)\/posts\/(\d+)$/, ({ match }) => {
  const user = requirePerm('post.interact', '当前角色不能收录内容')
  const c = getDb().collections.find((x) => x.id === Number(match[1]))
  if (!c || c.ownerId !== user.id) fail(404, '专题不存在') // 归属检查独立于角色
  if (!c.postIds.includes(Number(match[2]))) c.postIds.push(Number(match[2]))
  return collectionView(c)
})
// 撤回/下架期间自选记录保留但隐藏（c.status 非 PUBLISHED 时不展示）
const selectionView = (user) => {
  const ids = getDb().selections[user.id] || []
  const items = ids.map((id, i) => { const c = getDb().collections.find((x) => x.id === id); return c && c.status === 'PUBLISHED' && canReadCollection(user, c) ? { ...collectionView(c), sortOrder: i } : null }).filter(Boolean)
  return { version: ids.length, items }
}
on('GET', /^\/api\/me\/collections\/selected$/, () => selectionView(requirePerm('selection.manage')))
on('PUT', /^\/api\/me\/collections\/selected\/(\d+)$/, ({ match }) => {
  const user = requirePerm('selection.manage'); const id = Number(match[1])
  const c = getDb().collections.find((x) => x.id === id)
  if (!c || !canReadCollection(user, c)) fail(404, '专题不存在') // 受限专题对无权限者表现为不存在
  const list = getDb().selections[user.id] || (getDb().selections[user.id] = [])
  if (!list.includes(id)) list.push(id)
  return selectionView(user)
})
on('DELETE', /^\/api\/me\/collections\/selected\/(\d+)$/, ({ match }) => { const user = requirePerm('selection.manage'); getDb().selections[user.id] = (getDb().selections[user.id] || []).filter((id) => id !== Number(match[1])); return selectionView(user) })
on('PATCH', /^\/api\/me\/collections\/selected\/order$/, ({ body }) => { const user = requirePerm('selection.manage'); getDb().selections[user.id] = body.collectionIds; return selectionView(user) })
on('GET', /^\/api\/me\/ai-settings\/deepseek$/, () => { requirePerm('ai.use', '当前角色不能使用 AI 功能'); return { configured: false, lastFour: null, storageAvailable: true } })

// —— FINNS-11：管理接口（只对管理员开放） ——
on('GET', /^\/api\/admin\/users$/, async ({ params }) => {
  requirePerm('admin.users', '只有管理员可以查看用户列表')
  const settings = ensureUserAdmin()
  if (settings.slowList) await new Promise(resolve => setTimeout(resolve, 1400))
  if (settings.failLoadOnce) { settings.failLoadOnce = false; fail(503, '用户列表暂时无法加载，请重试。') }
  const q = (params.get('q') || '').trim().toLowerCase(); const role = params.get('role') || ''; const status = params.get('status') || ''
  const list = getDb().users.filter((u) => (!q || `${u.displayName} ${u.email}`.toLowerCase().includes(q)) && (!role || u.role === role) && (!status || u.accountStatus === status))
  const result = page(list, params, 10)
  return { ...result, items: result.items.map((u) => ({ ...adminUserView(u), canManage: permissionsOf(u.role).includes('admin.roles') })), managerCount: managerCount() }
})
on('GET', /^\/api\/admin\/ownership$/, () => ownershipOptions())
on('POST', /^\/api\/admin\/ownership$/, ({ body }) => transferOwnership(body))
on('PATCH', /^\/api\/admin\/users\/(\d+)\/status$/, ({ match, body }) => {
  requirePerm('admin.users')
  return adminUserView(changeAccountStatus(Number(match[1]), body))
})
on('PATCH', /^\/api\/admin\/users\/(\d+)\/role$/, ({ match, body }) => {
  const operator = requirePerm('admin.users', '只有管理员可以调整角色')
  const d = getDb(); const target = userById(Number(match[1]))
  if (!target) fail(404, '用户不存在')
  if (!findRole(body.role)) fail(400, '目标角色不存在，可能已被删除，请刷新后重试')
  const restriction = managementRestriction(operator, target, body.role)
  if (restriction) fail(403, restriction)
  if (body.expectedRole && body.expectedRole !== target.role) fail(409, `该用户角色已被修改为「${roleLabel(target.role)}」，请核对后重试`)
  if (managerCount(d.permissions, d.users.map((u) => u.id === target.id ? { ...u, role: body.role } : u)) < 1) fail(422, '这是最后一位能管理角色的用户，不能改为没有管理权限的角色；请先指定另一位管理员')
  if (d.settings.failRoleSaveOnce) { d.settings.failRoleSaveOnce = false; fail(500, '保存失败（模拟服务异常），请重试；已填写内容未丢失') }
  if (body.role === target.role) return adminUserView(target)
  d.audit.unshift({ id: nextId(), type: 'ROLE_ASSIGN', at: new Date(now()).toISOString(), operatorId: operator.id, targetId: target.id, before: target.role, after: body.role, beforeName: roleLabel(target.role), afterName: roleLabel(body.role), note: String(body.note || '').slice(0, 200) })
  target.role = body.role
  return adminUserView(target)
})
const rolesView = () => ({
  version: getDb().permissionsVersion,
  catalog: CATALOG,
  guest: permissionsOf('GUEST'),
  maxRoles: MAX_ROLES,
  roles: allRoles().map((role) => ({ ...role, assignable: role.key !== 'OWNER' && (isSiteOwner(currentUser()) || !protectedRole(role.key)), permissions: permissionsOf(role.key), userCount: getDb().users.filter((u) => u.role === role.key).length })),
})
on('GET', /^\/api\/admin\/roles$/, () => { requirePerm('admin.users', '只有管理员可以查看角色权限'); return rolesView() })
on('PUT', /^\/api\/admin\/roles$/, ({ body }) => {
  const operator = requirePerm('admin.roles', '当前角色不能修改角色权限')
  const d = getDb()
  if (body.version !== d.permissionsVersion) fail(409, '角色权限已被其他管理员修改，请查看最新设置后重新调整')
  const next = {}
  for (const role of roleKeys()) {
    const requested = new Set(Array.isArray(body.permissions?.[role]) ? body.permissions[role] : d.permissions[role])
    for (const perm of requested) if (!CATALOG.some((c) => c.key === perm)) fail(400, `未知权限：${perm}`)
    for (const c of CATALOG) {
      if (isLocked(role, c) && !requested.has(c.key)) fail(422, `「${roleLabel(role)}」的「${c.label}」不可关闭（防止锁死管理入口或影响基本登录能力）`)
    }
    next[role] = CATALOG.map((c) => c.key).filter((key) => requested.has(key))
  }
  if (!isSiteOwner(operator)) {
    for (const role of roleKeys()) {
      const changed = JSON.stringify(next[role]) !== JSON.stringify(d.permissions[role])
      if (changed && protectedRole(role)) fail(403, '只有站长可以调整管理员角色的权限。')
      if (next[role].some(p => p.startsWith('admin.') && !d.permissions[role].includes(p))) fail(403, '只有站长可以授予管理权限。')
    }
  }
  if (managerCount(next) < 1) fail(422, '保存后将没有任何用户能管理角色，请保留至少一位管理者')
  const changes = roleKeys().map((role) => ({ role, added: next[role].filter((k) => !d.permissions[role].includes(k)), removed: d.permissions[role].filter((k) => !next[role].includes(k)) })).filter((c) => c.added.length || c.removed.length)
  if (!changes.length) return rolesView()
  if (d.settings.failRoleSaveOnce) { d.settings.failRoleSaveOnce = false; fail(500, '保存失败（模拟服务异常），请重试；未保存的修改仍保留在页面上') }
  const at = new Date(now()).toISOString(); const note = String(body.note || '').slice(0, 200)
  for (const change of changes) d.audit.unshift({ id: nextId(), type: 'ROLE_PERMISSION', at, operatorId: operator.id, ...change, roleName: roleLabel(change.role), note })
  d.permissions = next; d.permissionsVersion += 1
  return rolesView()
})

// —— R3：角色新增 / 改名 / 删除 ——
function requireRoleAdmin(body) {
  const operator = requirePerm('admin.roles', '当前角色不能管理角色')
  if (body.version !== getDb().permissionsVersion) fail(409, '角色设置已被其他管理员修改，请刷新后重试')
  return operator
}
function cleanRoleText(body, exceptKey) {
  const label = String(body.label || '').trim(); const description = String(body.description || '').trim()
  if (!label) fail(400, '请填写角色名称')
  if ([...label].length > 20) fail(400, '角色名称最多 20 个字')
  if ([...description].length > 60) fail(400, '角色说明最多 60 个字')
  if (allRoles().some((r) => r.key !== exceptKey && r.label.toLowerCase() === label.toLowerCase())) fail(409, `已有名为「${label}」的角色`)
  return { label, description }
}
const defineAudit = (operator, entry, note) => getDb().audit.unshift({ id: nextId(), type: 'ROLE_DEFINE', at: new Date(now()).toISOString(), operatorId: operator.id, note: String(note || '').slice(0, 200), ...entry })
const simulatedFailure = (message) => { const d = getDb(); if (d.settings.failRoleSaveOnce) { d.settings.failRoleSaveOnce = false; fail(500, message) } }

on('POST', /^\/api\/admin\/roles\/definitions$/, ({ body }) => {
  const operator = requireRoleAdmin(body); const d = getDb()
  if (d.roles.length >= MAX_ROLES) fail(422, `角色最多 ${MAX_ROLES} 个，请先删除不用的角色`)
  const text = cleanRoleText(body)
  const source = body.copyFrom ? findRole(body.copyFrom) : null
  if (body.copyFrom && !source) fail(400, '要复制的角色不存在')
  if (source?.key === 'OWNER') fail(403, '站长角色不能复制。')
  if (source && protectedRole(source.key) && !isSiteOwner(operator)) fail(403, '只有站长可以创建具有管理权限的角色。')
  simulatedFailure('保存失败（模拟服务异常），请重试；已填写内容未丢失')
  const key = `R${nextId()}`
  const base = source ? permissionsOf(source.key) : []
  d.roles.splice(d.roles.length - 1, 0, { key, ...text }) // 新角色排在管理员之前
  d.permissions[key] = CATALOG.filter((c) => isLocked(key, c) || base.includes(c.key)).map((c) => c.key)
  d.permissionsVersion += 1
  defineAudit(operator, { action: 'CREATE', role: key, roleName: text.label, copyFrom: source?.key, copyFromName: source?.label || '' }, body.note)
  return { ...rolesView(), createdKey: key }
})
on('PATCH', /^\/api\/admin\/roles\/definitions\/(\w+)$/, ({ match, body }) => {
  const operator = requireRoleAdmin(body); const d = getDb(); const role = findRole(match[1])
  if (!role) fail(404, '角色不存在，可能已被删除')
  if (role.key === 'OWNER') fail(403, '站长身份及说明固定，不能在这里修改。')
  if (protectedRole(role.key) && !isSiteOwner(operator)) fail(403, '只有站长可以编辑管理员角色。')
  const text = cleanRoleText(body, role.key)
  if (text.label === role.label && text.description === (role.description || '')) return rolesView()
  simulatedFailure('保存失败（模拟服务异常），请重试；已填写内容未丢失')
  defineAudit(operator, { action: 'UPDATE', role: role.key, roleName: text.label, beforeName: role.label, afterName: text.label, descriptionChanged: text.description !== (role.description || '') }, body.note)
  Object.assign(role, text); d.permissionsVersion += 1
  return rolesView()
})
on('DELETE', /^\/api\/admin\/roles\/definitions\/(\w+)$/, ({ match, body }) => {
  const operator = requireRoleAdmin(body); const d = getDb(); const role = findRole(match[1])
  if (!role) fail(404, '角色不存在，可能已被删除')
  if (role.system === 'owner') fail(403, '站长角色不能删除。')
  if (protectedRole(role.key) && !isSiteOwner(operator)) fail(403, '只有站长可以处置具有管理权限的角色。')
  if (role.system === 'admin') fail(422, '管理员角色不能删除')
  if (role.system === 'default') fail(422, '注册默认角色不能删除，可以改名或调整权限')
  const members = d.users.filter((u) => u.role === role.key)
  const moveTo = members.length ? findRole(body.moveTo) : null
  if (members.length && (!moveTo || moveTo.key === role.key)) fail(400, `该角色下还有 ${members.length} 位用户，请选择他们改用的角色`)
  if (members.some(u => u.id === d.siteOwnerId) || moveTo?.key === 'OWNER') fail(403, '站长身份不能通过删除角色或迁移用户改变。')
  if (moveTo && protectedRole(moveTo.key) && !isSiteOwner(operator)) fail(403, '只有站长可以授予管理员身份。')
  if (members.length && managerCount(d.permissions, d.users.map((u) => u.role === role.key ? { ...u, role: moveTo.key } : u)) < 1) fail(422, '删除后将没有任何用户能管理角色')
  simulatedFailure('删除失败（模拟服务异常），角色和用户均未改变，请重试')
  for (const u of members) u.role = moveTo.key
  d.roles = d.roles.filter((r) => r.key !== role.key); delete d.permissions[role.key]; d.permissionsVersion += 1
  defineAudit(operator, { action: 'DELETE', role: role.key, roleName: role.label, moveTo: moveTo?.key, moveToName: moveTo?.label || '', movedCount: members.length, movedNames: members.slice(0, 10).map((u) => u.displayName) }, body.note)
  return rolesView()
})

on('GET', /^\/api\/admin\/audit$/, ({ params }) => {
  requirePerm('admin.audit', '只有管理员可以查看操作记录')
  const q = (params.get('q') || '').trim().toLowerCase(); const type = params.get('type') || ''; const role = params.get('role') || ''
  const from = params.get('from') ? new Date(`${params.get('from')}T00:00:00`).getTime() : -Infinity
  const to = params.get('to') ? new Date(`${params.get('to')}T23:59:59.999`).getTime() : Infinity
  if (from > to) fail(400, '开始日期不能晚于结束日期')
  const view = (a) => ({
    ...a,
    operatorName: userById(a.operatorId)?.displayName || '已注销用户',
    targetName: a.targetId ? userById(a.targetId)?.displayName || '已注销用户' : '',
    beforeLabel: a.beforeName || (a.before ? roleLabel(a.before) : ''), afterLabel: a.afterName || (a.after ? roleLabel(a.after) : ''), roleLabel: a.roleName || (a.role ? roleLabel(a.role) : ''),
    moveToLabel: a.moveToName || '', copyFromLabel: a.copyFromName || '',
    addedLabels: (a.added || []).map(permLabel), removedLabels: (a.removed || []).map(permLabel),
  })
  const list = getDb().audit.map(view).filter((a) => {
    const t = new Date(a.at).getTime()
    if (t < from || t > to) return false
    if (type && a.type !== type) return false
    if (role && ![a.before, a.after, a.role, a.moveTo, a.copyFrom].includes(role)) return false
    if (q && !`${a.operatorName} ${a.targetName} ${a.note} ${a.roleLabel} ${a.beforeLabel} ${a.afterLabel} ${a.moveToLabel} ${(a.movedNames || []).join(' ')} ${a.addedLabels.join(' ')} ${a.removedLabels.join(' ')}`.toLowerCase().includes(q)) return false
    return true
  })
  return page(list, params, 10)
})

// —— R4：专题工作稿与可见范围（真实编辑页所需接口的最小模拟） ——
// 可见范围随工作稿保存，发布/更新发布时写入公开版本；设为“受限”需要“阅读受限专题”权限。
function ownedCollection(id) {
  const user = requirePerm('collection.manage', '当前角色不能管理专题')
  const c = getDb().collections.find((x) => x.id === Number(id))
  if (!c || c.ownerId !== user.id) fail(404, '专题不存在') // 归属检查独立于角色
  if (!c.draft) {
    const o = outline(c)
    c.draft = { name: c.name, description: c.description, format: c.format, audience: c.audience || '', goal: c.goal || '', visibility: c.visibility, revision: 1, updatedAt: new Date(now()).toISOString(),
      sections: o.sections.map((s) => ({ ...s, entries: s.entries.map((e) => ({ ...e, title: e.title || '', body: e.body || '', externalUrl: e.externalUrl || '', annotation: e.annotation || '' })) })) }
  }
  return { user, c }
}
const draftView = (c) => ({ id: c.id, ...c.draft, status: c.status, ownerName: userById(c.ownerId)?.displayName, publishedAt: c.status === 'PUBLISHED' ? c.publishedAt || c.draft.updatedAt : null, publishedVisibility: c.status === 'PUBLISHED' ? c.visibility : null })
on('GET', /^\/api\/me\/collections\/(\d+)\/draft$/, ({ match }) => draftView(ownedCollection(match[1]).c))
on('PUT', /^\/api\/me\/collections\/(\d+)\/draft$/, ({ match, body }) => {
  const { user, c } = ownedCollection(match[1])
  if (body.revision !== c.draft.revision) fail(409, `专题版本已变化，请刷新后重试（当前版本 ${c.draft.revision}）`)
  const visibility = body.visibility === 'RESTRICTED' ? 'RESTRICTED' : 'PUBLIC'
  if (visibility === 'RESTRICTED' && c.draft.visibility !== 'RESTRICTED' && !can(user, 'content.restricted')) fail(403, '当前角色不能把专题设为受限')
  Object.assign(c.draft, { name: body.name, description: body.description, format: body.format, audience: body.audience, goal: body.goal, visibility, sections: body.sections, revision: c.draft.revision + 1, updatedAt: new Date(now()).toISOString() })
  return { revision: c.draft.revision, updatedAt: c.draft.updatedAt }
})
on('POST', /^\/api\/me\/collections\/(\d+)\/publish$/, ({ match, body }) => {
  const { user, c } = ownedCollection(match[1])
  if (body.revision !== c.draft.revision) fail(409, `专题版本已变化，请刷新后重试（当前版本 ${c.draft.revision}）`)
  if (c.draft.visibility === 'RESTRICTED' && c.visibility !== 'RESTRICTED' && !can(user, 'content.restricted')) fail(403, '当前角色不能发布受限专题')
  Object.assign(c, { name: c.draft.name, description: c.draft.description, format: c.draft.format, audience: c.draft.audience, goal: c.draft.goal, visibility: c.draft.visibility, status: 'PUBLISHED', publishedAt: new Date(now()).toISOString() })
  c.draft.revision += 1
  return draftView(c)
})
on('POST', /^\/api\/me\/collections\/(\d+)\/archive$/, ({ match }) => { const { c } = ownedCollection(match[1]); c.status = 'ARCHIVED'; c.draft.revision += 1; return draftView(c) })
on('GET', /^\/api\/me\/collections\/(\d+)\/preview$/, ({ match }) => {
  const { c } = ownedCollection(match[1])
  return { collection: { id: c.id, name: c.draft.name, description: c.draft.description, ownerName: userById(c.ownerId)?.displayName, format: c.draft.format, audience: c.draft.audience, goal: c.draft.goal }, sections: c.draft.sections }
})

// 不在 0.7.0 原型范围内的既有接口：如实返回“原型未模拟”，不假装成功。
const notSimulated = /^\/api\/(me\/(collections\/\d+\/builder-document|topic-builder)|private\/tools)/

const json = (status, data) => new Response(status === 204 ? null : JSON.stringify(data ?? {}), { status, headers: { 'Content-Type': 'application/json' } })
const emit = () => window.dispatchEvent(new CustomEvent('finns-proto-change'))

function log(entry) {
  const d = getDb()
  d.log.unshift({ at: new Date().toLocaleTimeString('zh-CN', { hour12: false }), ...entry })
  d.log.length = Math.min(d.log.length, 60)
}

async function handle(url, init) {
  const method = (init.method || 'GET').toUpperCase()
  const parsed = new URL(url, location.origin)
  const path = parsed.pathname
  reloadDb()
  let body = {}
  try { body = init.body ? JSON.parse(init.body) : {} } catch { body = {} }
  const d = getDb()
  let status = 200; let data; let note = ''
  try {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const header = new Headers(init.headers || {}).get('X-XSRF-TOKEN')
      if (d.settings.dropCsrfOnce) { d.settings.dropCsrfOnce = false; fail(403, 'CSRF 校验失败：请求缺少有效令牌（模拟）。页面输入已保留，请重试') }
      if (header !== d.csrf) fail(403, 'CSRF 校验失败')
    }
    const collection = handleCollection(method, path, body, parsed.searchParams)
    if (notSimulated.test(path) && !collection.handled) fail(501, '原型未模拟此功能（不在 0.7.0 浏览量/角色范围内）')
    const account = handleAccount(method, path, body, parsed.searchParams)
    const review = account.handled ? { handled: false } : handleReview(method, path, body, parsed.searchParams)
    const content = account.handled || review.handled || collection.handled ? { handled: false } : handleContent(method, path, body, parsed.searchParams)
    if (account.handled) data = account.data
    else if (collection.handled) data = collection.data
    else if (content.handled) data = content.data
    else if (review.handled) data = review.data && typeof review.data.then === 'function' ? await review.data : review.data
    else {
      const route = routes.find((r) => r.method === method && r.pattern.test(path))
      if (!route) fail(404, '接口不存在')
      const result = route.handler({ match: path.match(route.pattern), params: parsed.searchParams, body })
      // Synchronous mock mutations must commit before another request can reload storage.
      data = result && typeof result.then === 'function' ? await result : result
    }
    if (data === null) status = 204
    if (path === '/api/site-visits') note = data.reason
    if (data?._note) { note = data._note; delete data._note }
  } catch (error) {
    status = error.status || 500; data = { message: error.message }; note = error.message
  }
  const safeSearch = new URLSearchParams(parsed.search); if (safeSearch.has('token')) safeSearch.set('token', '[hidden]')
  log({ method, path: path + (safeSearch.size ? '?' + safeSearch : ''), status, note, role: roleLabel(currentUser()?.role) })
  saveDb(); emit()
  if ((/^\/api\/admin\/users\/\d+\/status$/.test(path) || path === '/api/admin/ownership' && method === 'POST') && status === 200) window.dispatchEvent(new Event('finns-account-session'))
  await new Promise((resolve) => setTimeout(resolve, 120)) // 让加载状态可见
  return json(status, data)
}

export function installMockServer() {
  ensureReview(); saveDb()
  // 与真实服务端一致：令牌 Cookie 始终对应当前会话的令牌（演示数据重置后也同步）。
  document.cookie = `XSRF-TOKEN=${encodeURIComponent(getDb().csrf)}; path=/`
  window.fetch = (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url
    if (new URL(url, location.origin).pathname.startsWith('/api/')) return handle(url, init)
    return realFetch(input, init)
  }
}

// 原型控制台使用的模拟操作（不是产品接口）。
export const proto = {
  db: () => { const d = reloadDb(); ensureAccount(d); return d },
  update(mutator) { reloadDb(); mutator(getDb()); saveDb(); emit() },
  visitorId: () => localStorage.getItem(VISITOR_KEY),
  clearVisitor() { localStorage.removeItem(VISITOR_KEY); emit() },
  advance(ms) { this.update((d) => { d.clockOffset += ms }) },
  MINUTE, DAY,
}
