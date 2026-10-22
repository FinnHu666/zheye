<script setup>
// 只渲染 safeMarkdown 产出的令牌树：文本一律用插值，HTML/脚本只会显示为文字；图片不加载。
// 预览页和公开阅读共用，保证“预览所见 = 发布后所见”。
import { computed, h } from 'vue'
import { markdownBlocks, noteText } from '../lib/safeMarkdown'
const props = defineProps({ source: { type: String, default: '' }, showNotes: { type: Boolean, default: true } })
const parsed = computed(() => markdownBlocks(props.source))
const notes = computed(() => noteText(parsed.value.notes))

const SafeInline = (p) => {
  const t = p.token
  if (t.k === 'strong') return h('strong', t.text)
  if (t.k === 'code') return h('code', t.text)
  if (t.k === 'link') return h('a', { href: t.href, rel: 'noopener noreferrer nofollow', target: '_blank' }, t.text)
  if (t.k === 'blocked') return h('span', { class: 'ct-md-blocked', title: '不安全链接，未生成可点击链接' }, t.text)
  if (t.k === 'image') return h('span', { class: 'ct-md-image' }, `🖼 图片未加载：${t.alt}`)
  return h('span', t.text)
}
SafeInline.props = ['token']
</script>
<template>
  <div class="ct-md">
    <p v-if="showNotes && notes.length" class="rv-notice amber ct-md-notes" role="status">安全处理：{{ notes.join('；') }}。</p>
    <template v-for="(block, index) in parsed.blocks" :key="index">
      <component :is="'h' + (block.level + 1)" v-if="block.k === 'h'"><SafeInline v-for="(t, i) in block.inline" :key="i" :token="t" /></component>
      <p v-else-if="block.k === 'p'"><SafeInline v-for="(t, i) in block.inline" :key="i" :token="t" /></p>
      <p v-else-if="block.k === 'li'" class="ct-md-li"><SafeInline v-for="(t, i) in block.inline" :key="i" :token="t" /></p>
      <blockquote v-else-if="block.k === 'quote'"><SafeInline v-for="(t, i) in block.inline" :key="i" :token="t" /></blockquote>
      <pre v-else-if="block.k === 'code'">{{ block.text }}</pre>
    </template>
    <p v-if="!parsed.blocks.length" class="ct-md-empty">（正文为空）</p>
  </div>
</template>
