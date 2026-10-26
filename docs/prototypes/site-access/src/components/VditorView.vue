<script setup>
// 阅读/预览渲染：与编辑器同一个引擎（Vditor.preview + Lute，sanitize 开启），保证编辑时所见与读者所见一致（PUBLISH-D14 E1）。
// 外链图片规则（CT-R13，D14 A1/B1）：只显示 http/https 的 Markdown 图片；不发送来源（no-referrer）；原生懒加载；
// 等比缩放；加载失败换成占位框（说明文字 + 图片域名），不自动重试。原始 <img> 写法按文字显示，不渲染。
import { onMounted, ref, watch } from 'vue'
import Vditor from 'vditor'
import 'vditor/dist/index.css'
const props = defineProps({ source: { type: String, default: '' } })
const el = ref(null)
// 原始 HTML 的 <img 先转义为文字，剩下的 <img> 只可能来自 Markdown 图片语法
const escapeRawImg = md => String(md || '').replace(/<(\s*img\b)/gi, '&lt;$1')
const hostOf = src => { try { return new URL(src).host } catch { return '' } }
function imagePolicy(html) {
  const tpl = document.createElement('template'); tpl.innerHTML = html
  for (const img of tpl.content.querySelectorAll('img')) {
    const src = img.getAttribute('src') || ''
    if (!/^https?:\/\//i.test(src)) { img.replaceWith(placeholder(img.getAttribute('alt'), '', '图片地址不受支持')); continue }
    img.setAttribute('referrerpolicy', 'no-referrer'); img.setAttribute('loading', 'lazy'); img.setAttribute('decoding', 'async')
    img.removeAttribute('data-src'); img.classList.add('ct-ext-img')
  }
  return tpl.innerHTML
}
function placeholder(alt, host, reason = '图片无法加载') {
  const box = document.createElement('span'); box.className = 'ct-img-fail'
  box.textContent = `${reason}${alt ? '：' + alt : ''}${host ? '（' + host + '）' : ''}`
  return box
}
const onError = e => { const img = e.target; if (img?.tagName === 'IMG' && img.classList.contains('ct-ext-img')) img.replaceWith(placeholder(img.getAttribute('alt'), hostOf(img.getAttribute('src')))) }
const render = () => { if (el.value) Vditor.preview(el.value, escapeRawImg(props.source), { cdn: '/vendor/vditor', lang: 'zh_CN', mode: 'light', markdown: { sanitize: true }, theme: { current: 'light' }, transform: imagePolicy }) }
onMounted(() => { el.value.addEventListener('error', onError, true); render() }); watch(() => props.source, render)
</script>
<template><div ref="el" class="vditor-reset ct-vditor-view"></div></template>
