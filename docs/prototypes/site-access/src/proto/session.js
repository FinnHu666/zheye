// 当前会话身份的共享状态（0.7.0 设计草案：/api/auth/me 返回 role 与 permissions）。
// 前端只用它决定“展示什么”；是否允许始终由后端逐请求判断。
import { ref } from 'vue'

export const sessionUser = ref(null)
export const can = (perm) => {
  const user = sessionUser.value
  if (!user) return perm === 'content.public'
  return Array.isArray(user.permissions) && user.permissions.includes(perm)
}

// FINNS-10：首页访问量的最新值（来自访问上报的响应）。
export const siteVisit = ref({ total: null, counted: null, reason: '' })
