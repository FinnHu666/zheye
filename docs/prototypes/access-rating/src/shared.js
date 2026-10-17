import { reactive } from 'vue'

/* 原型工作草案参数：随原型展示，供 G2 确认时逐项核对；不是实际运行规则。 */
export const params = reactive({
  windowMin: 30,          // 浏览量去重窗口（分钟）
  modalCounts: false,     // 首页文章弹层阅读是否计入
  scaleMax: 5,            // 评分量表上限
  minSample: 5,           // 红/黑榜所需最小有效样本
  red: 4,                 // 红榜阈值（≥）
  black: 2                // 黑榜阈值（≤）
})

export const confirmations = reactive({}) // 清单页的勾选，仅本页内存

export function safeStorage() {
  try { const k = '__finns_proto__'; localStorage.setItem(k, '1'); localStorage.removeItem(k); return localStorage } catch { return null }
}

export const uid = (() => { let n = 100; return (p = 'x') => `${p}${++n}` })()
