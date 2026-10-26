<script setup>
// 文章正文编辑器（契约 §3.3）：阅读模式 = Vditor 即时渲染（ir），源码模式 = 纯 Markdown 文本框；编辑同一字符串。
// 无图片上传、无本地缓存；资源自托管。Vditor 加载失败时退回源码模式。
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
// 按需加载：只有文章阅读/编辑处才下载 Vditor 主包
const loadVditor = () => import('vditor').then((m) => m.default)
import 'vditor/dist/index.css'
import { VDITOR_CDN } from '../lib/markdownRender'
const props = defineProps({ modelValue: { type: String, default: '' }, mode: { type: String, default: 'read' }, disabled: Boolean, height: { type: Number, default: 520 } })
const emit = defineEmits(['update:modelValue', 'unavailable'])
const host = ref(null), area = ref(null), unavailable = ref(false)
let vd = null, ready = false, timer = null, generation = 0
const IMAGE_ICON = '<svg viewBox="0 0 24 24" width="16" height="16" style="fill:none;stroke:var(--toolbar-icon-color,#586069)" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="1.6"/><path d="m21 16-5-5-8 9"/></svg>'
// 图片只支持外链：按钮插入 Markdown 图片语法，不上传
const TOOLBAR = ['headings', 'bold', 'italic', 'strike', 'inline-code', 'link', { name: 'image-link', tip: '插入外链图片', icon: IMAGE_ICON, click: () => { vd?.insertValue('![图片说明](https://)') } }, 'quote', '|', 'list', 'ordered-list', 'check', 'outdent', 'indent', 'table', '|', 'undo', 'redo', 'fullscreen']
async function create() {
  ready = false; const value = props.modelValue, mine = ++generation
  timer = setTimeout(() => { if (!ready) { unavailable.value = true; emit('unavailable') } }, 8000)
  try {
    const Vditor = await loadVditor()
    if (mine !== generation || !host.value) return
    vd = new Vditor(host.value, {
      cdn: VDITOR_CDN, lang: 'zh_CN', mode: 'ir', value, height: props.height, minHeight: 360, icon: 'ant',
      cache: { enable: false }, toolbar: TOOLBAR, toolbarConfig: { pin: true }, counter: { enable: false }, placeholder: '开始编写你的内容…',
      preview: { hljs: { enable: false }, markdown: { sanitize: true, inlineMath: false, mathBlockPreview: false }, actions: [] },
      input: (v) => emit('update:modelValue', v),
      after: () => { clearTimeout(timer); if (vd.getValue() !== value) vd.setValue(value, true); ready = true; if (props.disabled) vd.disabled() },
    })
  } catch { clearTimeout(timer); unavailable.value = true; emit('unavailable') }
}
const destroy = () => { generation++; clearTimeout(timer); try { vd?.destroy() } catch {} vd = null; ready = false }
const useVditor = () => props.mode !== 'source' && !unavailable.value
onMounted(() => { if (useVditor()) create() })
watch(() => props.mode, async () => { destroy(); if (useVditor()) { await nextTick(); create() } })
watch(() => props.modelValue, (v) => { if (ready && v !== vd.getValue()) vd.setValue(v, true) })
watch(() => props.disabled, (d) => { if (ready) d ? vd.disabled() : vd.enable() })
onBeforeUnmount(destroy)
defineExpose({ focus: () => (useVditor() && ready ? vd.focus() : area.value?.focus()) })
</script>
<template>
  <p v-if="unavailable && mode !== 'source'" class="rv-notice amber" role="status">编辑器组件未加载，已切换为 Markdown 源码编辑。</p>
  <textarea v-if="!useVditor()" ref="area" class="md-source" :value="modelValue" :disabled="disabled" spellcheck="false" aria-label="正文 Markdown 源码" placeholder="开始编写你的内容…" :style="{ height: height + 'px' }" @input="emit('update:modelValue', $event.target.value)" />
  <div v-else ref="host" class="md-editor"></div>
</template>
