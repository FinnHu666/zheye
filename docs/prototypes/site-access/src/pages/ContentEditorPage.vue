<script setup>
// FINNS-18 R1：文章编辑页。区分“保存（私人）”“发布（检测并对外）”“取消送审”“撤回公开”；
// 工作稿、已提交的冻结版本和读者可见的公开版三者分开展示。
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { RouterLink, useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { api } from '@finns/lib/api'
import { sessionUser } from '../proto/session'
import FlowTimeline from '../components/FlowTimeline.vue'
import ContentShell from '../components/ContentShell.vue'
const LIMITS = { title: 120, summary: 280, body: 20000 }
const FIELDS = ['title', 'summary', 'body']
const FIELD_TEXT = { title: '标题', summary: '摘要', body: '正文' }
const route = useRoute(), router = useRouter()
const isNew = computed(() => !route.params.id)
const type = ref('ARTICLE'), doc = ref(null), form = ref({ title: '', summary: '', body: '' }), base = ref({ title: '', summary: '', body: '' })
const loading = ref(true), busy = ref(''), error = ref(''), notice = ref(''), noticeTone = ref('success'), missing = ref([]), conflict = ref(null), reader = ref(null), readerNote = ref(''), loadFailed = ref(false)
const boundAuthor = ref(null), dialog = ref(''), titleInput = ref(null)
let leaveAnswer = null, bypass = false, lastPublish = null
const count = value => [...String(value ?? '')].length
const pick = source => ({ title: source.title ?? '', summary: source.summary ?? '', body: source.body ?? '' })
const sameInput = (a, b) => FIELDS.every(k => a[k] === b[k])
const typeText = computed(() => '文章')
const dirty = computed(() => !sameInput(form.value, base.value))
const empty = computed(() => FIELDS.every(k => !form.value[k].trim()))
const over = computed(() => FIELDS.filter(k => count(form.value[k]) > LIMITS[k]))
const session = computed(() => !boundAuthor.value ? '' : !sessionUser.value ? 'expired' : sessionUser.value.id !== boundAuthor.value ? 'changed' : '')
const locked = computed(() => !!session.value || !!busy.value)
const status = computed(() => doc.value?.status)
const publishLabel = computed(() => doc.value?.submitted ? '重新提交（旧提交作废）' : doc.value?.publicVisible ? '提交更新' : doc.value?.withdrawn ? '重新公开' : '发布')
const publishHint = computed(() => doc.value?.publicVisible ? '更新会先保存当前输入并冻结，再检测；通过前读者继续看到旧版本。' : doc.value?.withdrawn ? '重新公开会重新检测，旧的审核结论不会恢复公开。' : '发布会先保存当前输入并冻结，再在本次请求中检测：无命中直接发布，中敏感送审，高敏感自动不通过并转人工复核。')
const time = value => value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : ''

function clearMessages() { error.value = ''; notice.value = ''; missing.value = []; conflict.value = null }
function say(text, tone = 'success') { notice.value = text; noticeTone.value = tone }
function setDoc(view) { doc.value = view; type.value = view.type }
function adopt(view) { setDoc(view); base.value = pick(view.draft) }
async function loadReader() {
  reader.value = null; readerNote.value = ''
  if (!doc.value) return
  try { reader.value = await api('/api/content/public/' + doc.value.id) } catch { readerNote.value = '读者现在看不到：未公开、已撤回或已被下架。' }
}
async function load() {
  loading.value = true; loadFailed.value = false; error.value = ''
  boundAuthor.value = sessionUser.value?.id ?? null
  if (isNew.value) {
    doc.value = null; type.value = 'ARTICLE'
    if (!dirty.value) { form.value = { title: '', summary: '', body: '' }; base.value = { title: '', summary: '', body: '' } }
    reader.value = null; readerNote.value = '还没有公开版本。'; loading.value = false; return
  }
  try {
    const view = await api('/api/content/mine/' + route.params.id)
    if (dirty.value && doc.value?.id === view.id) setDoc(view); else { adopt(view); form.value = pick(view.draft) }
    if (route.query.created) say(`私人${typeText.value}草稿已创建并保存（工作稿 v${view.draft.version}），尚未公开。`)
    await loadReader()
  } catch (e) { loadFailed.value = true; error.value = e.status === 401 ? '登录已失效，请重新登录后再打开。' : e.message }
  finally { loading.value = false }
}
// 刷新服务器状态但不覆盖用户正在输入的内容；若服务器稿与输入一致则视为已保存。
async function refreshDoc() {
  if (!doc.value) return null
  try { const view = await api('/api/content/mine/' + doc.value.id); if (sameInput(form.value, pick(view.draft))) adopt(view); else setDoc(view); await loadReader(); return view } catch { return null }
}
function handleError(e) {
  if (e.status === 401) error.value = '登录已失效。你的输入仍保留在本页，重新登录同一账号后可以继续保存或发布。'
  else if (e.status === 409) { error.value = e.message; refreshDoc().then(view => { if (view && !sameInput(form.value, pick(view.draft))) conflict.value = pick(view.draft) }) }
  else if (e.status === 503) { error.value = e.message; refreshDoc() }
  else error.value = e.message
}
function checkSession() {
  if (session.value === 'expired') { error.value = '登录已失效。输入已保留在本页，重新登录同一账号后继续。'; return true }
  if (session.value === 'changed') { error.value = '账号已变化。输入已保留，不能代替其他账号保存或发布；请切回原账号。'; return true }
  return false
}
function validateLocal(forPublish) {
  const bad = over.value
  if (bad.length) { error.value = `${bad.map(k => FIELD_TEXT[k]).join('、')}超过上限（${bad.map(k => `${count(form.value[k])}/${LIMITS[k]}`).join('，')}），请精简后再继续；输入已保留。`; return false }
  if (forPublish) {
    const lack = FIELDS.filter(k => !form.value[k].trim())
    if (lack.length) { missing.value = lack; error.value = `发布前请补全：${lack.map(k => FIELD_TEXT[k]).join('、')}。现在可以先保存为私人草稿。`; nextTick(() => document.querySelector(`[data-field="${lack[0]}"]`)?.focus()); return false }
  }
  return true
}

async function save({ redirect = true } = {}) {
  clearMessages()
  if (checkSession() || !validateLocal(false)) return false
  if (empty.value && !doc.value) { error.value = '标题、摘要和正文都是空的，不会创建草稿。'; return false }
  busy.value = 'save'
  try {
    if (!doc.value) {
      const created = await api('/api/content/mine', { method: 'POST', body: JSON.stringify({ type: type.value, ...form.value }) })
      adopt(created)
      if (redirect) { bypass = true; await router.replace({ path: `/me/posts/${created.id}/edit`, query: { created: 1 } }); bypass = false; return true }
    } else {
      const saved = await api('/api/content/mine/' + doc.value.id, { method: 'PUT', body: JSON.stringify({ ...form.value, expectedVersion: doc.value.draft.version }) })
      adopt(saved)
    }
    say(`私人草稿已保存（工作稿 v${doc.value.draft.version}），尚未公开${doc.value.publicVisible ? '；读者看到的仍是旧公开版' : ''}。`)
    return true
  } catch (e) { handleError(e); return false } finally { busy.value = '' }
}

async function publish(retry = false) {
  clearMessages()
  if (checkSession() || !validateLocal(true)) return
  if (!doc.value && !await save({ redirect: false })) return
  busy.value = 'publish'
  try {
    const sameAttempt = retry && lastPublish && lastPublish.docId === doc.value.id
    const requestId = sameAttempt ? lastPublish.requestId : crypto.randomUUID()
    lastPublish = { docId: doc.value.id, requestId }
    const result = await api(`/api/content/mine/${doc.value.id}/publish`, { method: 'POST', body: JSON.stringify({ ...form.value, expectedVersion: doc.value.draft.version, requestId }) })
    adopt(result.content); await loadReader()
    say(result.replay ? '这次提交已经处理过，没有重复发布。' + result.message : result.message, result.outcome === 'AUTO_REJECTED' ? 'danger' : result.outcome === 'PENDING' ? 'amber' : 'success')
    if (route.query.created) router.replace({ query: {} })
  } catch (e) { lastPublish = e.status === 503 || e.status === 401 ? lastPublish : null; handleError(e) } finally { busy.value = '' }
}
async function cancelSubmission() {
  clearMessages(); if (checkSession()) return
  busy.value = 'cancel'
  try { const r = await api(`/api/content/mine/${doc.value.id}/cancel`, { method: 'POST', body: '{}' }); setDoc(r.content); say(r.message); await loadReader() }
  catch (e) { handleError(e); await refreshDoc() } finally { busy.value = '' }
}
async function withdraw() {
  dialog.value = ''; clearMessages(); if (checkSession()) return
  busy.value = 'withdraw'
  try { const r = await api(`/api/content/mine/${doc.value.id}/withdraw`, { method: 'POST', body: JSON.stringify({ confirm: true }) }); setDoc(r.content); say(r.message); await loadReader() }
  catch (e) { handleError(e); await refreshDoc() } finally { busy.value = '' }
}
function takeServerVersion() { form.value = { ...conflict.value }; base.value = { ...conflict.value }; conflict.value = null; say('已载入别处保存的版本，你刚才的输入已放弃。', 'amber') }
async function preview() {
  clearMessages()
  if (!doc.value || dirty.value) { if (empty.value && !doc.value) { error.value = '还没有内容可以预览。'; return } if (!await save({ redirect: false })) return }
  bypass = true; await router.push(`/me/posts/${doc.value.id}/preview`); bypass = false
}

// 页面内离开保护：保存并离开 / 放弃修改 / 继续编辑；保存失败时不离开。
const ask = () => new Promise(resolve => { leaveAnswer = resolve; dialog.value = 'leave' })
function answer(choice) { dialog.value = ''; const resolve = leaveAnswer; leaveAnswer = null; resolve?.(choice) }
onBeforeRouteLeave(async () => {
  if (bypass || !dirty.value) return true
  const choice = await ask()
  if (choice === 'stay') return false
  if (choice === 'discard') return true
  return await save({ redirect: false })
})
const beforeUnload = e => { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
const onScene = () => { if (!dirty.value) load() }
const onSession = () => { if (!dirty.value) load(); else if (!sessionUser.value) checkSession() }
const retry = () => { if (doc.value) publish(true) }
const onKey = e => { if (e.key === 'Escape' && dialog.value) { dialog.value === 'leave' ? answer('stay') : dialog.value = '' } }
watch(() => [route.params.id, route.query.type], () => { if (!dirty.value) load() })
onMounted(() => { load(); window.addEventListener('beforeunload', beforeUnload); window.addEventListener('finns-content-scene', onScene); window.addEventListener('finns-account-session', onSession); window.addEventListener('finns-content-retry', retry); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('finns-content-scene', onScene); window.removeEventListener('finns-account-session', onSession); window.removeEventListener('finns-content-retry', retry); window.removeEventListener('keydown', onKey) })
const latest = computed(() => doc.value?.flows?.[0] || null)
const older = computed(() => doc.value?.flows?.slice(1) || [])
</script>
<template>
  <ContentShell wide kicker="MY CONTENT" :title="isNew ? `新建${typeText}` : (doc?.draft.title || '未命名')" :desc="`${typeText}的私人草稿、已提交版本与公开版分开管理；保存不会公开。`">
    <template #actions><RouterLink class="btn btn-ghost" :to="'/me/posts'">← 返回列表</RouterLink><RouterLink v-if="doc" class="btn btn-outline" :to="`/me/posts/${doc.id}/preview`">私人预览</RouterLink></template>
    <div v-if="loading" class="rv-empty" role="status"><span class="rv-spinner"></span><h2>正在读取{{ typeText }}</h2></div>
    <div v-else-if="loadFailed" class="rv-empty"><h2>无法打开这条内容</h2><p role="alert">{{ error }}</p><RouterLink class="btn btn-primary" to="/me/posts">回到我的内容</RouterLink></div>
    <template v-else>
      <header class="ct-head">
        <div v-if="status" class="rv-tags" aria-label="当前状态"><span class="rv-badge" :class="status.publicState === 'LIVE' ? 'green' : status.publicState === 'DOWN' ? 'danger' : 'neutral'">{{ status.publicText }}</span><span class="rv-badge" :class="status.tone">{{ status.workText }}</span></div>
        <div v-else class="rv-tags"><span class="rv-badge neutral">尚未保存</span></div>
      </header>
      <p v-if="session" class="rv-notice amber" role="alert">{{ session === 'expired' ? '登录已失效。' : '账号已变化。' }}输入已保留，不会代存或代发；{{ session === 'expired' ? '重新登录同一账号后继续。' : '请切回原账号。' }}</p>
      <p v-if="doc?.down" class="rv-notice danger" role="status">这条内容已被人工下架。保存、更新或撤回都不能解除下架，需要另一位审批者复核后恢复；你仍可继续编辑私人稿。</p>
      <p v-else-if="doc?.withdrawn" class="rv-notice" role="status">你已撤回这条内容，现在只有你自己可见。重新公开会重新检测。</p>
      <p v-if="notice" class="rv-notice" :class="noticeTone" role="status">{{ notice }}</p>
      <p v-if="error" class="rv-notice danger" role="alert">{{ error }}</p>
      <div v-if="conflict" class="rv-notice amber" role="alert">别处保存了不同的版本。你的输入仍在下面；再次保存将以你的输入为准。<button class="text-link" @click="takeServerVersion">改为载入别处的版本（放弃我的输入）</button></div>
      <div class="ct-grid">
        <form class="rv-editor ct-form" novalidate @submit.prevent="publish(false)">
          <div class="rv-editor-top"><span class="rv-badge">{{ typeText }}</span><span>{{ doc ? `工作稿 v${doc.draft.version}` : '新稿' }} · {{ dirty ? '有未保存的修改' : doc ? '已保存' : '未保存' }}</span></div>
          <label class="rv-field" :class="{ bad: missing.includes('title') || over.includes('title') }">标题<input ref="titleInput" v-model="form.title" data-field="title" :aria-invalid="missing.includes('title') || over.includes('title')" autocomplete="off" /><span class="ct-count" :class="{ over: over.includes('title') }">{{ count(form.title) }}/{{ LIMITS.title }}</span></label>
          <label class="rv-field" :class="{ bad: missing.includes('summary') || over.includes('summary') }">摘要<textarea v-model="form.summary" data-field="summary" rows="3" :aria-invalid="missing.includes('summary') || over.includes('summary')" /><span class="ct-count" :class="{ over: over.includes('summary') }">{{ count(form.summary) }}/{{ LIMITS.summary }}</span></label>
          <label class="rv-field" :class="{ bad: missing.includes('body') || over.includes('body') }">正文<textarea v-model="form.body" data-field="body" rows="14" :aria-invalid="missing.includes('body') || over.includes('body')" /><span class="ct-count" :class="{ over: over.includes('body') }">{{ count(form.body) }}/{{ LIMITS.body }}</span></label>
          <p class="rv-footnote">支持安全 Markdown：<code># 标题</code>、<code>- 列表</code>、<code>**粗体**</code>、<code>`代码`</code>、<code>[链接](https://…)</code>。HTML、脚本和图片不会生效。草稿可以缺字段；发布时标题、摘要、正文都必填。上限按字符数计算，具体计量规则在设计阶段确认。</p>
          <div class="rv-editor-actions ct-actions">
            <button type="button" class="btn btn-ghost" :disabled="locked || (empty && !doc)" @click="save()">{{ busy === 'save' ? '保存中…' : '保存草稿' }}</button>
            <button type="button" class="btn btn-ghost" :disabled="locked || (empty && !doc)" @click="preview">私人预览</button>
            <button class="btn btn-primary" :disabled="locked">{{ busy === 'publish' ? '处理中…' : publishLabel }}</button>
          </div>
          <p class="ct-hint">保存只是私人存稿，不会公开也不会送审。{{ publishHint }}</p>
          <div v-if="doc && (doc.canCancel || doc.publicVisible)" class="ct-danger-row">
            <button v-if="doc.canCancel" type="button" class="text-link" :disabled="locked" @click="cancelSubmission">取消本次送审（保留工作稿和旧公开版）</button>
            <button v-if="doc.publicVisible" type="button" class="text-link danger" :disabled="locked" @click="dialog = 'withdraw'">撤回公开（保留私人稿）</button>
          </div>
        </form>
        <aside class="ct-side" aria-label="版本与流转">
          <section class="ct-versions">
            <h2>三个版本</h2>
            <article class="ct-version" :class="{ on: reader }"><small>读者现在看到</small><template v-if="reader"><strong>公开版 v{{ reader.version }}</strong><p>{{ reader.title }}</p></template><template v-else><strong>读者看不到</strong><p>{{ readerNote || '还没有公开版本。' }}</p></template></article>
            <article class="ct-version" :class="{ on: doc?.submitted }"><small>已提交 · 等待处理</small><template v-if="doc?.submitted"><strong>提交版 v{{ doc.submitted.version }}</strong><p>{{ doc.submitted.title }}</p><em>{{ doc.submitted.kind === 'RECHECK' ? '高敏感：已自动不通过，人工复核中。' : '中敏感：已送审，等待另一位审批者。' }}之后的修改不会替换它，重新提交才会使它作废。</em></template><template v-else><strong>没有待处理的提交</strong><p>保存、继续编辑都不会产生提交。</p></template></article>
            <article class="ct-version on"><small>我的工作稿</small><strong>工作稿 {{ doc ? `v${doc.draft.version}` : '（未保存）' }}</strong><p>{{ dirty ? '输入里有尚未保存的修改。' : doc ? '输入与已保存的草稿一致。' : '保存后才会出现在“我的内容”。' }}</p></article>
          </section>
          <section class="rv3-flow ct-flow" aria-label="流转过程"><div class="rv3-flow-head"><h2>流转过程</h2></div>
            <p v-if="!latest" class="rv-footnote">还没有提交记录。保存草稿不会出现在这里；点击“发布”后，检测、分流和人工处理的每一步都会显示。</p>
            <template v-else><p class="rv-footnote">最近一次提交 v{{ latest.version }}。{{ latest.level === 'HIGH' ? '高敏感内容不显示具体命中词。' : '' }}</p><FlowTimeline :flow="latest" audience="author" lit /><details v-if="older.length" class="rv-history"><summary>更早的提交（{{ older.length }}）</summary><div v-for="flow in older" :key="flow.id" class="rv3-older"><strong>提交版 v{{ flow.version }}</strong><FlowTimeline :flow="flow" audience="author" compact lit /></div></details></template>
          </section>
        </aside>
      </div>
    </template>
    <div v-if="dialog === 'leave'" class="ct-overlay" @click.self="answer('stay')"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-leave-title"><h2 id="ct-leave-title">还有没保存的修改</h2><p>离开前要怎么处理这些修改？保存只会存为私人草稿，不会公开。</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="answer('save')">保存并离开</button><button class="btn btn-ghost" @click="answer('discard')">放弃修改</button><button class="btn btn-ghost" @click="answer('stay')">继续编辑</button></div></section></div>
    <div v-if="dialog === 'withdraw'" class="ct-overlay" @click.self="dialog = ''"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-withdraw-title"><h2 id="ct-withdraw-title">撤回公开？</h2><p>撤回后读者、列表、搜索和收藏里都看不到这条内容，待处理的提交也会作废；私人稿会保留，之后重新发布会重新检测。</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="withdraw">确认撤回</button><button class="btn btn-ghost" @click="dialog = ''">先不撤回</button></div></section></div>
  </ContentShell>
</template>
