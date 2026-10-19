// FINNS-15 product simulation only. No real password, mail, session or security service.
import { getDb, now, nextId, MINUTE, DAY } from './db'
import { ensureUserAdmin, currentPrototypeUser, simulateSignIn } from './user-admin'

export const RECOVERY_MESSAGE = '如果该邮箱可以接收账号邮件，我们会发送操作链接，请检查收件箱。'
const error = (status, message) => { throw Object.assign(new Error(message), { status }) }
const emitSession = () => queueMicrotask(() => window.dispatchEvent(new Event('finns-account-session')))
export function ensureAccount(d = getDb()) {
  ensureUserAdmin(d)
  d.account ||= { links: [], mail: [], sent: {}, devices: {}, failSend: false, failSave: false, wrongOld: false, failNotice: false }
  for (const u of d.users) {
    if (u.legacy === undefined) u.legacy = true
    if (u.emailVerified === undefined) u.emailVerified = u.id !== 3 // 阿远演示旧未验证账号
    d.account.devices[u.id] ||= [{ name: '当前浏览器', current: true, active: true }, { name: '手机浏览器', current: false, active: true }, { name: '另一台电脑', current: false, active: true }]
  }
  return d.account
}
export function accountView(u) {
  if (!u) return null
  ensureAccount()
  const d = getDb(), role = d.roles.find(r => r.key === u.role)
  return { id: u.id, email: u.email, displayName: u.displayName, role: u.role, roleLabel: role?.label, permissions: [...(d.permissions[u.role] || [])], emailVerified: u.emailVerified, legacy: u.legacy }
}
function user() {
  const d = getDb(), u = currentPrototypeUser()
  if (!u) error(401, '登录已失效，请重新登录。未提交的内容仍留在当前页面。')
  if (!u.legacy && !u.emailVerified) error(403, '请先完成邮箱验证。')
  return u
}
function credential(body) {
  if (typeof body.password !== 'string' || body.password.length < 8 || body.password.length > 20) error(400, '请设置8至20位密码。')
  if (body.password !== body.confirmPassword) error(400, '两次输入的新密码不一致。')
}
const normalizedEmail = value => String(value || '').trim().toLowerCase()
function checkEmail(value) {
  const email = normalizedEmail(value)
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) error(400, '请输入完整邮箱，例如 name@example.com。')
  return email
}
function sendTo(email, purpose, u) {
  const a = ensureAccount(), key = `${email}:${purpose}`, t = now()
  const response = { message: RECOVERY_MESSAGE, cooldown: 60 }
  if ((a.sent[key] && t - a.sent[key] < MINUTE) || a.failSend) return response
  a.links.filter(l => (l.email || getDb().users.find(user => user.id === l.userId)?.email) === email && l.purpose === purpose && !l.used).forEach(l => { l.revoked = true })
  const link = { id: `demo-${nextId()}`, userId: u?.id, email, purpose, expires: t + (purpose === 'reset' ? 30 * MINUTE : DAY), used: false, revoked: false, verified: false }
  a.links.unshift(link); a.sent[key] = t
  a.mail.unshift({ id: nextId(), email, title: purpose === 'signup' ? '验证邮箱，继续创建 FINNS 账号' : purpose === 'verify' ? '验证你的 FINNS 邮箱' : (!u.emailVerified ? '验证邮箱并重置密码' : '重置 FINNS 密码'), body: purpose === 'reset' ? '请通过链接设置新密码，30分钟内有效。' : '请验证邮箱，链接24小时内有效。', link: `/auth/${purpose === 'reset' ? 'reset' : 'verify'}?purpose=${purpose}&token=${link.id}`, at: t })
  return response
}
const send = (u, purpose) => sendTo(u.email, purpose, u)
function validLink(id, purpose) {
  const a = ensureAccount(), l = a.links.find(l => l.id === id && l.purpose === purpose)
  if (!l || l.revoked) error(410, '链接无效或已被新链接替代，请重新申请。')
  if (l.used) error(410, '这条链接已经使用，请重新申请。')
  if (now() >= l.expires) error(410, '链接已过期，请重新申请。')
  const u = getDb().users.find(u => u.id === l.userId)
  if (purpose !== 'signup' && !u) error(410, '链接无效，请重新申请。')
  return { l, u }
}
const profileView = u => {
  const d = getDb()
  return { id: u.id, email: u.email, displayName: u.displayName, bio: u.bio || '', emailVerified: u.emailVerified, createdAt: u.createdAt, postCount: d.posts.filter(p => p.authorId === u.id).length, collectionCount: d.collections.filter(c => c.ownerId === u.id).length, selectedCollectionCount: (d.selections[u.id] || []).length }
}
function updatePassword(u, isReset) {
  const d = getDb(), a = ensureAccount()
  if (a.failSave) { a.failSave = false; error(503, '暂时无法保存，请重试。原密码未改变。') }
  a.links.filter(l => l.userId === u.id && l.purpose === 'reset').forEach(l => { l.revoked = true })
  a.devices[u.id].forEach(device => { if (isReset || !device.current) device.active = false })
  u.sessionEpoch += 1
  if (d.session.userId === u.id) { if (isReset) d.session.userId = null; else d.session.epoch = u.sessionEpoch }
  u.passwordChangedAt = now() // 只存模拟事件，不保存/散列/传出输入密码
  if (!a.failNotice) a.mail.unshift({ id: nextId(), email: u.email, title: '你的 FINNS 密码已更新', body: '如果这不是你的操作，请立即通过找回密码恢复账号。本邮件不含密码或恢复凭据。', at: now() })
  emitSession()
  return { message: isReset ? '密码已重置，所有设备已退出，请重新登录。' : '密码已更新，当前设备保持登录，其他设备已退出。', noticeFailed: a.failNotice }
}

export function handleAccount(method, path, body, params) {
  const d = getDb(), a = ensureAccount()
  let data
  if (method === 'GET' && path === '/api/auth/me') data = accountView(user())
  else if (method === 'POST' && path === '/api/auth/login') {
    const u = d.users.find(u => u.email.toLowerCase() === String(body.email || '').trim().toLowerCase())
    if (!u || !body.password || a.wrongLogin) error(401, '邮箱或密码不正确。')
    if (u.accountStatus === 'DISABLED') error(403, '账号已停用，请联系站点管理员')
    if (!u.legacy && !u.emailVerified) error(403, '请先验证邮箱，再返回登录。')
    simulateSignIn(u.id)
    a.devices[u.id].find(device => device.current).active = true
    data = accountView(u); emitSession()
  } else if (method === 'POST' && path === '/api/auth/signup-link') {
    const email = checkEmail(body.email)
    data = { ...(d.users.some(u => u.email === email) ? { message: RECOVERY_MESSAGE, cooldown: 60 } : sendTo(email, 'signup')), email }
  } else if (method === 'POST' && path === '/api/auth/signup-verify') {
    const { l } = validLink(body.token, 'signup'); l.verified = true
    data = { email: l.email, token: l.id }
  } else if (method === 'POST' && path === '/api/auth/register') {
    const { l } = validLink(body.token, 'signup')
    if (!l.verified) error(403, '请先验证邮箱，再设置账号。')
    credential(body)
    if (!body.displayName?.trim() || body.displayName.trim().length > 40) error(400, '请填写40字以内的昵称。')
    if (d.users.some(u => u.email === l.email)) error(409, '注册无法完成，请尝试登录或找回密码。')
    const u = { id: nextId(), email: l.email, displayName: body.displayName.trim(), role: 'MEMBER', bio: '', createdAt: new Date(now()).toISOString(), legacy: false, emailVerified: true }
    d.users.push(u); l.used = true; ensureAccount(); d.session.userId = null
    data = { email: u.email, message: '账号创建成功，请使用邮箱和密码登录。' }; emitSession()
  } else if (method === 'POST' && path === '/api/auth/logout') { d.session.userId = null; data = null; emitSession() }
  else if (method === 'POST' && path === '/api/auth/request-link') {
    const purpose = ['verify', 'signup'].includes(body.purpose) ? body.purpose : 'reset'
    const email = checkEmail(body.email), u = d.users.find(u => u.email.toLowerCase() === email)
    data = purpose === 'signup' ? (u ? { message: RECOVERY_MESSAGE, cooldown: 60 } : sendTo(email, 'signup')) : u ? send(u, purpose) : { message: RECOVERY_MESSAGE, cooldown: 60 }
  } else if (method === 'GET' && path === '/api/auth/link') {
    const { u, l } = validLink(params.get('token'), params.get('purpose'))
    data = { email: l.email || u.email, expires: l.expires, needsVerification: u ? !u.emailVerified : !l.verified, verified: l.verified, purpose: l.purpose }
  } else if (method === 'POST' && path === '/api/auth/verify') {
    const { l, u } = validLink(body.token, 'verify'); l.used = true; u.emailVerified = true
    data = { message: '邮箱已验证。', legacy: u.legacy }; emitSession()
  } else if (method === 'POST' && path === '/api/auth/reset') {
    const { l, u } = validLink(body.token, 'reset'); credential(body)
    data = updatePassword(u, true); u.emailVerified = true; l.used = true
  } else if (method === 'POST' && path === '/api/account/password-reset-link') {
    const u = user(); data = send(u, 'reset') // 仅当前模拟会话账号，不接受客户端指定邮箱
  } else if (method === 'GET' && path === '/api/account/security') {
    const u = user(); data = { ...accountView(u), devices: a.devices[u.id], resetCooldown: Math.max(0, Math.ceil(((a.sent[`${u.email}:reset`] || 0) + MINUTE - now()) / 1000)) }
  } else if (method === 'POST' && path === '/api/account/password') {
    const u = user(); credential(body)
    if (!body.oldPassword || a.wrongOld) error(400, '原密码不正确，密码未改变。')
    data = updatePassword(u, false)
  } else if (method === 'GET' && path === '/api/me/profile') data = profileView(user())
  else if (method === 'PATCH' && path === '/api/me/profile') {
    const u = user()
    if (body.expectedUserId !== u.id) error(403, '账号已切换，请使用原账号重新登录后保存。')
    if (a.failProfile) { a.failProfile = false; error(503, '暂时无法保存，已保留你的修改，请重试。') }
    if (!body.displayName?.trim() || body.displayName.trim().length > 40 || String(body.bio || '').length > 500) error(400, '请检查昵称和简介的长度。')
    u.displayName = body.displayName.trim(); u.bio = String(body.bio || '')
    data = profileView(u)
  } else return { handled: false }
  return { handled: true, data }
}
