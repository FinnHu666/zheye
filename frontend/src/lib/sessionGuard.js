import { getCurrentInstance, ref, onBeforeUnmount } from 'vue'
import { api } from './api.js'
import { currentUser } from './session.js'

// 登录失效时保护页面内未提交的内容（ACCOUNT-P04）：
// 受保护请求返回 401 后不跳转、不清空页面，而是在原页重新登录；登录账号必须与原所有者一致才允许继续提交。
// 内容和密码输入只存在于组件内存，不写入 localStorage 或任何持久草稿。
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

  // 提交前再核对一次当前账号，避免在其他标签页换号后误提交。
  const sameAccount = async () => {
    try { const user = await api('/api/auth/me'); return owner === null || user.id === owner }
    catch (e) { handle(e); return false }
  }

  if (getCurrentInstance()) onBeforeUnmount(() => { password.value = '' })
  return { owner, email, password, expired, mismatch, busy, error, handle, reLogin, sameAccount }
}
