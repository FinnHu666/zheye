<script setup>
// FINNS-19 R1：阅读模式编写。按块渲染（与公开阅读页同一套样式与安全 Markdown），点击某一块就地改它的源码，失焦后重新渲染。
// 块的划分：“## 章节”单独一块；“[[文章:ID|标题]]”及紧随的“> 备注”一块；其余按空行分段。
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import SafeMarkdown from './SafeMarkdown.vue'
const props = defineProps({ modelValue: { type: String, default: '' }, refs: { type: Object, default: () => ({}) }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const REF = /^\[\[文章:(\d+)(?:\|(.*))?\]\]\s*$/
const editing = ref(-1), draftText = ref(''), root = ref(null)
const blocks = computed(() => {
  const lines = props.modelValue.split('\n'), out = []; let i = 0, chapter = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) { i++; continue }
    const start = i
    if (/^##\s+/.test(line)) { out.push({ kind: 'chapter', start, end: i + 1, title: line.replace(/^##\s+/, '').trim() || '未命名章节', no: ++chapter }); i++; continue }
    const ref = REF.exec(line)
    if (ref) { i++; let note = ''; if (i < lines.length && /^>\s?/.test(lines[i])) { note = lines[i].replace(/^>\s?/, ''); i++ } out.push({ kind: 'ref', start, end: i, postId: Number(ref[1]), title: ref[2] || '', note }); continue }
    while (i < lines.length && lines[i].trim() && !/^##\s+/.test(lines[i]) && !REF.test(lines[i])) i++
    out.push({ kind: chapter ? 'text' : 'intro', start, end: i, source: lines.slice(start, i).join('\n') })
  }
  return out
})
const sourceOf = b => props.modelValue.split('\n').slice(b.start, b.end).join('\n')
const edit = async (index) => { if (props.disabled) return; editing.value = index; draftText.value = sourceOf(blocks.value[index]); await nextTick(); const el = root.value?.querySelector('.fc-block-input'); if (el) { el.focus(); fit(el) } }
const fit = el => { el.style.height = 'auto'; el.style.height = el.scrollHeight + 2 + 'px' }
const commit = () => {
  const index = editing.value; if (index < 0) return
  const b = blocks.value[index], lines = props.modelValue.split('\n')
  lines.splice(b.start, b.end - b.start, ...draftText.value.split('\n'))
  editing.value = -1; emit('update:modelValue', lines.join('\n'))
}
const append = async kind => {
  const tail = props.modelValue.replace(/\n*$/, ''), add = kind === 'chapter' ? '## 新章节' : '新段落'
  emit('update:modelValue', (tail ? tail + '\n\n' : '') + add + '\n'); await nextTick(); edit(blocks.value.length - 1)
}
onBeforeUnmount(commit) // switching to source mode keeps an unfinished block edit
const lineOfChapter = no => blocks.value.find(b => b.kind === 'chapter' && b.no === no)?.start
defineExpose({ reveal: line => root.value?.querySelector(`[data-line="${line}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }), append, lineOfChapter })
const onKey = e => { if (e.key === 'Escape') { editing.value = -1 } else if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') commit() }
</script>
<template>
  <div ref="root" class="fc-reading reading-content" :class="{ disabled }">
    <p v-if="!blocks.length" class="fc-empty"><button type="button" class="text-link" @click="append('chapter')">添加第一个章节</button></p>
    <template v-for="(b, i) in blocks" :key="b.start + ':' + b.kind">
      <div v-if="editing === i" class="fc-block editing" :data-line="b.start"><textarea v-model="draftText" class="fc-block-input" spellcheck="false" @input="fit($event.target)" @blur="commit" @keydown="onKey" /></div>
      <header v-else-if="b.kind === 'chapter'" class="fc-block fc-chapter" :data-line="b.start" tabindex="0" @click="edit(i)" @keydown.enter.prevent="edit(i)"><span>{{ String(b.no).padStart(2, '0') }}</span><h2>{{ b.title }}</h2></header>
      <div v-else-if="b.kind === 'ref'" class="fc-block reading-entry entry-post" :data-line="b.start" tabindex="0" @click="edit(i)" @keydown.enter.prevent="edit(i)"><span class="post-tag">站内内容</span><template v-if="!refs[b.postId] || refs[b.postId].ok"><h3>{{ b.title || '文章 #' + b.postId }}</h3></template><p v-else class="fc-badref">原文已不可用（{{ refs[b.postId].reason }}）：读者只会看到“原文已不可用”，发布前请替换或删除。</p><p v-if="b.note" class="entry-annotation">{{ b.note }}</p></div>
      <div v-else class="fc-block" :class="b.kind === 'intro' ? 'fc-intro' : 'reading-entry entry-note'" :data-line="b.start" tabindex="0" @click="edit(i)" @keydown.enter.prevent="edit(i)"><SafeMarkdown class="entry-body" :source="b.source" /></div>
    </template>
    <div class="fc-append"><button type="button" class="btn btn-ghost" :disabled="disabled" @click="append('text')">+ 段落</button><button type="button" class="btn btn-ghost" :disabled="disabled" @click="append('chapter')">+ 章节</button></div>
  </div>
</template>
