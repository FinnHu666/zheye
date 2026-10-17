import { ref } from 'vue'

// 最近一次进入网站上报返回的累计访问次数；首页用它覆盖可能更早读取的统计值。
export const latestVisitTotal = ref(null)

// 进入网站时上报一次（服务端按 30 分钟访问 Cookie 去重）。该接口不需要 CSRF 令牌，失败静默，不影响使用。
export async function reportSiteEntry() {
  try {
    const response = await fetch('/api/site-visits', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    if (!response.ok) return
    const result = await response.json()
    if (Number.isFinite(result.total)) latestVisitTotal.value = result.total
  } catch { /* 计数失败不影响网站使用 */ }
}
