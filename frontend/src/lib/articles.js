// FINNS-18：文章草稿接口封装、状态文案与错误码映射（契约 §4、§5）。页面不直接拼接口地址或错误文案。
import { api } from './api.js'

export const LIMITS = { title: 120, summary: 280, body: 20000 }
export const FIELDS = ['title', 'summary', 'body']
export const FIELD_TEXT = { title: '标题', summary: '摘要', body: '正文' }
export const STATE_FILTERS = [['ALL', '全部'], ['DRAFT', '私人草稿'], ['REVIEW', '审核相关'], ['LIVE', '已公开'], ['OFF', '已撤回 / 下架']]

// 长度按码点计（决定 D3），与服务端 TextLimits 一致。
export const countChars = (value) => [...String(value ?? '')].length
export const pickFields = (source) => ({ title: source?.title ?? '', summary: source?.summary ?? '', body: source?.body ?? '' })
export const sameFields = (a, b) => FIELDS.every((k) => (a?.[k] ?? '') === (b?.[k] ?? ''))
export const overLimit = (form) => FIELDS.filter((k) => countChars(form[k]) > LIMITS[k])
export const missingForPublish = (form) => FIELDS.filter((k) => !String(form[k] ?? '').trim())
export const isBlank = (form) => FIELDS.every((k) => !String(form[k] ?? '').trim())

export const newRequestId = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`

// 公开状态 / 工作状态 → [文案, 徽标样式]
export const publicChip = (status) => ({
  NONE: ['未公开', 'neutral'], LIVE: ['已公开', 'green'], WITHDRAWN: ['已撤回', 'neutral'], DOWN: ['已被下架', 'danger'],
}[status?.publicState] || ['未公开', 'neutral'])
export const workChip = (status) => ({
  PRIVATE: ['私人草稿', 'neutral'], SAVED: ['已保存', 'neutral'], MODIFIED: ['有未发布的修改', 'amber'],
  REVIEWING: ['审核中', 'amber'], RECHECK: ['未通过 · 复核中', 'danger'], REJECTED: ['未通过', 'danger'],
}[status?.work] || ['已保存', 'neutral'])

export const publishLabel = (view) => {
  if (!view) return '发布'
  if (view.submitted) return '重新提交（旧提交作废）'
  if (view.status?.publicState === 'LIVE') return '提交更新'
  if (view.status?.publicState === 'WITHDRAWN') return '重新公开'
  return '发布'
}
export const publishHint = (view) => {
  const state = view?.status?.publicState
  if (state === 'LIVE') return '更新会先保存当前输入并冻结，再检测；通过前读者继续看到旧版本。'
  if (state === 'WITHDRAWN') return '重新公开会重新检测，旧的审核结论不会恢复公开。'
  return '发布会先保存当前输入并冻结，再在本次请求中检测：无命中直接发布，中敏感送审，高敏感自动不通过并转人工复核。'
}

export const outcomeTone = (outcome) => outcome === 'AUTO_REJECTED' ? 'danger' : outcome === 'PENDING' ? 'amber' : 'success'

// 错误 → 页面应对。code 来自服务端 ApiProblem；401 是登录失效（由 useSessionGuard 接管）。
export function errorKind(error) {
  if (!error) return 'other'
  if (error.status === 401) return 'session'
  if (error.code === 'VERSION_CONFLICT' || error.code === 'REQUEST_REUSED') return 'conflict'
  if (error.code === 'DETECTION_UNAVAILABLE' || error.status === 503) return 'retryable'
  if (error.code === 'TAKEN_DOWN' || error.code === 'NOT_PUBLIC' || error.code === 'NOT_DELETABLE' || error.code === 'NOTHING_TO_CANCEL' || error.code === 'RECHECK_NOT_CANCELLABLE') return 'state'
  if (error.code === 'VALIDATION' || error.code === 'TOO_LONG' || error.code === 'EMPTY_DRAFT' || error.code === 'CONFIRM_REQUIRED') return 'input'
  return 'other'
}
export const errorText = (error) => ({
  session: '登录已失效。你的输入仍保留在本页，重新登录同一账号后可以继续保存或发布。',
  retryable: '内容检测暂时不可用，输入已保留。稍后重试不会重复提交。',
}[errorKind(error)] || error?.message || '请求失败，请稍后再试')

const body = (value) => JSON.stringify(value)
export const articles = {
  list: ({ state = 'ALL', q = '', page = 0, size = 20 } = {}) => api(`/api/me/articles?state=${encodeURIComponent(state)}&q=${encodeURIComponent(q)}&page=${page}&size=${size}`),
  get: (id) => api(`/api/me/articles/${id}`),
  create: (fields) => api('/api/me/articles', { method: 'POST', body: body(fields) }),
  save: (id, fields, expectedVersion) => api(`/api/me/articles/${id}`, { method: 'PUT', body: body({ ...fields, expectedVersion }) }),
  publish: (id, fields, expectedVersion, requestId) => api(`/api/me/articles/${id}/publish`, { method: 'POST', body: body({ ...fields, expectedVersion, requestId }) }),
  cancel: (id) => api(`/api/me/articles/${id}/cancel`, { method: 'POST', body: '{}' }),
  withdraw: (id) => api(`/api/me/articles/${id}/withdraw`, { method: 'POST', body: body({ confirm: true }) }),
  remove: (id) => api(`/api/me/articles/${id}`, { method: 'DELETE' }),
}
