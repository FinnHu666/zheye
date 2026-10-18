import test from 'node:test'
import assert from 'node:assert/strict'
import { api } from '../src/lib/api.js'
import { currentUser } from '../src/lib/session.js'
import { useSessionGuard } from '../src/lib/sessionGuard.js'
import { emailError, passwordError } from '../src/lib/accountValidation.js'

globalThis.document = { cookie: 'XSRF-TOKEN=test-token' }
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

test('logging in as the original account releases the page and the account check blocks a swapped session', async () => {
  currentUser.value = { id: 7, email: 'owner@finns.test' }
  const guard = useSessionGuard()
  guard.handle(Object.assign(new Error('x'), { status: 401 }))
  respond({ status: 200, body: { id: 7, email: 'owner@finns.test' } })
  guard.password.value = 'right-password'
  assert.equal(await guard.reLogin(), true)
  assert.equal(guard.expired.value, false)
  assert.equal(guard.password.value, '')
  respond({ status: 200, body: { id: 8 } })
  assert.equal(await guard.sameAccount(), false)
  respond({ status: 401, body: { message: '登录已失效' } })
  assert.equal(await guard.sameAccount(), false)
  assert.equal(guard.expired.value, true)
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
