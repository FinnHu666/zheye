import test from 'node:test'
import assert from 'node:assert/strict'
import { api, UNAUTHORIZED_EVENT } from '../src/lib/api.js'
import { currentUser } from '../src/lib/session.js'
import { useSessionGuard } from '../src/lib/sessionGuard.js'
import { emailError, passwordError } from '../src/lib/accountValidation.js'

globalThis.document = { cookie: 'XSRF-TOKEN=test-token' }
globalThis.window = new EventTarget() // api() 通过 window 广播 401
let calls
const respond = (...queue) => {
  calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, options })
    const next = queue.shift()
    if (!next) throw new Error('unexpected request ' + url)
    return { ok: next.status < 400, status: next.status, json: async () => next.body ?? {} }
  }
}

test('api errors keep the HTTP status so pages can tell an expired login from other failures', async () => {
  respond({ status: 401, body: { status: 401, message: '登录已失效' } }, { status: 400, body: { message: '请检查输入' } })
  await assert.rejects(api('/api/me/profile'), (e) => e.status === 401 && e.message === '登录已失效')
  await assert.rejects(api('/api/account/password', { method: 'POST', body: '{}' }), (e) => e.status === 400 && e.message === '请检查输入')
  assert.equal(calls[1].options.headers['X-XSRF-TOKEN'], 'test-token')
})

test('an expired login keeps the page and only accepts the original account again', async () => {
  currentUser.value = { id: 7, email: 'owner@finns.test' }
  const guard = useSessionGuard()
  assert.equal(guard.handle(Object.assign(new Error('x'), { status: 500 })), false)
  assert.equal(guard.expired.value, false)
  assert.equal(guard.handle(Object.assign(new Error('登录已失效'), { status: 401 })), true)
  assert.equal(guard.expired.value, true)

  respond({ status: 401, body: { message: '邮箱或密码不正确' } })
  guard.password.value = 'wrong-password'
  assert.equal(await guard.reLogin(), false)
  assert.equal(guard.expired.value, true)
  assert.equal(guard.error.value, '邮箱或密码不正确')
  assert.equal(guard.password.value, 'wrong-password', 'a failed attempt keeps the typed password in memory only so it can be corrected; it is never stored')
})

test('logging in as someone else is refused and never counts as recovering the original session', async () => {
  currentUser.value = { id: 7, email: 'owner@finns.test' }
  const guard = useSessionGuard()
  guard.handle(Object.assign(new Error('x'), { status: 401 }))
  respond({ status: 200, body: { id: 99, email: 'other@finns.test' } })
  guard.password.value = 'another-password'
  assert.equal(await guard.reLogin(), false)
  assert.equal(guard.mismatch.value, true)
  assert.equal(guard.expired.value, true)
  assert.equal(guard.password.value, '')
  assert.equal(currentUser.value.id, 99, 'the browser really is signed in as the other account now')
})

test('logging in as the original account releases the page', async () => {
  currentUser.value = { id: 7, email: 'owner@finns.test' }
  const guard = useSessionGuard()
  guard.handle(Object.assign(new Error('x'), { status: 401 }))
  respond({ status: 200, body: { id: 7, email: 'owner@finns.test' } })
  guard.password.value = 'right-password'
  assert.equal(await guard.reLogin(), true)
  assert.equal(guard.expired.value, false)
  assert.equal(guard.password.value, '')
})

test('beforeSubmit blocks a session that expired or was swapped to another account, but not a network error', async () => {
  currentUser.value = { id: 7, email: 'owner@finns.test' }
  const guard = useSessionGuard()
  respond({ status: 200, body: { id: 7 } })
  assert.equal(await guard.beforeSubmit(), true)
  assert.equal(guard.expired.value, false)

  respond({ status: 200, body: { id: 8 } }) // 在别处换了账号
  assert.equal(await guard.beforeSubmit(), false)
  assert.equal(guard.expired.value, true)
  assert.equal(guard.mismatch.value, true)
  assert.match(guard.error.value, /另一个账号/)
  assert.equal(await guard.beforeSubmit(), false, 'stays blocked until the original account logs in again')

  const second = useSessionGuard()
  respond({ status: 401, body: { message: '登录已失效' } })
  assert.equal(await second.beforeSubmit(), false)
  assert.equal(second.expired.value, true)

  const third = useSessionGuard()
  globalThis.fetch = async () => { throw new Error('network down') }
  assert.equal(await third.beforeSubmit(), true, 'a network failure is reported by the real submit, not swallowed here')
  assert.equal(third.expired.value, false)
})

test('a 401 from a protected request is broadcast, but login and identity calls are not', async () => {
  let fired = 0
  const listener = () => { fired++ }
  window.addEventListener(UNAUTHORIZED_EVENT, listener)
  respond({ status: 401, body: {} }, { status: 401, body: {} }, { status: 401, body: {} }, { status: 400, body: {} })
  await assert.rejects(api('/api/me/profile'))
  assert.equal(fired, 1)
  await assert.rejects(api('/api/auth/login', { method: 'POST', body: '{}' }))
  await assert.rejects(api('/api/auth/me'))
  assert.equal(fired, 1, 'wrong password or an anonymous identity check is not an expired login')
  await assert.rejects(api('/api/me/posts'))
  assert.equal(fired, 1, 'only 401 counts')
  window.removeEventListener(UNAUTHORIZED_EVENT, listener)
})

test('the broadcast expires the guard only for a page that was opened while signed in', () => {
  currentUser.value = { id: 7, email: 'owner@finns.test' }
  const signedIn = useSessionGuard()
  signedIn.onUnauthorized()
  assert.equal(signedIn.expired.value, true)
  currentUser.value = null
  const guest = useSessionGuard()
  guest.onUnauthorized()
  assert.equal(guest.expired.value, false, 'a guest hitting a login-only action is not an expired session')
})

test('password and email checks match the server policy', () => {
  assert.equal(emailError(''), '请填写邮箱地址。')
  assert.match(emailError('abc'), /邮箱格式还不完整/)
  assert.equal(emailError('a@b.co'), '')
  const ok = 'a'.repeat(8)
  assert.equal(passwordError(ok, ok), '')
  assert.equal(passwordError('a'.repeat(20), 'a'.repeat(20)), '')
  assert.match(passwordError('a'.repeat(7), 'a'.repeat(7)), /至少需要8位/)
  assert.match(passwordError('a'.repeat(21), 'a'.repeat(21)), /最多20位/)
  assert.match(passwordError(ok, ok + 'x'), /不一致/)
  assert.equal(passwordError('', ''), '请设置登录密码。')
})
