<script setup>
// 第二步：内容编排（参考图 4）。左：模块库（“进入模块市场”看全部模块）；中：顶部固定封面 + 自由画布；右：属性设置。
// 画布上的每个模块（专题简介和全部内容模块）可拖动、缩放；位置 x/w 为百分比，y/h 为像素，允许重叠，移动不改变阅读顺序。
// 服务端暂无布局字段：位置只暂存在本浏览器，公开页仍按阅读顺序呈现（见 lib/collectionWizard.js）。
// 结构性操作与布局调整进入历史栈（撤销/重做）；逐字输入不进入历史。
import { computed, nextTick, onMounted, ref, toRaw, watch } from 'vue'
import { CANVAS_WIDTHS, MODULES, ZOOMS, checkCover, clampLayout, defaultHeight, stackLayouts, stageHeight } from '../../lib/collectionWizard'
import { compileHtmlBlock } from '../../lib/topic-builder/html'
import { MAX_PLACED, loadLibrary, loadPlaced, newId, saveLibrary, savePlaced } from '../../lib/collectionHtmlModules'
import HtmlImportDialog from './HtmlImportDialog.vue'
const props = defineProps({
  draft: { type: Object, required: true },
  extras: { type: Object, required: true },
  posts: { type: Array, default: () => [] },
  focus: { type: Object, default: null },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['stub','append'])
const root = ref(null), stageEl = ref(null), device = ref('desktop'), width = ref(1200), zoom = ref(100)
const freeDrag = ref(typeof window === 'undefined' || !window.matchMedia?.('(max-width: 700px)').matches)
const selected = ref({ type: 'home' }), panelTab = ref('content'), coverError = ref('')
const market = ref(false), marketFilter = ref('all'), marketEl = ref(null); let marketTrigger = null
const past = ref([]), future = ref([]), gesture = ref(null)
// “我的”：用户自己导入的 HTML 模块（模块库）与已放到画布上的 HTML 模块。服务端暂无存储，二者都只暂存在本浏览器，发布时不包含。
const checkHtml = source => compileHtmlBlock(source)
const library = ref(loadLibrary(checkHtml)), placed = ref([]), importing = ref(false)
watch(() => props.draft.id, id => { placed.value = loadPlaced(id, checkHtml) }, { immediate: true })
watch(placed, list => { savePlaced(props.draft.id, list) }, { deep: true })
const htmlDocs = computed(() => Object.fromEntries(placed.value.map(h => { try { return [h.id, { srcdoc: compileHtmlBlock(h.source).srcdoc }] } catch (e) { return [h.id, { error: e.message }] } })))
// 两列（模块库、属性设置）可折叠，把空间让给画布；状态记在本浏览器
const FOLD_KEY = 'finns.collection-wizard.columns'
const defaultFold = () => ({ side: false, props: false })
const readFold = () => { try { return { ...defaultFold(), ...JSON.parse(localStorage.getItem(FOLD_KEY) || '{}') } } catch { return defaultFold() } }
const fold = ref(readFold())
const toggleFold = name => { fold.value = { ...fold.value, [name]: !fold.value[name] }; try { localStorage.setItem(FOLD_KEY, JSON.stringify(fold.value)) } catch { /* 存储不可用时只在本次有效 */ } }
const openProps = () => { if (fold.value.props) toggleFold('props') }
const keys = new WeakMap(); let nextKey = 0
const key = item => { const raw = toRaw(item); if (!keys.has(raw)) keys.set(raw, `cw-${++nextKey}`); return keys.get(raw) }
const sections = computed(() => props.draft.sections)
const addSection = () => { if(sections.value.length>=50){emit('stub','专题最多50个章节');return} record();sections.value.push({title:'新章节',description:'',displayLayout:'LIST',entries:[]});selected.value={type:'home'};openProps() }
const moveSection = (i,direction) => { const to=i+direction;if(to<0||to>=sections.value.length)return;record();[sections.value[i],sections.value[to]]=[sections.value[to],sections.value[i]];selected.value={type:'home'} }
const removeSection = i => { if(!window.confirm('删除章节会同时删除其中的模块，确定继续？'))return;record();sections.value.splice(i,1);selected.value={type:'home'} }
const changeSection = event => { const value=event.target.value,from=selected.value.si,ei=selected.value.ei;if(value==='new'){const count=sections.value.length;addSection();if(sections.value.length===count)return;const [entry]=sections.value[from].entries.splice(ei,1);sections.value[count].entries.push(entry);selected.value={type:'entry',si:count,ei:0};return}const to=Number(value);if(from===to)return;record();const [entry]=sections.value[from].entries.splice(ei,1);sections.value[to].entries.push(entry);selected.value={type:'entry',si:to,ei:sections.value[to].entries.length-1} }
const modules = computed(() => MODULES.map(m => ['timeline','cards'].includes(m.key) && props.extras.layoutsEnabled ? { ...m, stub:false,kind:'NOTE',preset:m.title,template:m.key==='timeline'?'记录时间与事件':'在这里写下内容' } : m))
const gridModules = computed(() => modules.value.filter(m => m.grid))
const kindLabel = kind => ({ NOTE: '富文本', POST: '我的文章', LINK: '参考链接', intro: '专题简介', html: 'HTML 模块' }[kind])
const kindIcon = kind => ({ NOTE: '文', POST: '稿', LINK: '链', intro: '简', html: 'H' }[kind])
const preview = entry => entry.kind === 'NOTE' ? (entry.body || '') : entry.kind === 'LINK' ? (entry.externalUrl || '') : (entry.postTitle || (entry.postId ? '已选择文章' : '尚未选择文章'))

// ---- 画布模块列表：专题简介 + 全部内容模块（阅读顺序）。界面不再出现“章节”，内容统一放进默认内容区 ----
const items = computed(() => [
  ...sections.value.flatMap((section, si) => section.entries.map((entry, ei) => ({ id: key(entry), kind: entry.kind, si, ei, entry, section, holder: entry, field: 'layout' }))),
  ...placed.value.map(h => ({ id: h.id, kind: 'html', html: h, holder: h, field: 'layout' }))])
const entryItems = computed(() => items.value.filter(i => i.kind !== 'html'))
const defaults = computed(() => stackLayouts(items.value.map(i => i.kind)))
const layouts = computed(() => items.value.map((item, i) => item.holder[item.field] ? clampLayout(item.holder[item.field]) : defaults.value[i]))
const layoutOf = item => layouts.value[items.value.indexOf(item)]
const stageH = computed(() => stageHeight(layouts.value))
const isSelected = item => item.kind === 'html' ? selected.value.type === 'html' && selected.value.id === item.id : selected.value.type === 'entry' && selected.value.si === item.si && selected.value.ei === item.ei
const blockStyle = item => { const l = layoutOf(item); return freeDrag.value ? { left: `${l.x}%`, top: `${l.y}px`, width: `${l.w}%`, height: `${l.h}px`, zIndex: (l.z ?? 0) + 1 } : {} }

const current = computed(() => {
  const s = selected.value
  if (s.type === 'home') return { type: 'home' }
  if (s.type === 'html') { const item = items.value.find(i => i.kind === 'html' && i.id === s.id); return item ? { type: 'html', html: item.html, item } : { type: 'home' } }
  const section = sections.value[s.si]
  if (!section) return { type: 'home' }
  const entry = section.entries[s.ei]
  return entry ? { type: 'entry', section, entry, item: items.value.find(i => i.entry === entry) } : { type: 'home' }
})

// ---- 历史（撤销/重做）：结构变化与布局调整 ----
const snap = () => JSON.stringify({ s: sections.value, i: props.extras.introLayout, h: placed.value })
const record = () => { past.value.push(snap()); if (past.value.length > 50) past.value.shift(); future.value = [] }
const restore = text => {
  const data = JSON.parse(text)
  sections.value.splice(0, sections.value.length, ...data.s); props.extras.introLayout = data.i; placed.value = data.h || []
  if (selected.value.type === 'html' ? !placed.value.some(h => h.id === selected.value.id) : selected.value.type !== 'home' && !sections.value[selected.value.si]) selected.value = { type: 'home' }
}
const undo = () => { if (!past.value.length) return; future.value.push(snap()); restore(past.value.pop()) }
const redo = () => { if (!future.value.length) return; past.value.push(snap()); restore(future.value.pop()) }

// ---- 选择与定位 ----
const scrollToEl = async (selector, field) => {
  await nextTick()
  root.value?.querySelector(selector)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (field) { await nextTick(); root.value?.querySelector(`.cwb-props [data-field="${field}"]`)?.focus({ preventScroll: true }) }
}
const selectHome = () => { selected.value = { type: 'home' } }
const selectEntry = (si, ei, scroll = true) => { selected.value = { type: 'entry', si, ei }; if (scroll) scrollToEl(`[data-entry="${key(sections.value[si].entries[ei])}"]`) }
const selectItem = item => item.kind === 'html' ? (selected.value = { type: 'html', id: item.id }) : selectEntry(item.si, item.ei, false)

// ---- 编辑动作 ----
const bottomLayout = kind => { const bottom = Math.max(0, ...layouts.value.map(l => l.y + l.h)); return { x: 3, y: bottom ? bottom + 16 : 0, w: 94, h: defaultHeight(kind) } }
// 新插入的模块出现在当前可视画布的正中；那里已有模块（位置几乎重合）就依次错开一点，避免完全叠在一起
const centeredLayout = (kind, height) => {
  const h = height || defaultHeight(kind), w = 60, rect = stageEl.value?.getBoundingClientRect()
  const scale = rect ? rect.width / (stageEl.value.offsetWidth || rect.width) : 1
  const y = rect ? ((window.innerHeight + 88) / 2 - rect.top) / scale - h / 2 : 0
  let layout = clampLayout({ x: (100 - w) / 2, y: Math.max(0, Math.min(y, Math.max(stageH.value - h, 0))), w, h })
  for (let i = 0; i < 12 && layouts.value.some(l => Math.abs(l.x - layout.x) < 2 && Math.abs(l.y - layout.y) < 24); i++) layout = clampLayout({ ...layout, x: layout.x + 3, y: layout.y + 32 })
  return layout
}
const newEntry = (kind, preset = '', template = '') => ({ kind, title: preset, body: template, annotation: '', externalUrl: '', postId: null })
const insert = module => {
  if (module.stub) { emit('stub', `「${module.title}」将在后续版本开放，目前请先用富文本、链接或文章模块编排内容`); return }
  if (props.disabled) return
  touchModule(module.key); openProps(); record()
  if (!sections.value.length) sections.value.push({ title: '内容', description: '', entries: [] })
  const si = sections.value.length - 1
  const section = sections.value[si]; if (['cards','timeline'].includes(module.key)) section.displayLayout = module.key==='cards'?'CARDS':'TIMELINE'; const entry = newEntry(module.kind, module.preset || '', module.template || '')
  if (freeDrag.value) entry.layout = centeredLayout(module.kind)
  section.entries.push(entry)
  selected.value = { type: 'entry', si, ei: section.entries.length - 1 }; panelTab.value = 'content'
  scrollToEl(`[data-entry="${key(entry)}"]`, module.kind === 'NOTE' ? 'body' : module.kind === 'LINK' ? 'title' : 'postId')
}
const moveEntry = (si, ei, direction) => {
  const flat = sections.value.flatMap((s, i) => s.entries.map((_, j) => [i, j]))
  const at = flat.findIndex(([i, j]) => i === si && j === ei), to = at + direction
  if (to < 0 || to >= flat.length) return
  record(); const [ti, tj] = flat[to]
  if (ti === si) { const list = sections.value[si].entries;[list[ei], list[tj]] = [list[tj], list[ei]]; selected.value = { type: 'entry', si, ei: tj }; return }
  const [entry] = sections.value[si].entries.splice(ei, 1), target = sections.value[ti].entries
  if (direction < 0) { target.push(entry); selected.value = { type: 'entry', si: ti, ei: target.length - 1 } } else { target.unshift(entry); selected.value = { type: 'entry', si: ti, ei: 0 } }
}
const isFirst = item => entryItems.value.indexOf(item) === 0
const isLast = item => entryItems.value.indexOf(item) === entryItems.value.length - 1
// 把“我的”里的 HTML 模块放到画布（居中，已有模块则错开）；只存在本浏览器
const addHtml = mod => {
  if (props.disabled) return
  if (placed.value.length >= MAX_PLACED) { emit('stub', `画布上最多放 ${MAX_PLACED} 个 HTML 模块，请先删除不用的。`); return }
  openProps(); record()
  const h = { id: newId(), name: mod.name, source: mod.source, height: mod.height, layout: centeredLayout('html', mod.height) }
  placed.value.push(h); selected.value = { type: 'html', id: h.id }; panelTab.value = 'content'; closeMarket()
  scrollToEl(`[data-html="${h.id}"]`)
}
const removeHtml = id => { record(); placed.value = placed.value.filter(h => h.id !== id); selected.value = { type: 'home' } }
const saveImported = data => {
  library.value = [{ id: newId(), ...data }, ...library.value]; saveLibrary(library.value); importing.value = false; marketFilter.value = 'mine'
  emit('stub', `已保存到“我的”：${data.name}`)
}
const removeFromLibrary = mod => { if (!window.confirm(`确定从“我的”删除“${mod.name}”吗？画布上已放置的不受影响。`)) return; library.value = library.value.filter(m => m.id !== mod.id); saveLibrary(library.value) }
const removeEntry = (section, ei) => { record(); section.entries.splice(ei, 1); selected.value = { type: 'home' } }
const duplicate = (section, ei) => {
  record(); const copy = { ...JSON.parse(JSON.stringify(section.entries[ei])), id: undefined, title: section.entries[ei].title ? `${section.entries[ei].title}（副本）`.slice(0, 160) : '' }
  if (freeDrag.value) copy.layout = bottomLayout(copy.kind)
  section.entries.splice(ei + 1, 0, copy); selected.value = { type: 'entry', si: sections.value.indexOf(section), ei: ei + 1 }
}
// ---- 模块市场（全部模块）----
// 收藏、最近使用、使用次数只记在本浏览器；热度 = 使用次数加一个默认排序（暂无站内统计）
const MOD_KEY = 'finns.collection-wizard.modules'
const readMods = () => {
  try { const v = JSON.parse(localStorage.getItem(MOD_KEY) || '{}'); return { favorites: Array.isArray(v.favorites) ? v.favorites : [], recent: Array.isArray(v.recent) ? v.recent : [], usage: v.usage && typeof v.usage === 'object' ? v.usage : {} } } catch { return { favorites: [], recent: [], usage: {} } }
}
const mods = ref(readMods())
const saveMods = () => { try { localStorage.setItem(MOD_KEY, JSON.stringify(mods.value)) } catch { /* 存储不可用时只在本次有效 */ } }
const touchModule = moduleKey => { const m = mods.value; m.usage[moduleKey] = (m.usage[moduleKey] || 0) + 1; m.recent = [moduleKey, ...m.recent.filter(k => k !== moduleKey)].slice(0, 6); saveMods() }
const toggleFavorite = moduleKey => { const m = mods.value; m.favorites = m.favorites.includes(moduleKey) ? m.favorites.filter(k => k !== moduleKey) : [...m.favorites, moduleKey]; saveMods() }
const isFavorite = moduleKey => mods.value.favorites.includes(moduleKey)
const SEED_HEAT = { richtext: 90, step: 80, table: 70, quote: 60, faq: 50, checklist: 40, link: 30, article: 20, carousel: 9, rank: 8, timeline: 7, video: 6, cards: 5 }
const heatOf = m => (mods.value.usage[m.key] || 0) * 10 + (SEED_HEAT[m.key] || 0)
const ranked = computed(() => [...modules.value].sort((a, b) => heatOf(b) - heatOf(a)))
const recentModules = computed(() => mods.value.recent.map(k => ranked.value.find(m => m.key === k)).filter(Boolean))
const marketList = computed(() => marketFilter.value === 'fav' ? ranked.value.filter(m => isFavorite(m.key)) : ranked.value)
const openMarket = event => { marketTrigger = event?.currentTarget || null; marketFilter.value = 'all'; market.value = true; nextTick(() => marketEl.value?.querySelector('button')?.focus()) }
const closeMarket = () => { market.value = false; nextTick(() => marketTrigger?.focus?.()) }
const addFromMarket = module => { if (module.stub) { emit('stub', `「${module.title}」将在后续版本开放`); return } insert(module); closeMarket() }

// ---- 画布布局：拖动、缩放、键盘微调、数值输入 ----
const writeLayout = (item, layout) => { item.holder[item.field] = clampLayout(layout) }
const ensureLayout = item => { if (!item.holder[item.field]) item.holder[item.field] = { ...layoutOf(item) } }
const startGesture = (item, mode, event) => {
  if (!freeDrag.value || props.disabled || event.button > 0) return
  if (event.target.closest('button, input, select, textarea, label, a')) return
  if (event.pointerType === 'touch' && !event.target.closest('header')) return // 触屏只认标题栏，避免挡住页面滚动
  selectItem(item)
  const rect = stageEl.value.getBoundingClientRect()
  gesture.value = { item, mode, startX: event.clientX, startY: event.clientY, orig: { ...layoutOf(item) }, width: rect.width, scale: rect.width / (stageEl.value.offsetWidth || rect.width), pointerId: event.pointerId, moved: false }
  try { event.currentTarget.setPointerCapture?.(event.pointerId) } catch { /* 合成事件没有真实指针 */ }
  event.preventDefault()
}
const onGesture = event => {
  const g = gesture.value
  if (!g || event.pointerId !== g.pointerId) return
  const dx = (event.clientX - g.startX) / g.width * 100, dy = (event.clientY - g.startY) / g.scale
  if (!g.moved) { if (Math.hypot(event.clientX - g.startX, event.clientY - g.startY) < 4) return; g.moved = true; record(); ensureLayout(g.item) }
  const next = g.mode === 'resize' ? { ...g.orig, w: g.orig.w + dx, h: g.orig.h + dy } : { ...g.orig, x: g.orig.x + dx, y: g.orig.y + dy }
  writeLayout(g.item, next)
}
const endGesture = event => { if (gesture.value && event.pointerId === gesture.value.pointerId) gesture.value = null }
const nudge = (item, event) => {
  if (!freeDrag.value || props.disabled || !event.key.startsWith('Arrow')) return
  event.preventDefault(); record(); ensureLayout(item)
  const l = { ...layoutOf(item) }, dx = { ArrowLeft: -1, ArrowRight: 1 }[event.key] || 0, dy = { ArrowUp: -8, ArrowDown: 8 }[event.key] || 0
  if (event.shiftKey) { l.w += dx; l.h += dy } else { l.x += dx; l.y += dy }
  writeLayout(item, l)
}
const setField = (item, field, value) => { if (value === '' || value === null || Number.isNaN(Number(value))) return; record(); ensureLayout(item); writeLayout(item, { ...layoutOf(item), [field]: Number(value) }) }
const resetLayout = item => { record(); item.holder[item.field] = null }
// ---- 图层顺序：每个模块的层级 z 记在布局里，右键菜单里调整 ----
const zOf = item => layoutOf(item).z ?? 0
const layerList = () => [...items.value].sort((a, b) => (zOf(a) - zOf(b)) || (items.value.indexOf(a) - items.value.indexOf(b)))
const layerState = item => { const list = layerList(), i = list.indexOf(item); return { top: i === list.length - 1, bottom: i === 0 } }
const moveLayer = (item, how) => {
  const list = layerList(), i = list.indexOf(item)
  const to = how === 'top' ? list.length - 1 : how === 'bottom' ? 0 : how === 'up' ? Math.min(list.length - 1, i + 1) : Math.max(0, i - 1)
  if (to === i) return
  record(); list.splice(i, 1); list.splice(to, 0, item)
  list.forEach((it, idx) => { ensureLayout(it); writeLayout(it, { ...layoutOf(it), z: idx + 1 }) })
}
// ---- 右键菜单：编辑、复制、图层、阅读顺序、删除（不再在模块头部放按钮，避免盖住内容）----
const menu = ref(null), menuEl = ref(null)
const menuTarget = computed(() => menu.value ? items.value.find(i => i.id === menu.value.itemId) : null)
const menuItems = computed(() => {
  const item = menuTarget.value
  if (!item) return []
  const entry = item.kind !== 'html', layer = layerState(item), list = [{ key: 'edit', label: '编辑' }]
  if (entry) list.push({ key: 'copy', label: '复制' })
  if (freeDrag.value) list.push({ sep: true }, { key: 'top', label: '置于顶层', disabled: layer.top }, { key: 'up', label: '上移一层', disabled: layer.top }, { key: 'down', label: '下移一层', disabled: layer.bottom }, { key: 'bottom', label: '置于底层', disabled: layer.bottom })
  if (entry) list.push({ sep: true }, { key: 'order-up', label: '阅读顺序上移', disabled: isFirst(item) }, { key: 'order-down', label: '阅读顺序下移', disabled: isLast(item) })
  list.push({ sep: true }, { key: 'delete', label: '删除', danger: true })
  return list
})
const closeMenu = (focusBack = true) => { const id = menu.value?.itemId; menu.value = null; window.removeEventListener('resize', closeMenu); window.removeEventListener('blur', closeMenu); if (focusBack && id) nextTick(() => root.value?.querySelector(`[data-entry="${id}"], [data-html="${id}"]`)?.focus({ preventScroll: true })) }
const openMenu = (item, event) => {
  if (props.disabled) return
  event.preventDefault(); selectItem(item)
  const rect = event.currentTarget.getBoundingClientRect()
  menu.value = { itemId: item.id, x: event.clientX || rect.left + 24, y: event.clientY || rect.top + 24 }
  window.addEventListener('resize', closeMenu); window.addEventListener('blur', closeMenu)
  nextTick(() => { const box = menuEl.value?.getBoundingClientRect(); if (box && menu.value) menu.value = { ...menu.value, x: Math.max(8, Math.min(menu.value.x, window.innerWidth - box.width - 8)), y: Math.max(8, Math.min(menu.value.y, window.innerHeight - box.height - 8)) }; menuEl.value?.querySelector('button:not(:disabled)')?.focus() })
}
const menuKey = event => {
  if (event.key === 'Escape') { event.preventDefault(); closeMenu(); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = [...menuEl.value.querySelectorAll('button:not(:disabled)')], at = buttons.indexOf(document.activeElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : event.key === 'ArrowDown' ? (at + 1) % buttons.length : (at - 1 + buttons.length) % buttons.length
  buttons[next]?.focus()
}
const menuAct = key => {
  const item = menuTarget.value
  closeMenu(false)
  if (!item) return
  if (key === 'edit') { openProps(); panelTab.value = 'content'; scrollToEl('.cwb-props', item.kind === 'html' ? 'title' : item.entry.kind === 'NOTE' ? 'body' : 'title') }
  else if (key === 'copy') duplicate(item.section, item.ei)
  else if (['top', 'up', 'down', 'bottom'].includes(key)) moveLayer(item, key)
  else if (key === 'order-up') moveEntry(item.si, item.ei, -1)
  else if (key === 'order-down') moveEntry(item.si, item.ei, 1)
  else if (key === 'delete') item.kind === 'html' ? removeHtml(item.id) : removeEntry(item.section, item.ei)
}

// ---- 封面（仅当前页预览，引子）----
const pickCover = event => {
  const file = event.target.files?.[0]; event.target.value = ''
  coverError.value = checkCover(file)
  if (coverError.value) return
  if (props.extras.cover) URL.revokeObjectURL(props.extras.cover)
  props.extras.cover = URL.createObjectURL(file); props.extras.coverFile = file
  emit('stub', '封面将在保存草稿时上传并同步服务器')
}

// ---- 发布前问题定位（来自预览发布步骤）----
const reveal = issue => {
  if (!issue) return
  openProps()
  const section = sections.value[issue.section]
  const entry = section && issue.entry != null ? section.entries[issue.entry] : null
  if (!entry) { selected.value = { type: 'home' }; scrollToEl('.cwb-props'); return }
  selected.value = { type: 'entry', si: issue.section, ei: issue.entry }; panelTab.value = 'content'
  scrollToEl(`[data-entry="${key(entry)}"]`, issue.field)
}
onMounted(() => reveal(props.focus))
watch(() => props.focus, reveal)
defineExpose({ reveal })
const canvasStyle = computed(() => ({ maxWidth: device.value === 'mobile' ? '390px' : `${width.value}px`, zoom: zoom.value / 100 }))
</script>

<template>
  <div ref="root" class="cwb" :class="{ 'side-off': fold.side, 'props-off': fold.props }">
    <div class="cwb-side" :class="{ off: fold.side }" @click="fold.side && toggleFold('side')">
      <div class="cwb-panel-bar"><span v-if="!fold.side" class="cwb-bar-title">模块库</span><button type="button" class="cwb-toggle" :class="{ flip: fold.side }" :aria-expanded="!fold.side" :aria-label="fold.side ? '展开模块库' : '折叠模块库'" @click.stop="toggleFold('side')"><svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M10 3 5 8l5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></button><span v-if="fold.side" class="cwb-vlabel">模块库</span></div>
      <section v-if="!fold.side" class="cwb-library" aria-label="模块库">
        <ul class="cwb-grid"><li v-for="m in gridModules" :key="m.key"><button type="button" class="cwb-mod" :class="{ stub: m.stub }" :disabled="disabled && !m.stub" :title="m.stub ? `${m.title}：即将开放` : m.hint" @click="insert(m)"><i aria-hidden="true">{{ m.icon }}</i><span>{{ m.title }}</span><em v-if="m.stub" class="cwb-soon">即将开放</em></button></li></ul>
        <button type="button" class="cwb-showmore" @click="openMarket">进入模块市场 ›</button>
      </section>
    </div>

    <section class="cwb-stage-col" aria-label="画布">
      <div class="cwb-toolbar" role="toolbar" aria-label="画布工具">
        <div class="cwb-seg" role="group" aria-label="预览设备"><button type="button" :class="{ active: device === 'desktop' }" :aria-pressed="device === 'desktop'" aria-label="桌面宽度" @click="device = 'desktop'">▭</button><button type="button" :class="{ active: device === 'mobile' }" :aria-pressed="device === 'mobile'" aria-label="手机宽度" @click="device = 'mobile'">▯</button></div>
        <select v-model.number="width" aria-label="页面宽度" :disabled="device === 'mobile'"><option v-for="w in CANVAS_WIDTHS" :key="w.value" :value="w.value">{{ w.label }}</option></select>
        <span class="cwb-hist"><button type="button" aria-label="撤销" :disabled="!past.length" @click="undo">↶</button><button type="button" aria-label="重做" :disabled="!future.length" @click="redo">↷</button></span>
        <span class="cwb-tip">右键点模块：更多操作</span>
        <span class="cwb-spacer"></span>
        <label class="cwb-switch"><span>自由拖动布局</span><input v-model="freeDrag" type="checkbox" role="switch" /><i aria-hidden="true"></i></label>
        <button type="button" class="cwb-layer" @click="emit('stub', '图层管理将在后续版本开放')">图层</button>
        <select v-model.number="zoom" aria-label="画布缩放"><option v-for="z in ZOOMS" :key="z" :value="z">{{ z }}%</option></select>
      </div>

      <fieldset class="cwb-canvas-wrap" :disabled="disabled">
        <div class="cwb-canvas" :style="canvasStyle">
          <article class="cwb-cover" :class="{ selected: selected.type === 'home', empty: !extras.cover }" data-block="home" tabindex="0" @click="selectHome" @keydown.enter.self="selectHome">
            <img v-if="extras.cover" :src="extras.cover" alt="" />
            <div class="cwb-hero-text"><span class="cwb-tag">专题</span><h2>{{ draft.name || '未命名专题' }}</h2><p>{{ draft.description || '点击封面，在右侧填写专题简介。' }}</p></div>
            <label class="cwb-cover-btn" @click.stop><input type="file" accept="image/jpeg,image/png" @change="pickCover" />更换封面</label>
          </article>
          <p v-if="coverError" class="cw-error" role="alert">{{ coverError }}</p>
          <div v-if="!items.length" class="cw-empty cwb-empty"><p>画布上还没有内容模块，从左侧模块库选择一个开始。</p><div class="cwb-empty-actions"><button type="button" class="btn btn-outline" :disabled="disabled" @click="openMarket">从模块库添加</button></div></div>

          <div ref="stageEl" class="cwb-stage" :class="{ free: freeDrag }" :style="freeDrag ? { height: stageH + 'px' } : {}" @pointermove="onGesture" @pointerup="endGesture" @pointercancel="endGesture">
            <article v-for="item in items" :key="item.id" class="cwb-block" :class="{ selected: isSelected(item), moving: gesture?.item === item }" :style="blockStyle(item)" :data-entry="item.entry ? item.id : null" :data-html="item.kind === 'html' ? item.id : null" :aria-label="`${kindLabel(item.kind)}模块`" tabindex="0" @click.stop="selectItem(item)" @keydown.enter.self="selectItem(item)" @keydown.self="nudge(item, $event)" @pointerdown="startGesture(item, 'move', $event)" @contextmenu="openMenu(item, $event)">
              <header :class="{ grab: freeDrag }">
                <i class="cwb-ico" aria-hidden="true">{{ kindIcon(item.kind) }}</i>
                <strong>{{ item.kind === 'html' ? item.html.name : (item.entry.title || kindLabel(item.kind)) }}</strong>
                <em v-if="item.kind === 'html'" class="cwb-chip" title="HTML 模块目前只保存在本浏览器">仅本机预览</em>
                <button v-if="isSelected(item)" type="button" class="cwb-more-btn" aria-haspopup="menu" aria-label="更多操作（也可以右键点模块）" title="更多操作（也可以右键点模块）" @click.stop="openMenu(item, $event)">⋯</button>
              </header>
              <div v-if="item.kind === 'html'" class="cwb-html"><p v-if="htmlDocs[item.id]?.error" class="cw-error">{{ htmlDocs[item.id].error }}</p><iframe v-else sandbox="" referrerpolicy="no-referrer" :title="`HTML 模块：${item.html.name}`" :srcdoc="htmlDocs[item.id]?.srcdoc" :style="freeDrag ? {} : { height: item.html.height + 'px', flex: 'none' }"></iframe></div>
              <p v-else class="cwb-preview" :class="{ placeholder: !preview(item.entry) }">{{ preview(item.entry) || (item.entry.kind === 'NOTE' ? '空内容：在右侧属性设置里填写正文' : item.entry.kind === 'LINK' ? '尚未填写链接地址' : '尚未选择文章') }}</p>
              <span v-if="freeDrag" class="cwb-resize" role="presentation" title="拖动调整大小" @pointerdown.stop="startGesture(item, 'resize', $event)"></span>
            </article>
          </div>
        </div>
      </fieldset>
    </section>

    <aside class="cwb-props" :class="{ off: fold.props }" aria-label="属性设置" @click="fold.props && toggleFold('props')">
      <div class="cwb-panel-bar"><span v-if="!fold.props" class="cwb-bar-title">属性设置</span><button type="button" class="cwb-toggle" :class="{ flip: !fold.props }" :aria-expanded="!fold.props" :aria-label="fold.props ? '展开属性设置' : '折叠属性设置'" @click.stop="toggleFold('props')"><svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M10 3 5 8l5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg></button><span v-if="fold.props" class="cwb-vlabel">属性设置</span></div>
      <div v-if="!fold.props" class="cwb-props-body">
      <div class="cwb-props-head"><i aria-hidden="true">{{ current.type === 'entry' ? kindIcon(current.entry.kind) : current.type === 'html' ? 'H' : '简' }}</i><span><strong>{{ current.type === 'entry' ? kindLabel(current.entry.kind) : current.type === 'html' ? 'HTML 模块' : '专题首页' }}</strong><small>{{ current.type === 'entry' ? '画布模块' : current.type === 'html' ? '仅本机预览，发布时不包含' : '封面与简介' }}</small></span><button type="button" class="cw-link" @click="emit('stub', '更换模块类型将在后续版本开放')">更换模块</button></div>
      <div class="cw-tabs" role="tablist"><button type="button" role="tab" :aria-selected="panelTab === 'content'" :class="{ active: panelTab === 'content' }" @click="panelTab = 'content'">内容设置</button><button type="button" role="tab" :aria-selected="panelTab === 'style'" :class="{ active: panelTab === 'style' }" @click="panelTab = 'style'">样式设置</button><button type="button" role="tab" :aria-selected="panelTab === 'display'" :class="{ active: panelTab === 'display' }" @click="panelTab = 'display'">显示设置</button></div>

      <fieldset v-if="panelTab === 'content'" class="cwb-fields" :disabled="disabled">
        <template v-if="current.type === 'home'">
          <label>专题简介<span class="cw-input-wrap"><textarea v-model="draft.description" data-field="description" maxlength="260" rows="5" placeholder="用几句话概括专题的价值和适用人群"></textarea><span class="cw-count">{{ draft.description.length }}/260</span></span></label>
          <p class="cw-hint">标题、适用人群、发布目标在第一步“基础信息”里修改。封面请点画布顶部的“更换封面”，保存后同步服务器。</p>
          <section class="cwb-chapter-settings"><h3>章节管理</h3><div v-for="(chapter,i) in sections" :key="i"><label>章节 {{ i+1 }}<input v-model="chapter.title" maxlength="80" /></label><label>章节说明<textarea v-model="chapter.description" maxlength="500" rows="2"></textarea></label><div><button type="button" :disabled="i===0" @click="moveSection(i,-1)">上移</button><button type="button" :disabled="i===sections.length-1" @click="moveSection(i,1)">下移</button><button type="button" @click="removeSection(i)">删除章节</button></div></div><button type="button" class="btn btn-outline" @click="addSection">添加章节</button></section>
        </template>
        <template v-else-if="current.type === 'html'">
          <label>模块名称<input v-model="current.html.name" data-field="title" maxlength="40" /></label>
          <p class="cw-hint">HTML 模块在隔离的预览框里显示（不运行脚本）。它目前只保存在这台电脑的浏览器里，发布到公开页时不包含。</p>
          <button type="button" class="cw-link danger" @click="removeHtml(current.html.id)">从画布删除</button>
        </template>
        <template v-else>
          <label>所属章节<select :value="selected.si" @change="changeSection"><option v-for="(chapter,i) in sections" :key="i" :value="i">{{ chapter.title }}</option><option value="new">＋新建章节…</option></select></label>
          <label v-if="extras.layoutsEnabled">章节展示<select v-model="current.section.displayLayout"><option value="LIST">顺序列表</option><option value="CARDS">卡片</option><option value="TIMELINE">时间轴</option></select></label>
          <template v-if="current.entry.kind === 'NOTE'">
            <label>模块标题（可选）<input v-model="current.entry.title" data-field="title" maxlength="160" placeholder="小标题，留空也可以发布" /></label>
            <label>正文<span class="cw-input-wrap"><textarea v-model="current.entry.body" data-field="body" maxlength="4000" rows="9" placeholder="直接写下经验、步骤或想法，支持 Markdown。没写完也会保存。"></textarea><span class="cw-count">{{ (current.entry.body || '').length }}/4000</span></span></label>
          </template>
          <template v-else-if="current.entry.kind === 'LINK'">
            <label>链接标题<input v-model="current.entry.title" data-field="title" maxlength="160" placeholder="这份资料叫什么" /></label>
            <label>链接地址<input v-model="current.entry.externalUrl" data-field="externalUrl" maxlength="2000" placeholder="https://…" /></label>
          </template>
          <template v-else>
            <label>选择我的文章<select v-model="current.entry.postId" data-field="postId"><option :value="null">稍后选择文章</option><option v-for="post in posts" :key="post.id" :value="post.id">{{ post.title }}</option></select></label>
            <p class="cw-hint">只能引用你自己已公开的文章。</p>
          </template>
          <label>来源与备注（可选）<textarea v-model="current.entry.annotation" maxlength="1000" rows="2" placeholder="来源、适用范围或核验日期"></textarea></label>
        </template>
      <section v-if="freeDrag && current.type !== 'home'" class="cwb-pos"><h4>位置与大小</h4>
        <div class="cwb-pos-grid"><label>左 %<input type="number" step="1" :value="layoutOf(current.item).x" @change="setField(current.item, 'x', $event.target.value)" /></label><label>上 px<input type="number" step="8" :value="layoutOf(current.item).y" @change="setField(current.item, 'y', $event.target.value)" /></label><label>宽 %<input type="number" step="1" :value="layoutOf(current.item).w" @change="setField(current.item, 'w', $event.target.value)" /></label><label>高 px<input type="number" step="8" :value="layoutOf(current.item).h" @change="setField(current.item, 'h', $event.target.value)" /></label></div>
        <button type="button" class="cw-link" @click="resetLayout(current.item)">恢复自动排列</button>
        <p class="cw-hint">位置只暂存在本浏览器，公开页暂按阅读顺序呈现。键盘：选中模块后方向键移动，Shift+方向键调整大小。</p></section>
      </fieldset>

      <div v-else class="cwb-stub-panel">
        <p class="cw-soon-banner"><em class="cw-soon">即将开放</em>{{ panelTab === 'style' ? '样式设置' : '显示设置' }}目前不会保存，下面只是版式预览。</p>
        <fieldset disabled>
          <template v-if="panelTab === 'style'"><span class="cwb-label">模块样式</span><div class="cwb-styles"><span class="on">列表样式</span><span>卡片样式</span><span>表格样式</span></div><span class="cwb-label">对齐方式</span><div class="cwb-styles"><span>左</span><span class="on">中</span><span>右</span></div></template>
          <template v-else><label>模块宽度<select><option>跟随页面（推荐）</option></select></label><label class="cwb-inline"><input type="checkbox" checked />在页面中显示此模块</label></template>
        </fieldset>
      </div>
      </div>
    </aside>

    <div v-if="market" class="cwb-market-back" @click.self="closeMarket" @keydown.esc="closeMarket">
      <section ref="marketEl" class="cwb-market" role="dialog" aria-modal="true" aria-labelledby="cwb-market-title">
        <header><h3 id="cwb-market-title">模块市场</h3><button type="button" class="cwb-toggle" aria-label="关闭模块市场" @click="closeMarket"><svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></button></header>
        <div class="cw-tabs" role="tablist"><button type="button" role="tab" :aria-selected="marketFilter === 'all'" :class="{ active: marketFilter === 'all' }" @click="marketFilter = 'all'">全部</button><button type="button" role="tab" :aria-selected="marketFilter === 'fav'" :class="{ active: marketFilter === 'fav' }" @click="marketFilter = 'fav'">收藏<span v-if="mods.favorites.length" class="cwb-count">{{ mods.favorites.length }}</span></button><button type="button" role="tab" :aria-selected="marketFilter === 'mine'" :class="{ active: marketFilter === 'mine' }" @click="marketFilter = 'mine'">我的<span v-if="library.length" class="cwb-count">{{ library.length }}</span></button></div>
        <template v-if="marketFilter === 'mine'">
          <div class="cwb-mine-head"><p class="cw-hint">这里只有你自己导入的 HTML 模块（静态 HTML + CSS）。目前只保存在这台电脑的浏览器里，换浏览器或清除数据后会消失。</p><button type="button" class="btn btn-primary" @click="importing = true">导入 HTML 模块</button></div>
          <p v-if="!library.length" class="cw-empty small">还没有导入过。点“导入 HTML 模块”，粘贴代码或选择 .html 文件。</p>
          <ul v-else class="cwb-market-grid"><li v-for="m in library" :key="m.id"><div class="cwb-market-card"><button type="button" class="cwb-market-main" :disabled="disabled" @click="addHtml(m)"><i aria-hidden="true">H</i><span><strong>{{ m.name }}</strong><small>显示高度 {{ m.height }}px · {{ m.source.length.toLocaleString() }} 字</small></span><span class="cwb-market-act">添加</span></button><button type="button" class="cwb-star del" :aria-label="`删除我的模块${m.name}`" @click="removeFromLibrary(m)">删</button></div></li></ul>
        </template>
        <template v-else>
        <template v-if="marketFilter === 'all' && recentModules.length"><h4 class="cwb-market-h">最近使用</h4><ul class="cwb-market-grid"><template v-for="m in recentModules" :key="'r-' + m.key"><li><div class="cwb-market-card" :class="{ stub: m.stub }"><button type="button" class="cwb-market-main" :disabled="disabled && !m.stub" @click="addFromMarket(m)"><i aria-hidden="true">{{ m.icon }}</i><span><strong>{{ m.title }}<em v-if="m.stub" class="cw-soon">即将开放</em></strong><small>{{ m.hint }}</small></span><span class="cwb-market-act">{{ m.stub ? '' : '添加' }}</span></button><button type="button" class="cwb-star" :class="{ on: isFavorite(m.key) }" :aria-pressed="isFavorite(m.key)" :aria-label="`${isFavorite(m.key) ? '取消收藏' : '收藏'}${m.title}`" @click="toggleFavorite(m.key)">{{ isFavorite(m.key) ? '★' : '☆' }}</button></div></li></template></ul></template>
        <h4 v-if="marketFilter === 'all'" class="cwb-market-h">热门模块<small>按使用热度排序</small></h4>
        <p v-if="marketFilter === 'fav' && !marketList.length" class="cw-empty small">还没有收藏。点模块卡片右侧的星标，常用模块会出现在这里。</p>
        <ul v-else class="cwb-market-grid"><template v-for="(m, i) in marketList" :key="m.key"><li><div class="cwb-market-card" :class="{ stub: m.stub }"><button type="button" class="cwb-market-main" :disabled="disabled && !m.stub" @click="addFromMarket(m)"><i aria-hidden="true">{{ m.icon }}</i><span><strong>{{ m.title }}<em v-if="m.stub" class="cw-soon">即将开放</em></strong><small>{{ m.hint }}</small></span><span class="cwb-market-act">{{ m.stub ? '' : '添加' }}</span></button><button type="button" class="cwb-star" :class="{ on: isFavorite(m.key) }" :aria-pressed="isFavorite(m.key)" :aria-label="`${isFavorite(m.key) ? '取消收藏' : '收藏'}${m.title}`" @click="toggleFavorite(m.key)">{{ isFavorite(m.key) ? '★' : '☆' }}</button></div></li></template></ul>
        <p class="cw-hint">收藏、最近使用和热度只记在本浏览器；“即将开放”的模块点击只会提示，不会写入内容。</p>
        </template>
      </section>
    </div>
    <div v-if="menu" class="cwb-menu-back" @pointerdown.self="closeMenu()" @contextmenu.prevent="closeMenu()">
      <ul ref="menuEl" class="cwb-menu" role="menu" aria-label="模块操作" :style="{ left: menu.x + 'px', top: menu.y + 'px' }" @keydown="menuKey"><template v-for="(m, i) in menuItems" :key="i"><li v-if="m.sep" class="sep" role="separator"></li><li v-else role="none"><button type="button" role="menuitem" :class="{ danger: m.danger }" :disabled="m.disabled" @click="menuAct(m.key)">{{ m.label }}</button></li></template></ul>
    </div>
    <HtmlImportDialog v-if="importing" :library="library" @close="importing = false" @save="saveImported" />
  </div>
</template>
