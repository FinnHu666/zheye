<!-- 原型覆盖：frontend/src/pages/CollectionEditorPage.vue。FINNS-19 R1（按用户批注修订）：整篇专题用一个 Markdown 输入框编写，
     “## 章节名”分章，左侧目录导航；可见范围保留数据、页面不展示；“编辑 / 流转过程”两个页签。保存≠发布，专题继续自动保存（约1.2秒）。 -->
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter, RouterLink } from 'vue-router'
import { api } from '@finns/lib/api'
import { createDraftSaveQueue } from '@finns/lib/draftSaveQueue'
import { publicChip, workChip } from '@finns/lib/articles'
import FlowTimeline from '@finns/components/FlowTimeline.vue'
import MdBlocksEditor from '../components/MdBlocksEditor.vue'
import { toMarkdown, fromMarkdown, outlineOf } from '../lib/collection-md'
const route = useRoute(), router = useRouter()
const draft = ref(null), info = ref(null), posts = ref([]), text = ref(''), loading = ref(true), saving = ref(false), error = ref(''), saveState = ref(''), operation = ref(false)
const notice = ref(''), noticeTone = ref('success'), dialog = ref(''), attemptedPublish = ref(false), area = ref(null)
let timer, hydrated = false, acknowledging = false, lastPublish = null, leaveAnswer = null, bypass = false
const tab = computed(() => route.query.tab === 'flow' ? 'flow' : 'edit')
const mode = computed(() => route.query.mode === 'source' ? 'source' : 'read')
const setMode = m => router.replace({ query: { ...route.query, mode: m === 'source' ? 'source' : undefined } })
const blocksEditor = ref(null)
const onBlocks = value => { text.value = value; onText() }
const setTab = t => router.replace({ query: { ...route.query, tab: t === 'flow' ? 'flow' : undefined } })
const refs = computed(() => info.value?.refs || {})
const outline = computed(() => outlineOf(text.value))
const badRefs = computed(() => {
  const out = []
  draft.value?.sections.forEach((s, si) => s.entries.forEach(e => { if (e.kind === 'POST' && (!e.postId || (refs.value[e.postId] && !refs.value[e.postId].ok))) out.push({ section: si, message: `「${s.title || '未命名章节'}」中的文章引用不可用（${e.postId ? refs.value[e.postId].reason : '没有选择文章'}），请替换或删除这一行` }) }))
  return out
})
const badChapters = computed(() => new Set(badRefs.value.map(b => b.section)))
const issues = computed(() => {
  if (!attemptedPublish.value || !draft.value) return []
  const out = []
  if (!draft.value.name.trim()) out.push({ message: '填写专题名称', target: 'name' })
  if (!draft.value.sections.length) out.push({ message: '用“## 章节名”添加至少一个章节', target: 'text' })
  draft.value.sections.forEach((s, i) => { if (!s.title.trim()) out.push({ message: `第 ${i + 1} 章：填写章节名称`, section: i }); if (!s.entries.length) out.push({ message: `「${s.title || `第 ${i + 1} 章`}」还没有内容`, section: i }) })
  return [...out, ...badRefs.value.map(b => ({ message: b.message, section: b.section }))]
})
const payload = () => JSON.parse(JSON.stringify({ revision: draft.value.revision, name: draft.value.name, description: draft.value.description, format: draft.value.format, audience: draft.value.audience, goal: draft.value.goal, visibility: draft.value.visibility, sections: draft.value.sections }))
const status = computed(() => info.value?.status)
const publishLabel = computed(() => info.value?.submitted ? '重新提交' : info.value?.publicVisible ? '更新发布' : info.value?.withdrawn ? '重新公开' : '发布专题')
const publishTitle = computed(() => info.value?.publicVisible ? '更新发布会先保存并冻结，再检测；通过前读者继续看到旧公开版' : '发布会先保存并冻结，再检测：无命中直接发布，中敏感送审，高敏感自动不通过')
const say = (t, tone = 'success') => { notice.value = t; noticeTone.value = tone }
const adoptInfo = view => { info.value = view; acknowledging = true; if (draft.value) draft.value.revision = view.revision; acknowledging = false }
const queue = createDraftSaveQueue({
  snapshot: payload,
  send: document => api(`/api/me/collections/${draft.value.id}/draft`, { method: 'PUT', body: JSON.stringify(document) }),
  acknowledge: result => { adoptInfo(result) },
  state: message => { saveState.value = message }
})
watch(draft, () => {
  if (!hydrated || acknowledging) return
  queue.changed(); clearTimeout(timer); timer = setTimeout(() => { if (!operation.value) save() }, 1200)
}, { deep: true, flush: 'sync' })
const onText = () => { const parsed = fromMarkdown(text.value); draft.value.description = parsed.description; draft.value.sections = parsed.sections }
const load = async () => {
  try {
    const view = await api(`/api/me/collections/${route.params.id}/draft`)
    draft.value = view.draft; info.value = view; text.value = toMarkdown(view.draft)
    posts.value = (await api('/api/me/posts?page=0&size=50')).items
    hydrated = true
    if (route.query.created) say('私人专题草稿已创建，尚未公开。内容没写完也可保存。')
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
const reload = async () => { if (queue.dirty) return; try { const view = await api(`/api/me/collections/${route.params.id}/draft`); info.value = view; hydrated = false; draft.value = view.draft; text.value = toMarkdown(view.draft); await nextTick(); hydrated = true; error.value = '' } catch (e) { error.value = e.message } }
const unload = event => { if (queue.dirty) { event.preventDefault(); event.returnValue = '' } }
const retry = () => { if (draft.value && lastPublish) publish(true) }
const onKey = e => { if (e.key === 'Escape' && dialog.value) { dialog.value === 'leave' ? answer('stay') : dialog.value = '' } }
onMounted(() => { load(); window.addEventListener('beforeunload', unload); window.addEventListener('finns-content-scene', reload); window.addEventListener('finns-account-session', reload); window.addEventListener('finns-content-retry', retry); window.addEventListener('keydown', onKey) })
const failure = e => { error.value = e.status === 401 ? '登录已失效。你的输入仍保留在本页，重新登录同一账号后可以继续保存或发布。' : e.message }
const save = async () => { clearTimeout(timer); saving.value = true; error.value = ''; try { await queue.flush(); return true } catch (e) { failure(e); return false } finally { saving.value = false } }
const go = async path => { if (await save()) { bypass = true; await router.push(path); bypass = false } }
const refresh = async () => { try { info.value = await api(`/api/me/collections/${draft.value.id}/draft`) } catch {} }
const jump = async offset => {
  if (tab.value !== 'edit') setTab('edit'); await nextTick()
  if (mode.value === 'read') { const line = text.value.slice(0, offset).split(String.fromCharCode(10)).length - 1; blocksEditor.value?.reveal(line); return }
  const el = area.value; if (!el) return
  el.focus(); el.setSelectionRange(offset, offset)
  const line = el.value.slice(0, offset).split('\n').length - 1; el.scrollTop = Math.max(0, line * 24 - 40)
}
const jumpSection = i => { const o = outline.value[i]; if (o) jump(o.offset) }
const addChapter = () => { if (mode.value === 'read') { blocksEditor.value?.append('chapter'); return } const base = text.value.replace(/\n*$/, '\n\n') + '## 新章节\n\n'; text.value = base; onText(); nextTick(() => jump(base.length)) }
const publish = async (again = false) => {
  attemptedPublish.value = true; notice.value = ''
  if (issues.value.length) { error.value = ''; return }
  operation.value = true; error.value = ''
  try {
    if (!await save()) return
    const requestId = again && lastPublish ? lastPublish : (globalThis.crypto?.randomUUID?.() ?? String(Date.now())); lastPublish = requestId
    const result = await api(`/api/me/collections/${draft.value.id}/publish`, { method: 'POST', body: JSON.stringify({ ...payload(), requestId }) })
    lastPublish = null; adoptInfo(result.content); attemptedPublish.value = false
    say(result.replay ? '这次提交已经处理过，没有重复发布。' + result.message : result.message, result.outcome === 'AUTO_REJECTED' ? 'danger' : result.outcome === 'PENDING' ? 'amber' : 'success')
    if (result.outcome !== 'PUBLISHED') setTab('flow')
  } catch (e) { if (e.status !== 503 && e.status !== 401) lastPublish = null; failure(e); await refresh() } finally { operation.value = false }
}
const act = async (path, body, after) => { operation.value = true; error.value = ''; try { const r = await api(`/api/me/collections/${draft.value.id}/${path}`, { method: 'POST', body: JSON.stringify(body) }); adoptInfo(r.content); say(r.message) } catch (e) { failure(e); await refresh() } finally { operation.value = false } }
const cancelSubmission = () => act('cancel', {})
const withdraw = () => { dialog.value = ''; act('withdraw', { confirm: true }) }
const copyContent = async () => { try { await navigator.clipboard.writeText(text.value); say('已复制当前内容，可保留后再处理版本冲突', 'amber') } catch { error.value = '无法访问剪贴板，请直接选择并复制编辑区内容' } }
const ask = () => new Promise(resolve => { leaveAnswer = resolve; dialog.value = 'leave' })
const answer = choice => { dialog.value = ''; const r = leaveAnswer; leaveAnswer = null; r?.(choice) }
onBeforeRouteLeave(async () => { if (bypass || !queue.dirty) return true; const c = await ask(); if (c === 'stay') return false; if (c === 'discard') return true; return await save() })
onBeforeUnmount(() => { clearTimeout(timer); window.removeEventListener('beforeunload', unload); window.removeEventListener('finns-content-scene', reload); window.removeEventListener('finns-account-session', reload); window.removeEventListener('finns-content-retry', retry); window.removeEventListener('keydown', onKey) })
const latest = computed(() => info.value?.flows?.[0] || null)
const older = computed(() => info.value?.flows?.slice(1) || [])
const locate = issue => { if (issue.section != null) jumpSection(issue.section); else if (issue.target === 'name') document.querySelector('.fc-name')?.focus(); else area.value?.focus() }
</script>
<template>
  <section class="workspace-shell fc-editor"><div v-if="loading" class="loading">正在打开专题工作台…</div><div v-else-if="!draft" class="empty-state"><p>{{ error }}</p><RouterLink to="/me/posts?type=COLLECTION">返回我的专题</RouterLink></div><template v-else>
    <header class="workspace-header"><div><RouterLink to="/me/posts?type=COLLECTION" class="text-link">← 我的专题</RouterLink><span class="rv-badge" :class="publicChip(status)[1]">{{ publicChip(status)[0] }}</span><span class="rv-badge" :class="workChip(status)[1]">{{ workChip(status)[0] }}</span></div>
      <div class="workspace-actions"><span class="save-state" role="status">{{ saveState || '已保存' }}</span><button class="btn btn-outline" :disabled="saving || operation" @click="save">保存</button><button class="btn btn-outline" :disabled="saving || operation" @click="go(`/me/collections/${draft.id}/preview`)">阅读预览</button><button v-if="info.canCancel" class="btn btn-ghost" :disabled="operation" @click="cancelSubmission">取消送审</button><button v-if="info.publicVisible" class="btn btn-ghost" :disabled="operation" @click="dialog = 'withdraw'">撤回发布</button><button class="btn btn-primary" :disabled="saving || operation" :title="publishTitle" @click="publish(false)">{{ operation ? '处理中…' : publishLabel }}</button></div></header>
    <p v-if="info.down" class="rv-notice danger" role="status">这个专题已被人工下架。保存、更新或撤回都不能解除下架，需要另一位审批者复核后恢复；你仍可继续编辑私人稿。</p>
    <p v-else-if="info.withdrawn" class="rv-notice" role="status">你已撤回这个专题，现在只有你自己可见。重新发布并通过检测后，读者的自选记录会自动恢复。</p>
    <p v-if="info.submitted && !notice" class="rv-notice amber" role="status">{{ info.submitted.kind === 'RECHECK' ? '高敏感：本次提交已自动不通过，人工复核中，复核期间不能取消，可改稿后重新提交。' : '本次提交（v' + info.submitted.version + '）已送审，等待另一位审批者；之后的修改不会替换它。' }}{{ info.publicVisible ? '读者仍看到旧公开版。' : '' }}</p>
    <p v-if="notice" class="rv-notice" :class="noticeTone" role="status">{{ notice }}</p>
    <div v-if="issues.length" class="publish-issues" role="alert"><strong>发布前还有 {{ issues.length }} 处需要处理</strong><ul><li v-for="(issue, index) in issues" :key="index"><button type="button" @click="locate(issue)">{{ issue.message }} →</button></li></ul></div>
    <p v-if="error" class="form-error workspace-error" role="alert">{{ error }} <button v-if="lastPublish" class="btn btn-outline" @click="publish(true)">重试发布（不会重复提交）</button> <button class="btn btn-outline" @click="copyContent">复制当前内容以保留</button></p>
    <div class="rv-tabs fc-tabs" role="tablist" aria-label="专题工作台"><button role="tab" :aria-selected="tab === 'edit'" :class="{ active: tab === 'edit' }" @click="setTab('edit')">编辑</button><button role="tab" :aria-selected="tab === 'flow'" :class="{ active: tab === 'flow' }" @click="setTab('flow')">流转过程<span v-if="info.flows.length" class="fc-count">{{ info.flows.length }}</span></button></div>
    <div v-if="tab === 'edit'" class="fc-body">
      <nav class="fc-toc" aria-label="专题目录"><strong>目录</strong><ol><li v-for="(o, i) in outline" :key="o.offset"><button type="button" @click="jump(o.offset)"><small>{{ String(i + 1).padStart(2, '0') }}</small>{{ o.title }}<span v-if="badChapters.has(i)" class="fc-warn" title="含失效引用">!</span></button></li></ol><button type="button" class="btn btn-outline" @click="addChapter">+ 添加章节</button></nav>
      <div class="fc-main">
        <div class="fc-namebar"><input v-model="draft.name" class="fc-name" maxlength="80" aria-label="专题名称" :disabled="operation" /><div class="fc-seg" role="group" aria-label="编辑模式"><button type="button" :class="{ on: mode === 'read' }" :aria-pressed="mode === 'read'" @click="setMode('read')">阅读模式</button><button type="button" :class="{ on: mode === 'source' }" :aria-pressed="mode === 'source'" @click="setMode('source')">源码</button></div></div>
        <MdBlocksEditor v-if="mode === 'read'" ref="blocksEditor" :model-value="text" :refs="refs" :disabled="operation" @update:model-value="onBlocks" />
        <textarea v-else ref="area" v-model="text" class="fc-md" spellcheck="false" aria-label="专题内容（Markdown）" :disabled="operation" @input="onText" />
      </div>
    </div>
    <section v-else class="rv3-flow ct-flow fc-flow" aria-label="流转过程"><p v-if="!latest" class="rv-footnote">还没有提交记录。保存草稿不会出现在这里；点击“发布”后，检测、分流和人工处理的每一步都会显示。</p><template v-else><p class="rv-footnote">最近一次提交 v{{ latest.version }}。{{ latest.level === 'HIGH' ? '高敏感内容不显示具体命中词。' : '' }}</p><FlowTimeline :flow="latest" audience="author" lit /><details v-if="older.length" class="rv-history"><summary>更早的提交（{{ older.length }}）</summary><div v-for="flow in older" :key="flow.id" class="rv3-older"><strong>提交版 v{{ flow.version }}</strong><FlowTimeline :flow="flow" audience="author" compact lit /></div></details></template></section>
    <div v-if="dialog === 'leave'" class="ct-overlay" @click.self="answer('stay')"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="fc-leave"><h2 id="fc-leave">还有没保存的修改</h2><p>离开前要怎么处理这些修改？保存只会存为私人草稿，不会公开。</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="answer('save')">保存并离开</button><button class="btn btn-ghost" @click="answer('discard')">放弃修改</button><button class="btn btn-ghost" @click="answer('stay')">继续编辑</button></div></section></div>
    <div v-if="dialog === 'withdraw'" class="ct-overlay" @click.self="dialog = ''"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="fc-withdraw"><h2 id="fc-withdraw">撤回发布？</h2><p>撤回后读者、专题广场、搜索和自选页里都看不到这个专题，待处理的提交也会作废；私人稿保留。<template v-if="info.selectedCount">目前有 {{ info.selectedCount }} 位读者自选了它：他们的记录会保留，撤回期间隐藏，你重新发布并通过检测后自动恢复。</template>重新发布会重新检测。</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="withdraw">确认撤回</button><button class="btn btn-ghost" @click="dialog = ''">先不撤回</button></div></section></div>
  </template></section>
</template>
