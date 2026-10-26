import { publicationIssues } from './collectionDraft.js'

// 专题创建向导（基础信息 → 内容编排 → 预览发布）。
// “后端已支持”的字段直接落在专题工作稿；“引子”字段（封面、标签、分类、定时发布、SEO、内容模块）
// 目前只在前端占位并暂存在本浏览器，待服务端接口与迁移就绪后整体接入，见 docs/iterations/0.8.0。

export const STEPS = [
  { key: 'basic', label: '基础信息' },
  { key: 'content', label: '内容编排' },
  { key: 'publish', label: '预览发布' }
]

export const AUDIENCES = ['学生', '职场人士', '产品/运营', '开发者', '设计师', '创业者', 'AI 爱好者', '其他']

export const GOALS = [
  { key: '知识普及', hint: '帮助用户了解相关知识' },
  { key: '技能提升', hint: '教授实用方法与技能' },
  { key: '产品推广', hint: '围绕产品或服务进行介绍' },
  { key: '品牌宣传', hint: '提升品牌影响力' }
]

// 引子：分类暂无服务端字段（清单终稿见需规修订4；存储时用稳定英文 key，写死在代码，单选、选填）
export const CATEGORY_KEYS = ['ai','engineering','operations','productivity','learning','career','life','other']
export const CATEGORIES = ['AI 与大模型', '开发与工程', '运维与自托管', '效率与工具', '学习与成长', '职场与职业', '生活与经验', '其他']

// 模块库（第二步右侧/左侧模块区）：kind 为已有条目类型，template 为插入时预置的 Markdown 模板；
// stub 为引子（点击只提示，不写入内容）。内容模块范围经 D19 拟定、D20 起页面按参考图重新展示轮播/评分榜/视频等入口，
// 但它们仍是“暂缓实现”的占位；表格/FAQ/引用提示只是预置 Markdown 的富文本，不新增条目类型。
export const MODULES = [
  { key: 'richtext', title: '富文本', hint: '支持 Markdown 的正文', kind: 'NOTE', icon: 'T', grid: true },
  { key: 'carousel', title: '图片轮播', hint: '多图展示，支持跳转', stub: true, icon: '图', grid: true },
  { key: 'rank', title: '评分榜', hint: '展示热门内容的评分与排行', stub: true, icon: '榜', grid: true },
  { key: 'timeline', title: '时间轴', hint: '按时间展示发展历程', stub: true, icon: '轴', grid: true },
  { key: 'step', title: '步骤卡片', hint: '按顺序写下步骤', kind: 'NOTE', preset: '操作步骤', template: '1. 第一步\n2. 第二步\n3. 第三步', icon: '步', grid: true },
  { key: 'faq', title: 'FAQ 问答', hint: '常见问题与解答', kind: 'NOTE', preset: '常见问题', template: '**Q：问题**\n\nA：回答', icon: '问', grid: true },
  { key: 'table', title: '表格', hint: 'Markdown 表格', kind: 'NOTE', template: '| 项目 | 说明 |\n| --- | --- |\n| 示例 | 内容 |', icon: '表', grid: true },
  { key: 'video', title: '视频', hint: '嵌入视频', stub: true, icon: '视', grid: true },
  { key: 'quote', title: '引用/提示', hint: '突出显示重要内容', kind: 'NOTE', template: '> 在这里写下需要强调的内容', icon: '引', grid: true },
  { key: 'checklist', title: '检查清单', hint: '逐项核对的事项', kind: 'NOTE', preset: '检查清单', template: '□ 待检查事项\n□ 待补充事项', icon: '清' },
  { key: 'link', title: '参考链接', hint: '外部资料与来源', kind: 'LINK', icon: '链' },
  { key: 'article', title: '我的文章', hint: '引用自己已公开的文章', kind: 'POST', icon: '稿' },
  { key: 'cards', title: '卡片列表', hint: '以卡片形式展示内容', stub: true, icon: '卡' }
]

// 画布宽度选项（仅影响编辑画布，不改变公开页）
export const CANVAS_WIDTHS = [{ value: 1200, label: '页面宽度 1200px' }, { value: 960, label: '页面宽度 960px' }, { value: 760, label: '页面宽度 760px' }]
export const ZOOMS = [75, 100, 125]

export const parseAudience = (text) => {
  const parts = String(text || '').split(/[、,，]/).map(s => s.trim())
  return AUDIENCES.filter(label => parts.includes(label))
}
export const joinAudience = (labels) => AUDIENCES.filter(label => labels.includes(label)).join('、')
export const parseGoal = (text) => GOALS.find(g => g.key === String(text || '').trim())?.key || ''

const blank = value => !String(value || '').trim()

// 基础信息的必填项：名称由服务端强制，其余是向导的完整性要求
export function basicIssues(draft) {
  const issues = []
  const add = (field, message) => issues.push({ step: 'basic', field, message })
  if (blank(draft.name)) add('name', '填写专题标题')
  if (blank(draft.description)) add('description', '填写专题简介')
  if (!parseAudience(draft.audience).length && blank(draft.audience)) add('audience', '至少选择一类适用人群')
  if (blank(draft.goal)) add('goal', '选择发布目标')
  return issues
}

// 界面不再出现“章节”：把服务端沿用的章节措辞改成模块措辞
const moduleMessage = issue => {
  if (issue.field === 'sections' || issue.field === 'entries') return '添加一个内容模块（从左侧模块库选择）'
  if (issue.entry != null) return issue.message.replace(/^.*? · 第 (\d+) 条：/, '第 $1 个模块：')
  return issue.message
}

// 发布前问题：基础信息 + 内容（与服务端发布校验一致的部分来自 publicationIssues）
export function wizardIssues(draft) {
  const basic = basicIssues(draft)
  const content = publicationIssues(draft).filter(issue => issue.field !== 'name').map(issue => ({ ...issue, step: 'content', message: moduleMessage(issue) }))
  return [...basic, ...content]
}

export function summarize(draft) {
  const sections = draft.sections || []
  const entries = sections.reduce((n, s) => n + s.entries.length, 0)
  return { sections: sections.length, entries }
}

// 内容完整度：四项各占 25%
export function completeness(draft) {
  const { sections, entries } = summarize(draft)
  const items = [
    { label: '已设置专题标题', done: !blank(draft.name) },
    { label: '已填写专题简介', done: !blank(draft.description) },
    { label: '已创建内容区', done: sections > 0 },
    { label: '已添加内容模块', done: entries > 0 }
  ]
  return { items, percent: Math.round(items.filter(i => i.done).length / items.length * 100) }
}

// 填写建议随输入变化
export function suggestions(draft, extras) {
  const name = String(draft.name || '').trim(), description = String(draft.description || '').trim()
  return [
    { text: '标题简洁明确，建议在 20 个字以内，突出专题核心内容', done: name.length > 0 && name.length <= 20 },
    { text: '简介清晰概括专题的价值和适用人群，建议 50–260 个字', done: description.length >= 50 && description.length <= 260 },
    { text: '封面建议 16:9（如 1200 × 675），不超过 2MB，宽高不超过4096像素', done: !!extras.cover },
    { text: '标签用于展示；选择分类后可在专题广场按分类发现', done: extras.tags.length >= 3 },
    { text: '根据专题的主要目的选择合适的发布目标', done: !!parseGoal(draft.goal) }
  ]
}

// ---- 引子字段的浏览器暂存 ----
export const defaultExtras = () => ({ tags: [], category: '', seoTitle: '', scheduled: false, scheduledTime: '', coverId: null, coverFile: null, cover: '', layoutsEnabled: false, introLayout: null })
const storageKey = id => `finns.collection-wizard.${id}`

export function loadExtras(id, storage = globalThis.localStorage) {
  const extras = defaultExtras()
  try {
    const saved = JSON.parse(storage?.getItem(storageKey(id)) || '{}')
    if (Array.isArray(saved.tags)) extras.tags = saved.tags.filter(t => typeof t === 'string').slice(0, 6)
    if (typeof saved.category === 'string' && CATEGORIES.includes(saved.category)) extras.category = saved.category
    if (typeof saved.seoTitle === 'string') extras.seoTitle = saved.seoTitle.slice(0, 60)
  } catch { /* 浏览器禁用存储时仍可正常使用，只是引子字段不会保留 */ }
  return extras
}

export function saveExtras(id, extras, storage = globalThis.localStorage) {
  try { storage?.setItem(storageKey(id), JSON.stringify({ tags: extras.tags, category: extras.category, seoTitle: extras.seoTitle })) } catch { /* 同上 */ }
}

// 封面只在当前页面预览：校验格式与大小
export function checkCover(file) {
  if (!file) return '请选择图片文件'
  if (!['image/jpeg', 'image/png'].includes(file.type)) return '封面仅支持 JPG、PNG 格式'
  if (file.size > 2 * 1024 * 1024) return '封面文件大小不能超过 2MB'
  return ''
}

export const addTag = (tags, raw) => {
  const tag = String(raw || '').normalize('NFKC').trim().replace(/\s+/g, ' ').slice(0, 12)
  if (!tag || tags.some(t => t.toLowerCase() === tag.toLowerCase()) || tags.length >= 6) return tags
  return [...tags, tag]
}

// ---- 画布布局（第二步，除顶部封面外的模块可在画布上自由移动/缩放）----
// 约定沿用专题搭建器：x、w 为画布宽度的百分比，y、h 为像素；允许重叠；移动不改变阅读顺序。
// 服务端目前没有布局字段：位置只暂存在本浏览器（按“章节→条目”的顺序下标），公开页仍按阅读顺序呈现。
const DEFAULT_HEIGHT = { intro: 150, NOTE: 210, LINK: 120, POST: 120 }
export const defaultHeight = kind => DEFAULT_HEIGHT[kind] || 160
export function clampLayout(layout) {
  const num = (v, fallback) => Number.isFinite(Number(v)) && v !== '' && v !== null ? Number(v) : fallback
  const w = Math.min(100, Math.max(20, num(layout?.w, 94)))
  const x = Math.min(100 - w, Math.max(0, num(layout?.x, 3)))
  const y = Math.min(10000, Math.max(0, num(layout?.y, 0)))
  const h = Math.min(1600, Math.max(80, num(layout?.h, 160)))
  const z = layout?.z === '' || layout?.z === null ? NaN : Number(layout?.z) // 层级（图层顺序）：有才保留
  return { x: Math.round(x * 10) / 10, y: Math.round(y), w: Math.round(w * 10) / 10, h: Math.round(h), ...(Number.isFinite(z) ? { z: Math.min(999, Math.max(0, Math.round(z))) } : {}) }
}
// 没有手动摆放的模块按阅读顺序自上而下排开
export function stackLayouts(kinds) {
  let y = 0
  return kinds.map(kind => { const h = defaultHeight(kind); const layout = { x: 3, y, w: 94, h }; y += h + 16; return layout })
}
export const stageHeight = layouts => Math.max(320, ...layouts.map(l => l.y + l.h)) + 60
export const collectLayouts = (sections, intro) => ({ intro: intro || null, entries: sections.flatMap(s => s.entries.map(e => e.layout || null)) })
export function applyLayouts(sections, saved) {
  const flat = sections.flatMap(s => s.entries)
  if (!saved || !Array.isArray(saved.entries) || saved.entries.length !== flat.length) return false
  flat.forEach((entry, i) => { const layout = saved.entries[i]; if (layout && typeof layout === 'object') entry.layout = clampLayout(layout) })
  return true
}
const layoutKey = id => `finns.collection-wizard.layout.${id}`
export function loadLayouts(id, storage = globalThis.localStorage) {
  try { return JSON.parse(storage?.getItem(layoutKey(id)) || 'null') } catch { return null }
}
export function saveLayouts(id, data, storage = globalThis.localStorage) {
  try { storage?.setItem(layoutKey(id), JSON.stringify(data)) } catch { /* 存储不可用时布局仅在本次编辑中有效 */ }
}
