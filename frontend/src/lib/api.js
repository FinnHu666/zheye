export const UNAUTHORIZED_EVENT = 'finns-unauthorized'

export async function api(url, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  const unsafe = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
  if (unsafe) {
    let token = csrfCookie()
    if (!token) {
      await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
      token = csrfCookie()
    }
    options = { ...options, headers: { ...(options.headers || {}), ...(token ? { 'X-XSRF-TOKEN': token } : {}) } }
  }
  const response = await fetch(url, {
    ...options,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  })
  if (response.status === 204) return null
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(body.message || '请求失败，请稍后再试')
    error.status = response.status // 页面据此区分 401（登录失效，需保护未提交内容）与其他错误
    // 受保护请求遇到 401：广播给正在使用会话保护的页面（登录/身份接口自身的 401 不算“登录失效”）
    if (response.status === 401 && !String(url).startsWith('/api/auth/') && typeof window !== 'undefined' && typeof window.dispatchEvent === 'function')
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    throw error
  }
  return body
}

function csrfCookie() {
  const row = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='))
  return row ? decodeURIComponent(row.substring('XSRF-TOKEN='.length)) : ''
}

export const dateLabel = (value) => new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(value))
