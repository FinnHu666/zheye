<script setup>
// FINNS-18 文章编辑器（原型）：Vditor 4.0.1（MIT）。“阅读模式”= Vditor 即时渲染 ir（类 Typora，边写边呈现）；
// “源码”= 纯 Markdown 文本框（Vditor 的 sv 模式在销毁重建后内容为空，原型改用文本框）。两者编辑同一份 Markdown。
// 资源从本地 /vendor/vditor 加载（vite.config.mjs 中间件），不访问外部 CDN；不提供图片上传。
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Vditor from 'vditor'
import 'vditor/dist/index.css'
const props = defineProps({ modelValue: { type: String, default: '' }, mode: { type: String, default: 'read' }, disabled: Boolean, height: { type: Number, default: 520 } })
const emit = defineEmits(['update:modelValue'])
const host = ref(null), area = ref(null); let vd = null, ready = false
const TOOLBAR = ['headings', 'bold', 'italic', 'strike', '|', 'list', 'ordered-list', 'check', 'quote', 'line', 'code', 'inline-code', 'link', 'table', '|', 'undo', 'redo']
function create() {
  ready = false; const value = props.modelValue
  vd = new Vditor(host.value, {
    cdn: '/vendor/vditor', lang: 'zh_CN', mode: 'ir', value, height: props.height, minHeight: 360,
    cache: { enable: false }, toolbar: TOOLBAR, toolbarConfig: { pin: true }, counter: { enable: false },
    preview: { markdown: { sanitize: true }, actions: [] }, placeholder: '',
    input: v => emit('update:modelValue', v),
    after: () => { if (vd.getValue() !== value) vd.setValue(value, true); ready = true; if (props.disabled) vd.disabled() },
  })
}
const destroy = () => { try { vd?.destroy() } catch {} vd = null; ready = false }
// 编辑器内的图片预览同样不发送来源（A1）：原型用文档级 referrer 策略；正式实现由服务端 Referrer-Policy 头覆盖文章页
function noReferrer() { if (!document.querySelector('meta[name="referrer"]')) { const m = document.createElement('meta'); m.name = 'referrer'; m.content = 'no-referrer'; document.head.appendChild(m) } }
onMounted(() => { noReferrer(); if (props.mode !== 'source') create() })
watch(() => props.mode, async m => { if (m === 'source') destroy(); else { await nextTick(); create() } })
watch(() => props.modelValue, v => { if (ready && v !== vd.getValue()) vd.setValue(v, true) })
watch(() => props.disabled, d => { if (ready) d ? vd.disabled() : vd.enable() })
onBeforeUnmount(destroy)
defineExpose({ focus: () => props.mode === 'source' ? area.value?.focus() : ready && vd.focus() })
</script>
<template>
  <textarea v-if="mode === 'source'" ref="area" class="ct-source" :value="modelValue" :disabled="disabled" spellcheck="false" aria-label="正文 Markdown 源码" :style="{ height: height + 'px' }" @input="emit('update:modelValue', $event.target.value)" />
  <div v-else ref="host" class="ct-vditor"></div>
</template>
