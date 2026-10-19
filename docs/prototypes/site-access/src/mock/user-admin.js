// FINNS-16 R3: browser-only behavior simulation. No production API or security guarantee.
import { getDb, now, nextId } from './db'
import { CATALOG, ROLES } from './roles'

export const statusLabel = value => value === 'DISABLED' ? '停用' : '正常'
export function ensureUserAdmin(d = getDb()) {
  d.userAdmin ||= { failSaveOnce: false, failLoadOnce: false, slowList: false, conflictOnce: false }
  if (!d.userAdmin.ownerModelVersion) {
    if (!d.roles.some(r => r.key === 'OWNER')) d.roles.unshift({ ...ROLES.find(r => r.key === 'OWNER') })
    d.permissions.OWNER = CATALOG.map(p => p.key)
    d.siteOwnerId = 1
    d.users.find(u => u.id === d.siteOwnerId).role = 'OWNER'
    d.userAdmin.ownerModelVersion = 1
  }
  d.userAdmin.ownerVersion ||= 1
  const originalDemoUser = d.users.find(u => u.id === 1)
  if (originalDemoUser?.displayName === '你（站长）') originalDemoUser.displayName = '你'
  const ownerRole = d.roles.find(r => r.key === 'OWNER')
  if (ownerRole) ownerRole.description = ROLES.find(r => r.key === 'OWNER').description
  for (const u of d.users) { u.accountStatus ||= 'ACTIVE'; u.statusVersion ||= 1; u.sessionEpoch ||= 0 }
  return d.userAdmin
}
export const isSiteOwner = user => !!user && user.id === getDb().siteOwnerId && user.role === 'OWNER'
export const protectedRole = role => role === 'OWNER' || role === 'ADMIN' || (getDb().permissions[role] || []).includes('admin.roles')
export function managementRestriction(operator, target, nextRole) {
  if (target?.id === getDb().siteOwnerId) return '站长不能停用或直接调整角色，请由当前站长使用专门的转交入口。'
  if (target && protectedRole(target.role) && !isSiteOwner(operator)) return '管理员不能管理其他管理员，请由站长处理。'
  if (nextRole === 'OWNER') return '站长为唯一身份，请使用专门的转交入口。'
  if (nextRole && protectedRole(nextRole) && !isSiteOwner(operator)) return '只有站长可以授予管理员身份或管理角色权限。'
  return ''
}
export const normalManagerCount = (permissions = getDb().permissions, users = getDb().users) => users.filter(u => u.accountStatus !== 'DISABLED' && (permissions[u.role] || []).includes('admin.roles')).length
export function currentPrototypeUser() {
  const d = getDb(); ensureUserAdmin(d)
  const u = d.users.find(u => u.id === d.session.userId)
  return u && u.accountStatus !== 'DISABLED' && (d.session.epoch ?? 0) === u.sessionEpoch ? u : null
}
export function simulateSignIn(id) {
  const d = getDb(); ensureUserAdmin(d)
  const u = d.users.find(u => u.id === id)
  d.session = { userId: u?.id || null, epoch: u?.sessionEpoch || 0 }
}
export function ownershipOptions() {
  const d = getDb(), settings = ensureUserAdmin(d), operator = currentPrototypeUser()
  if (!isSiteOwner(operator)) throw Object.assign(new Error('只有当前站长可以转交站长身份。'), { status: operator ? 403 : 401 })
  const view = u => ({ id: u.id, displayName: u.displayName, email: u.email })
  return { owner: view(operator), version: settings.ownerVersion, candidates: d.users.filter(u => u.id !== operator.id && u.role === 'ADMIN' && u.accountStatus === 'ACTIVE').map(view) }
}
export function transferOwnership(body) {
  const d = getDb(), settings = ensureUserAdmin(d), operator = currentPrototypeUser()
  const reject = (status, message) => { throw Object.assign(new Error(message), { status }) }
  if (!operator) reject(401, '登录已失效，请重新登录。')
  const target = d.users.find(u => u.id === Number(body.targetId)), note = String(body.note || '').trim()
  const audit = (outcome, resultMessage) => d.audit.unshift({ id: nextId(), type: 'OWNER_TRANSFER', operatorId: operator.id, targetId: target?.id, ownerBeforeName: operator.displayName, ownerAfterName: target?.displayName || '无效接任人', before: 'OWNER', after: outcome === 'SUCCESS' ? 'ADMIN' : 'OWNER', beforeName: '站长', afterName: outcome === 'SUCCESS' ? '管理员' : '站长', role: 'OWNER', note, outcome, resultMessage, at: new Date(now()).toISOString() })
  try {
    if (!isSiteOwner(operator)) reject(403, '只有当前站长可以转交站长身份。')
    if (body.expectedOwnerId !== d.siteOwnerId || body.expectedVersion !== settings.ownerVersion) reject(409, '站长信息已变化，请重新核对接任人后确认。')
    if (!target || target.id === operator.id || target.role !== 'ADMIN' || target.accountStatus !== 'ACTIVE') reject(422, '接任人必须是另一位正常管理员，请重新选择。')
    if (!note || [...note].length > 200) reject(400, '请填写1至200字的转交原因。')
    if (body.confirmEmail !== target.email) reject(400, '请输入接任人的完整邮箱以确认转交。')
    if (settings.conflictOnce) { settings.conflictOnce = false; settings.ownerVersion += 1; reject(409, '站长信息已变化，请重新核对接任人后确认。') }
    if (settings.failSaveOnce) { settings.failSaveOnce = false; reject(503, '转交未完成，双方身份均未改变。填写内容已保留，请重试。') }
    // All validation precedes the synchronous mock commit. Real transactions are a later design task.
    operator.role = 'ADMIN'; target.role = 'OWNER'; d.siteOwnerId = target.id; settings.ownerVersion += 1
    for (const u of [operator, target]) {
      u.sessionEpoch += 1
      d.account?.devices[u.id]?.forEach(device => { device.active = u.id === operator.id && device.current })
    }
    // Keep this confirmed session as ADMIN; other old sessions require sign-in again.
    d.session.epoch = operator.sessionEpoch
    audit('SUCCESS', '唯一站长已转交；原站长成为管理员，双方其他旧会话失效，接任人需重新登录。')
    return { owner: { id: target.id, displayName: target.displayName, email: target.email }, formerOwnerRole: 'ADMIN' }
  } catch (e) { audit(e.status === 503 ? 'FAILED' : 'REJECTED', e.message); throw e }
}
export function changeAccountStatus(id, body) {
  const d = getDb(), settings = ensureUserAdmin(d), operator = currentPrototypeUser()
  const reject = (status, message) => { throw Object.assign(new Error(message), { status }) }
  if (!operator) reject(401, '登录已失效，请重新登录。')
  if (!(d.permissions[operator.role] || []).includes('admin.users')) reject(403, '当前角色没有用户管理权限。')
  const target = d.users.find(u => u.id === id)
  if (!target) reject(404, '用户不存在。')
  const note = String(body.note || '').trim()
  const before = target.accountStatus
  const audit = (outcome, resultMessage) => d.audit.unshift({ id: nextId(), type: 'ACCOUNT_STATUS', operatorId: operator.id, targetId: id, role: target.role, roleName: d.roles.find(r => r.key === target.role)?.label, before, after: outcome === 'SUCCESS' ? body.status : before, beforeName: statusLabel(before), afterName: statusLabel(outcome === 'SUCCESS' ? body.status : before), note, outcome, resultMessage, at: new Date(now()).toISOString() })
  try {
    if (!['ACTIVE', 'DISABLED'].includes(body.status)) reject(400, '请选择有效账号状态。')
    if (!note || [...note].length > 200) reject(400, '请填写1至200字的操作原因。')
    if (id === operator.id && body.status === 'DISABLED') reject(422, '不能停用自己的账号，请由另一位有权限的管理者操作。')
    const restriction = managementRestriction(operator, target)
    if (restriction) reject(403, restriction)
    if (target.accountStatus === body.status) return { ...target, unchanged: true }
    if (settings.conflictOnce) { settings.conflictOnce = false; target.statusVersion += 1; reject(409, '该账号状态已被其他管理者更新，请核对最新状态后再次确认。') }
    if (body.expectedVersion !== target.statusVersion) reject(409, '该账号状态已被其他管理者更新，请核对最新状态后再次确认。')
    const nextUsers = d.users.map(u => u.id === id ? { ...u, accountStatus: body.status } : u)
    if (normalManagerCount(d.permissions, nextUsers) < 1) reject(422, '操作后将没有正常管理者，请先安排另一位正常且能管理角色的用户。')
    if (settings.failSaveOnce) { settings.failSaveOnce = false; reject(503, '暂时无法保存，账号状态未改变。原因已保留，请重试。') }
    target.accountStatus = body.status; target.statusVersion += 1
    if (body.status === 'DISABLED') {
      target.sessionEpoch += 1
      d.account?.devices[id]?.forEach(device => { device.active = false })
    }
    audit('SUCCESS', body.status === 'DISABLED' ? '已停用；旧会话失效，作品保持原可见范围' : '已恢复；须重新登录，旧会话不恢复')
    return { ...target, unchanged: false }
  } catch (e) { audit(e.status === 503 ? 'FAILED' : 'REJECTED', e.message); throw e }
}
