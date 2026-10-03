<script setup>
import { nextTick, ref, toRaw, watch } from 'vue'
const props = defineProps({ modelValue: { type: Array, required: true }, posts: { type: Array, default: () => [] } })
defineEmits(['update:modelValue'])
const undo = ref(null), openKeys = ref(new Set()), root = ref(null)
const keys = new WeakMap(); let nextKey = 0
const key = item => { const raw = toRaw(item); if (!keys.has(raw)) keys.set(raw, `outline-${++nextKey}`); return keys.get(raw) }
watch(() => props.modelValue, sections => { if (sections.length && !openKeys.value.size) openKeys.value.add(key(sections[0])) }, { immediate: true })
const toggle = (section, event) => { if (event.target.open) openKeys.value.add(key(section)); else openKeys.value.delete(key(section)) }
const restore = () => { undo.value?.(); undo.value = null }
const move = (list, index, direction) => { const target = index + direction; if (target >= 0 && target < list.length) [list[index], list[target]] = [list[target], list[index]] }
const remove = (list, index) => { const removed = list.splice(index, 1)[0]; undo.value = () => { list.splice(Math.min(index, list.length), 0, removed); if (removed.entries) openKeys.value.add(key(removed)) } }
const focusField = async (section, entry, field) => {
  openKeys.value.add(key(section)); await nextTick()
  const panel = root.value?.querySelector(`[data-section="${key(section)}"]`)
  const row = entry ? panel?.querySelector(`[data-entry="${key(entry)}"]`) : panel
  const input = row?.querySelector(`[data-field="${field}"]`) || row?.querySelector('textarea, input, select, button')
  input?.focus(); row?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
const addSection = () => { const section = { title: '新章节', description: '', entries: [] }; props.modelValue.push(section); focusField(section, null, 'sectionTitle') }
const add = (section, kind, preset = '') => {
  const entry = { kind, title: preset, body: preset === '检查清单' ? '□ 待检查事项\n□ 待补充事项' : '', annotation: '', externalUrl: '', postId: null }
  section.entries.push(entry); focusField(section, entry, kind === 'NOTE' ? 'body' : kind === 'LINK' ? 'title' : 'postId')
}
const split = (section, index) => {
  const entry = section.entries[index]; let at = Math.floor((entry.body || '').length / 2)
  if (at && /[\uD800-\uDBFF]/.test(entry.body[at - 1])) at--
  if (!at) return
  undo.value = null; const copy = { ...entry, id: undefined, title: entry.title ? `${entry.title}（续）`.slice(0, 160) : '', body: entry.body.slice(at) }
  entry.body = entry.body.slice(0, at); section.entries.splice(index + 1, 0, copy)
}
const merge = (section, index) => {
  const a = section.entries[index], b = section.entries[index + 1]
  if (!b || a.kind !== 'NOTE' || b.kind !== 'NOTE') return
  const merged = [a.body || '', b.title || '', b.body || ''].filter(Boolean).join('\n')
  const annotation = [a.annotation, b.annotation].filter(Boolean).join('\n')
  if (merged.length > 4000 || annotation.length > 1000) { window.alert('合并后超过正文或备注长度限制，请保留为两条。'); return }
  undo.value = null; a.body = merged; a.annotation = annotation; section.entries.splice(index + 1, 1)
}
const revealIssue = issue => { const section = props.modelValue[issue.section]; if (section) focusField(section, section.entries[issue.entry], issue.field) }
defineExpose({ revealIssue })
</script>
<template>
  <div ref="root" class="outline-editor friendly-outline">
    <div class="canvas-heading"><div><h2>写下你的内容</h2><p>展开一个章节开始写；不用的章节可以删除。</p></div><button v-if="undo" class="btn btn-outline" type="button" @click="restore">撤销最近一次删除</button></div>
    <p v-if="!modelValue.length" class="empty-state">还没有章节，先添加一个开始记录。</p>
    <details v-for="(section, si) in modelValue" :key="key(section)" :data-section="key(section)" class="section-editor chapter-panel" :open="openKeys.has(key(section))" @toggle="toggle(section, $event)">
      <summary class="chapter-summary"><span class="chapter-number">{{ String(si + 1).padStart(2, '0') }}</span><strong>{{ section.title || '未命名章节' }}</strong><span class="chapter-count">{{ section.entries.length ? `${section.entries.length} 条内容` : '待填写' }}</span></summary>
      <div class="chapter-edit-body">
        <div class="section-toolbar"><span>第 {{ si + 1 }} 章</span><button type="button" :aria-label="`上移第 ${si + 1} 章`" :disabled="si === 0" @click="move(modelValue, si, -1)">↑</button><button type="button" :aria-label="`下移第 ${si + 1} 章`" :disabled="si === modelValue.length - 1" @click="move(modelValue, si, 1)">↓</button><button type="button" @click="remove(modelValue, si)">删除章节</button></div>
        <label>章节名称<input v-model="section.title" data-field="sectionTitle" maxlength="80" class="section-title-input" /></label>
        <details class="secondary-details"><summary>章节说明（可选）</summary><label>说明<textarea v-model="section.description" maxlength="500" rows="2" /></label></details>
        <div v-for="(entry, ei) in section.entries" :key="key(entry)" :data-entry="key(entry)" class="entry-editor">
          <div class="entry-toolbar"><strong>{{ { NOTE: '内容', POST: '我的文章', LINK: '参考链接' }[entry.kind] }} {{ ei + 1 }}</strong><span class="entry-spacer"></span><button type="button" :aria-label="`上移第 ${ei + 1} 条内容`" :disabled="ei === 0" @click="move(section.entries, ei, -1)">↑</button><button type="button" :aria-label="`下移第 ${ei + 1} 条内容`" :disabled="ei === section.entries.length - 1" @click="move(section.entries, ei, 1)">↓</button><button type="button" @click="remove(section.entries, ei)">删除</button></div>
          <template v-if="entry.kind === 'NOTE'"><label>正文<textarea v-model="entry.body" data-field="body" maxlength="4000" rows="6" placeholder="直接写下经验、步骤或想法。没写完也会保存。" /><small class="field-counter">{{ (entry.body || '').length }} / 4000</small></label><details class="secondary-details"><summary>小标题与内容整理（可选）</summary><label>小标题<input v-model="entry.title" data-field="title" maxlength="160" placeholder="留空也可以发布" /></label><div class="heading-actions"><button type="button" :disabled="(entry.body || '').length < 2" @click="split(section, ei)">拆成两条</button><button v-if="section.entries[ei + 1]?.kind === 'NOTE'" type="button" @click="merge(section, ei)">合并下一条</button></div></details></template>
          <template v-else-if="entry.kind === 'LINK'"><label>链接标题<input v-model="entry.title" data-field="title" maxlength="160" placeholder="这份资料叫什么" /></label><label>链接地址<input v-model="entry.externalUrl" data-field="externalUrl" maxlength="2000" placeholder="https://…" /></label></template>
          <label v-else>选择我的文章<select v-model="entry.postId" data-field="postId"><option :value="null">稍后选择文章</option><option v-for="post in posts" :key="post.id" :value="post.id">{{ post.title }}</option></select></label>
          <details class="secondary-details"><summary>来源与备注（可选）</summary><label>备注<textarea v-model="entry.annotation" maxlength="1000" rows="2" placeholder="来源、适用范围或核验日期" /></label></details>
        </div>
        <div class="entry-add"><button class="btn btn-outline" type="button" data-field="entries" @click="add(section, 'NOTE')">{{ section.entries.length ? '+ 再写一段内容' : '+ 写第一段内容' }}</button><details class="entry-more"><summary>更多内容类型</summary><div class="heading-actions"><button type="button" @click="add(section, 'NOTE', '操作步骤')">步骤</button><button type="button" @click="add(section, 'NOTE', '检查清单')">清单</button><button type="button" @click="add(section, 'LINK')">链接</button><button v-if="posts.length" type="button" @click="add(section, 'POST')">我的文章</button></div></details></div>
      </div>
    </details>
    <button class="btn btn-outline add-chapter-button" type="button" @click="addSection">+ 添加章节</button>
  </div>
</template>
