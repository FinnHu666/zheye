import { getCurrentInstance, ref, onMounted, onBeforeUnmount } from 'vue'
import { api, UNAUTHORIZED_EVENT } from './api.js'
import { currentUser } from './session.js'

// 登录失效时保护页面内未提交的内容（ACCOUNT-P04）：
// 受保护请求返回 401 后不跳转、不清空页面，而是在原页重新登录；登录账号必须与原所有者一致才允许继续提交。
// 提交按钮在 expired 期间应禁用；提交前用 beforeSubmit() 再核对一次当前账号，防止在别处换号后误提交。
// 内容和密码输入只存在于组件内存，不写入 localStorage 或任何持久草稿。
//
// 在组件 setup 中使用时，会自动监听 api() 广播的 401（页面里不用逐个 catch）；在 setup 之外创建则只能手动调用 handle()。
export function useSessionGuard() {
  const owner = currentUser.value?.id ?? null
  const email = ref(currentUser.value?.email ?? '')
  const expired = ref(false)
  const mismatch = ref(false)
  const busy = ref(false)
  const error = ref('')
  const password = ref('')

  // 在 catch 中调用：是登录失效则接管并返回 true。
  const handle = (e) => {
    if (e && e.status === 401) { expired.value = true; return true }
    return false
  }

  // api() 广播的 401：只有进入页面时已登录的用户才算“登录失效”。
  const onUnauthorized = () => { if (owner !== null) expired.value = true }

  const reLogin = async () => {
    if (busy.value) return false
    busy.value = true; error.value = ''
    try {
      const user = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email: email.value, password: password.value }) })
      password.value = ''
      currentUser.value = user
      if (owner !== null && user.id !== owner) {
        mismatch.value = true
        error.value = '当前登录的是另一个账号，不能提交原账号的内容。请改用原账号登录，或放弃这些内容。'
        return false
      }
      mismatch.value = false; expired.value = false
      return true
    } catch (e) { error.value = e.message; return false }
    finally { busy.value = false }
  }

  // 提交前核对：会话仍有效且仍是原账号才返回 true；否则让页面进入“需要重新登录/换号被拒”状态。
  const beforeSubmit = async () => {
    if (expired.value) return false
    try {
      const user = await api('/api/auth/me')
      if (owner === null || user.id === owner) return true
      mismatch.value = true; expired.value = true
      error.value = '当前登录的是另一个账号，不能提交原账号的内容。请改用原账号登录，或放弃这些内容。'
      return false
    } catch (e) { return !handle(e) } // 401 → 需要重新登录；其他错误（如网络）让真正的提交去报告，不在这里拦截
  }

  if (getCurrentInstance()) {
    onMounted(() => window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized))
    onBeforeUnmount(() => { window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized); password.value = '' })
  }
  return { owner, email, password, expired, mismatch, busy, error, handle, onUnauthorized, reLogin, beforeSubmit }
}
