// “我的”HTML 模块：用户自己导入的静态 HTML + CSS。
// 检查规则复用专题搭建器的 compileHtmlBlock（只允许静态 HTML/CSS，渲染在无脚本沙箱 iframe 里）。
// 服务端目前没有 HTML 模块的存储：模块库与画布上已放置的模块都只暂存在本浏览器（换设备/清数据就没了），
// 放置在画布上的 HTML 模块发布时不包含。本文件只做存储与整理，检查函数由调用方传入，便于测试。
export const MAX_LIBRARY = 30 // 我的模块库最多条数
export const MAX_PLACED = 20 // 单个专题画布上最多放置数（同搭建器 maxHtmlBlocks）
export const MAX_FILE_BYTES = 131072
export const MIN_HEIGHT = 120
export const MAX_HEIGHT = 1200
export const NAME_MAX = 40

const libraryKey = 'finns.collection-wizard.html-library'
const placedKey = id => `finns.collection-wizard.html-placed.${id}`
const read = (key, storage) => { try { return JSON.parse(storage?.getItem(key) || 'null') } catch { return null } }
const write = (key, value, storage) => { try { storage?.setItem(key, JSON.stringify(value)); return true } catch { return false } }

export const clampHeight = value => Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, Math.round(Number(value) || 240)))
export const newId = () => `h${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`

// 读出来的数据一律重新检查：坏数据、超长、检查不通过的都丢掉
export function normalizeModules(raw, check, limit = MAX_LIBRARY) {
  if (!Array.isArray(raw)) return []
  const out = []
  for (const item of raw) {
    if (!item || typeof item.source !== 'string' || typeof item.name !== 'string') continue
    const name = item.name.trim().slice(0, NAME_MAX)
    if (!name) continue
    try { check(item.source) } catch { continue }
    out.push({ id: typeof item.id === 'string' && item.id ? item.id : newId(), name, source: item.source, height: clampHeight(item.height), ...(item.layout && typeof item.layout === 'object' ? { layout: item.layout } : {}) })
    if (out.length >= limit) break
  }
  return out
}

export const loadLibrary = (check, storage = globalThis.localStorage) => normalizeModules(read(libraryKey, storage), check, MAX_LIBRARY)
export const saveLibrary = (list, storage = globalThis.localStorage) => write(libraryKey, list.map(({ id, name, source, height }) => ({ id, name, source, height })), storage)
export const loadPlaced = (draftId, check, storage = globalThis.localStorage) => draftId ? normalizeModules(read(placedKey(draftId), storage), check, MAX_PLACED) : []
export const savePlaced = (draftId, list, storage = globalThis.localStorage) => draftId ? write(placedKey(draftId), list.map(({ id, name, source, height, layout }) => ({ id, name, source, height, layout })), storage) : false

// 导入表单校验：名称、显示高度（代码本身由检查函数负责）
export function importIssue({ name, height, library = [] }) {
  const trimmed = String(name || '').trim()
  if (!trimmed) return '请给模块起个名字。'
  if (trimmed.length > NAME_MAX) return `名称最多 ${NAME_MAX} 个字。`
  if (library.some(m => m.name === trimmed)) return '已有同名模块，请换个名字。'
  if (library.length >= MAX_LIBRARY) return `“我的”最多保存 ${MAX_LIBRARY} 个模块，请先删除不用的。`
  const h = Number(height)
  if (!Number.isFinite(h) || h < MIN_HEIGHT || h > MAX_HEIGHT) return `显示高度需要在 ${MIN_HEIGHT}–${MAX_HEIGHT}px 之间。`
  return ''
}
