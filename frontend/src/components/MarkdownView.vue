<script setup>
// 文章阅读/预览渲染（契约 §3.2）：与编辑器同一引擎 Vditor（Lute），sanitize + 本站规则（原始 HTML 文字化、外链图片策略）。
// 资源加载失败时显示原文，不让读者看到空白。
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
// 按需加载：只有文章阅读/编辑处才下载 Vditor 主包
const loadVditor = () => import('vditor').then((m) => m.default)
import 'vditor/dist/index.css'
import { escapeRawHtml, imageFallback, previewOptions } from '../lib/markdownRender'
const props = defineProps({ source: { type: String, default: '' } })
const el = ref(null), failed = ref(false)
let detach = null, token = 0
function render() {
  if (!el.value) return
  const mine = ++token; failed.value = false
  const timer = setTimeout(() => { if (mine === token) failed.value = true }, 8000)
  loadVditor().then((Vditor) => Vditor.preview(el.value, escapeRawHtml(props.source), previewOptions({ after: () => clearTimeout(timer) })))
    .catch(() => { clearTimeout(timer); if (mine === token) failed.value = true })
}
onMounted(() => { detach = imageFallback(el.value); render() })
watch(() => props.source, render)
onBeforeUnmount(() => { token++; detach?.() })
</script>
<template>
  <div class="md-view">
    <template v-if="failed"><p class="rv-notice amber" role="status">排版组件未加载，以下为原文。</p><pre class="md-fallback">{{ source }}</pre></template>
    <div v-show="!failed" ref="el" class="vditor-reset md-body"></div>
  </div>
</template>
