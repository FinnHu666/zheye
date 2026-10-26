<script setup>
// 只渲染 safe-markdown 产出的令牌树：文本一律用插值，HTML/脚本只会显示为文字；图片不加载。
import { computed } from 'vue'
import { markdownBlocks, noteText } from '../lib/safe-markdown'
const props = defineProps({ source: { type: String, default: '' } })
const parsed = computed(() => markdownBlocks(props.source))
const notes = computed(() => noteText(parsed.value.notes))
defineExpose({ notes })
</script>
<template>
  <div class="ct-md">
    <p v-if="notes.length" class="rv-notice amber ct-md-notes" role="status">安全处理：{{ notes.join('；') }}。</p>
    <template v-for="(block, index) in parsed.blocks" :key="index">
      <component :is="'h' + Math.min(6, block.level + 1)" v-if="block.k === 'h'"><template v-for="(t, i) in block.inline" :key="i"><SafeInline :token="t" /></template></component>
      <p v-else-if="block.k === 'p'"><template v-for="(t, i) in block.inline" :key="i"><SafeInline :token="t" /></template></p>
      <p v-else-if="block.k === 'li'" class="ct-md-li"><template v-for="(t, i) in block.inline" :key="i"><SafeInline :token="t" /></template></p>
      <blockquote v-else-if="block.k === 'quote'"><template v-for="(t, i) in block.inline" :key="i"><SafeInline :token="t" /></template></blockquote>
      <pre v-else-if="block.k === 'code'">{{ block.text }}</pre>
    </template>
    <p v-if="!parsed.blocks.length" class="ct-md-empty">（正文为空）</p>
  </div>
</template>
<script>
import { h } from 'vue'
const SafeInline = {
  props: { token: Object },
  setup: (props) => () => {
    const t = props.token
    if (t.k === 'strong') return h('strong', t.text)
    if (t.k === 'code') return h('code', t.text)
    if (t.k === 'link') return h('a', { href: t.href, rel: 'noopener noreferrer nofollow', target: '_blank' }, t.text)
    if (t.k === 'blocked') return h('span', { class: 'ct-md-blocked', title: '不安全链接，未生成可点击链接' }, t.text)
    if (t.k === 'image') return h('span', { class: 'ct-md-image' }, `🖼 图片未加载：${t.alt}`)
    return h('span', t.text)
  },
}
export default { components: { SafeInline } }
</script>
