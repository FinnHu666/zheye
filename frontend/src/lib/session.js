import { ref } from 'vue'
import { api } from './api.js'

// 当前登录用户（含 role 与 permissions）。服务端每次请求按最新角色检查；前端只用它决定显示什么。
export const currentUser = ref(null)

export const can = (permission) => {
  const user = currentUser.value
  if (!user) return permission === 'content.public'
  return Array.isArray(user.permissions) && user.permissions.includes(permission)
}

// 每次导航刷新一次，使角色或权限变化在下一次导航即反映到界面。
export async function refreshSession() {
  try { currentUser.value = await api('/api/auth/me') } catch { currentUser.value = null }
  return currentUser.value
}
