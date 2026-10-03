<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import CollectionOutlineEditor from '../components/CollectionOutlineEditor.vue'
import { api } from '../lib/api'
import { importReadiness } from '../lib/collectionDraft'
const route = useRoute(), router = useRouter()
const text = ref(''), parsed = ref(null), warnings = ref([]), error = ref(''), busy = ref(false), saved = ref(false)
const target = ref(null), parsedSource = ref(''), parseChoice = ref(null)
const readiness = computed(() => importReadiness(parsed.value, text.value, parsedSource.value, target.value))
let submission = null
const appendId = computed(() => /^\d+$/.test(String(route.query.append || '')) ? String(route.query.append) : '')
const sample = '# 我的租房经验\n先明确预算和通勤要求。\n\n## 看房检查\n白天查看采光，晚上留意噪音。\n记录水电设施的使用情况。\n\n## 入住复盘\n记下哪些判断准确，哪些地方下次要改进。'
const readFile = async (event) => {
  const file = event.target.files[0]; if (!file) return
  error.value = ''
  try {
    if (!/\.(txt|md|markdown)$/i.test(file.name)) throw new Error('请选择 TXT 或 Markdown 文件')
    if (file.size > 1048576) throw new Error('单次文件最多 1 MiB，请分批导入')
    text.value = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()); parsed.value = null
  } catch (e) { error.value = e instanceof TypeError ? '无法读取文字，请将文件保存为 UTF-8 格式后重试。' : e.message } finally { event.target.value = '' }
}
const parse = async (preserveWhole = false, confirmed = false) => {
  if (busy.value) return
  if (parsed.value && !confirmed) { parseChoice.value = preserveWhole; return }
  parseChoice.value = null
  busy.value = true; error.value = ''; submission = null
  const source = text.value
  try {
    if (appendId.value) target.value = await api(`/api/me/collections/${appendId.value}/draft`)
    const result = await api('/api/me/collection-imports/preview', { method: 'POST', body: JSON.stringify({ text: source, preserveWhole }) })
    parsed.value = result.document; parsedSource.value = source; warnings.value = result.warnings.filter(w => !w.startsWith('超过 50'))
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
const commit = async () => {
  if (busy.value || !readiness.value.canSave) return
  busy.value = true; error.value = ''
  try {
    const document = JSON.parse(JSON.stringify(parsed.value)); document.revision = target.value?.revision || 0
    const serialized = JSON.stringify(document)
    if (!submission || submission.serialized !== serialized) submission = { serialized, idempotencyKey: crypto.randomUUID() }
    const result = await api(appendId.value ? `/api/me/collections/${appendId.value}/imports` : '/api/me/collection-imports/commit', { method: 'POST', body: JSON.stringify({ idempotencyKey: submission.idempotencyKey, document }) })
    saved.value = true; text.value = ''; await router.push(`/me/collections/${result.id}/edit`)
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
const refreshTarget = async () => { try { target.value = await api(`/api/me/collections/${appendId.value}/draft`); submission = null; error.value = '已读取最新版本；下面的整理内容仍然保留，请检查后再次确认追加。' } catch (e) { error.value = e.message } }
const count = computed(() => parsed.value?.sections.reduce((n, s) => n + s.entries.length, 0) || 0)
const leave = () => saved.value || !text.value || window.confirm('导入尚未完成，离开会清除本次原文和整理结果。请先复制需要保留的内容。')
onBeforeRouteLeave(leave)
const unload = event => { if (!saved.value && text.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload); onBeforeUnmount(() => window.removeEventListener('beforeunload', unload))
</script>
<template>
  <section class="collection-create-page import-create-page">
    <header class="collection-compact-heading"><RouterLink to="/me/collections/new" class="text-link">← 返回创建入口</RouterLink><h1>{{ appendId ? '补充已有专题' : '导入现成内容' }}</h1><p>粘贴文字或选择文件，检查整理结果，再保存为私人工作稿。</p></header>
    <ol class="import-steps" aria-label="导入步骤"><li :class="{ current: !parsed }">1. 放入资料</li><li :class="{ current: parsed }">2. 检查内容</li><li>3. 保存专题</li></ol>
    <details class="import-source-panel" :open="!parsed"><summary>{{ parsed ? '查看或修改原始资料' : '准备你的资料' }}</summary><fieldset :disabled="busy"><label class="import-source">你的资料<textarea v-model="text" rows="8" placeholder="粘贴笔记、文章文字或聊天记录，不需要整理格式…" /></label><div class="heading-actions"><label class="btn btn-outline">选择 TXT / Markdown<input type="file" accept=".txt,.md,.markdown" @change="readFile" /></label><button class="btn btn-ghost" type="button" @click="text = sample; parsed = null">试用租房示例</button></div><p class="field-help">UTF-8 文字文件，单次最多 1 MiB。图片、表格和 HTML 仅保留文字。</p><div class="heading-actions"><button class="btn btn-primary" :disabled="busy || !text.trim()" @click="parse(false)">按标题和段落整理</button><button class="btn btn-outline" :disabled="busy || !text.trim()" @click="parse(true)">按原顺序保留整篇</button></div></fieldset></details>
    <p v-if="error" class="form-error" role="alert">{{ error }}<button v-if="appendId && parsed" :disabled="busy" @click="refreshTarget">读取最新专题版本（整理内容保留）</button></p>
    <div v-if="parseChoice !== null" class="import-readiness" role="alert"><p>重新整理会替换下面的编辑结果，原文仍会保留。</p><div class="heading-actions"><button class="btn btn-outline" :disabled="busy" @click="parseChoice = null">保留当前结果</button><button class="btn btn-primary" :disabled="busy" @click="parse(parseChoice, true)">确认重新整理</button></div></div>
    <template v-if="parsed">
      <div class="import-summary"><h2>检查整理结果</h2><p v-if="target">追加到「{{ target.name }}」，已有内容保留。</p><p>{{ parsed.sections.length }} 个章节 · {{ count }} 条内容。修改下面的文字或目录即可。</p><details v-if="warnings.length"><summary>整理说明（{{ warnings.length }}）</summary><p v-for="warning in warnings" :key="warning">{{ warning }}</p></details></div>
      <p v-if="readiness.message" class="import-readiness" role="alert">{{ readiness.message }}<button v-if="text !== parsedSource" class="text-link" :disabled="busy" @click="parse(false)">重新整理 →</button></p>
      <fieldset class="import-preview-fields" :disabled="busy"><label v-if="!appendId">专题名称<input v-model="parsed.name" maxlength="80" /></label><details v-if="!appendId" class="secondary-details"><summary>简介与目标（可选）</summary><label>简介<textarea v-model="parsed.description" maxlength="260" rows="2" /></label><label>适合谁<input v-model="parsed.audience" maxlength="160" /></label><label>完成目标<textarea v-model="parsed.goal" maxlength="300" rows="2" /></label></details><CollectionOutlineEditor v-model="parsed.sections" /></fieldset>
      <div class="import-save-bar"><span>内容可继续编辑，保存不会自动发布。</span><button class="btn btn-primary" :disabled="busy || !readiness.canSave" @click="commit">{{ busy ? '正在保存…' : target ? '确认追加到工作稿 →' : '保存并继续编辑 →' }}</button></div>
    </template>
  </section>
</template>
