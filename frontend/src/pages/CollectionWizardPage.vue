<script setup>
// 创建专题向导：基础信息 → 内容编排 → 预览发布。
// /me/collections/new 是尚未保存的第一步；点“保存并继续”创建私人草稿后，进入 /me/collections/:id/compose/:step。
// 保存、发布沿用既有专题工作稿接口；仅前端占位的“引子”字段见 lib/collectionWizard.js。
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api'
import { can } from '../lib/session'
import { createDraftSaveQueue } from '../lib/draftSaveQueue'
import { STEPS, applyLayouts, basicIssues, clampLayout, collectLayouts, defaultExtras, loadExtras, loadLayouts, parseAudience, CATEGORIES, CATEGORY_KEYS, saveExtras, saveLayouts, wizardIssues } from '../lib/collectionWizard'
import { useSessionGuard } from '../lib/sessionGuard'
import { outcomeNotice, submitNotice } from '../lib/reviewText'
import FlowTimeline from '../components/FlowTimeline.vue'
import ReauthPanel from '../components/ReauthPanel.vue'
import StepBasic from '../components/collection-wizard/StepBasic.vue'
import StepContent from '../components/collection-wizard/StepContent.vue'
import StepPublish from '../components/collection-wizard/StepPublish.vue'

const route = useRoute(), router = useRouter()
const guard = useSessionGuard()
const blankDraft = () => ({ id: null, revision: 0, name: '', description: '', format: 'CUSTOM', audience: '', goal: '', visibility: 'PUBLIC', publishedVisibility: null, status: 'DRAFT', sections: [] })
const draft = ref(blankDraft()), extras = reactive(defaultExtras())
const posts = ref([]), activity = ref([]), flows = ref([]), capabilities = ref({ schedulingEnabled: false, layoutsEnabled: false })
const loading = ref(!!route.params.id), busy = ref(false), operation = ref(false), error = ref(''), notice = ref(''), saveState = ref(''), savedAt = ref('')
const attempted = ref(false), focusIssue = ref(null), published = ref(null), toast = ref('')
const basicRef = ref(null)
let timer, toastTimer, hydrated = false, acknowledging = false, postsLoaded = false, lastKey = ''

const step = computed(() => STEPS.some(s => s.key === route.params.step) ? route.params.step : 'basic')
const stepIndex = computed(() => STEPS.findIndex(s => s.key === step.value))
const isNew = computed(() => !draft.value.id)
const canRestrict = computed(() => can('content.restricted'))
const issues = computed(() => wizardIssues(draft.value))
const clock = () => new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })

// 画布布局（entry.layout）只暂存在本浏览器，不随保存请求发送
const metadata = () => ({ tags: extras.tags, category: CATEGORY_KEYS[CATEGORIES.indexOf(extras.category)] || '', seoTitle: extras.seoTitle, audiences: draft.value.metadata?.audiences || parseAudience(draft.value.audience), coverId: extras.coverId || null, sectionLayouts: draft.value.sections.map(s => s.displayLayout || 'LIST') })
const payload = () => JSON.parse(JSON.stringify({ revision: draft.value.revision, name: draft.value.name, description: draft.value.description, format: draft.value.format, audience: draft.value.audience, goal: draft.value.goal, visibility: draft.value.visibility, sections: draft.value.sections, metadata: metadata() }, (k,v) => k === 'layout' || k === 'displayLayout' ? undefined : v))
const docKey = () => { const p = payload(); delete p.revision; return JSON.stringify(p) }
const queue = createDraftSaveQueue({
  snapshot: payload,
  send: async document => { if (!await guard.beforeSubmit()) throw Object.assign(new Error('请先恢复原账号登录，输入已保留'),{status:401}); return api(`/api/me/collections/${draft.value.id}/draft`, { method: 'PUT', body: JSON.stringify(document) }).catch(e => { guard.handle(e); throw e }) },
  acknowledge: result => { acknowledging = true; draft.value.revision = result.revision; draft.value.updatedAt = result.updatedAt; acknowledging = false; savedAt.value = clock() },
  state: message => { saveState.value = message }
})
const setDraft = document => {
  const saved = loadLayouts(document.id); applyLayouts(document.sections, saved)
  hydrated = false; draft.value = document
  const m = document.metadata || {}; document.metadata = { ...m, audiences: m.audiences?.length ? m.audiences : parseAudience(document.audience) }; Object.assign(extras, defaultExtras(), { tags: m.tags || [], category: CATEGORIES[CATEGORY_KEYS.indexOf(m.category)] || '', seoTitle: m.seoTitle || '', coverId: m.coverId || null, cover: m.coverId ? `/api/collection-covers/${m.coverId}` : '', layoutsEnabled: capabilities.value.layoutsEnabled }); document.sections.forEach((s,i) => { s.displayLayout = m.sectionLayouts?.[i] || 'LIST' }); extras.introLayout = saved?.intro ? clampLayout(saved.intro) : null
  lastKey = docKey(); hydrated = true
}
watch(draft, () => {
  if (!hydrated || acknowledging || !draft.value.id) return
  const key = docKey(); if (key === lastKey) return // 只有布局变化时不触发保存
  lastKey = key
  queue.changed(); clearTimeout(timer); timer = setTimeout(() => { if (!operation.value) save() }, 1200)
}, { deep: true, flush: 'sync' })
watch(() => JSON.stringify(metadata()), () => { if (draft.value.id && hydrated) { queue.changed(); clearTimeout(timer); timer = setTimeout(() => { if (!operation.value && !busy.value) save() },1200) } }, { flush: 'sync' })
watch(() => extras.coverFile, file => { if(file && draft.value.id && hydrated) { queue.changed(); clearTimeout(timer); timer=setTimeout(() => { if(!operation.value && !busy.value) save() },1200) } }, { flush:'sync' })
watch(() => JSON.stringify(collectLayouts(draft.value.sections, extras.introLayout)), text => { if (draft.value.id && hydrated) saveLayouts(draft.value.id, JSON.parse(text)) })

const stub = message => { toast.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toast.value = '' }, 4000) }
const uploadCover = async () => {
  if (!extras.coverFile || !draft.value.id) return
  const data = new FormData(); data.append('file', extras.coverFile)
  const result = await api(`/api/me/collections/${draft.value.id}/cover`, { method: 'POST', body: data })
  if (extras.cover?.startsWith('blob:')) URL.revokeObjectURL(extras.cover)
  extras.coverId = result.coverId; extras.cover = result.url; extras.coverFile = null; queue.changed()
}
const save = async () => {
  if (!draft.value.id) return true
  if (!await guard.beforeSubmit()) { error.value='请先恢复原账号登录，输入已保留';return false }
  clearTimeout(timer); error.value = ''
  try { await uploadCover(); await queue.flush(); return true } catch (e) { error.value = e.message; guard.handle(e); return false }
}
const loadDraft = async id => {
  loading.value = true; error.value = ''
  try { setDraft(await api(`/api/me/collections/${id}/draft`)) } catch (e) { error.value = e.message } finally { loading.value = false }
}
const loadPosts = async () => {
  if (postsLoaded) return
  postsLoaded = true
  try {
    let page = 0, more = true; const all = []
    while (more) { const mine = await api(`/api/me/posts?page=${page++}&size=50`); all.push(...mine.items); more = mine.hasMore }
    posts.value = all
  } catch { postsLoaded = false }
}
const loadFlows = async () => { if (draft.value.id) try { const data = await api(`/api/review/mine/content?type=COLLECTION&ids=${draft.value.id}`); flows.value = data.items?.[draft.value.id] || []; activity.value = await api(`/api/me/collections/${draft.value.id}/activity`) } catch { flows.value = [] } }
const init = async () => {
  try { capabilities.value = await api('/api/me/collections/capabilities') } catch { /* 未配置的实例保持关闭 */ }
  if (route.params.id) { if (String(draft.value.id) !== String(route.params.id)) await loadDraft(route.params.id) } else hydrated = false
  if (step.value === 'content' || step.value === 'publish') loadPosts(); loadFlows()
}
onMounted(init)
watch(() => [route.params.id, route.params.step], init)

const go = async key => { if (!draft.value.id) return; await router.push(`/me/collections/${draft.value.id}/compose/${key}`) }
const create = async () => {
  if (!await guard.beforeSubmit()) { error.value='请先恢复原账号登录，输入已保留';return }
  const doc = await api('/api/me/collections/drafts', { method: 'POST', body: JSON.stringify({ name: draft.value.name.trim(), description: draft.value.description.trim(), format: 'CUSTOM', audience: draft.value.audience, goal: draft.value.goal, templateKey: 'BLANK', initialContent: '' }) })
  const pending = { tags: extras.tags.slice(), category: extras.category, seoTitle: extras.seoTitle, coverFile: extras.coverFile, cover: extras.cover }
  setDraft(doc); Object.assign(extras, pending); queue.changed(); if (!await save()) return
  await router.replace(`/me/collections/${doc.id}/compose/content`)
}
const next = async () => {
  if (busy.value) return
  error.value = ''; notice.value = ''
  if (step.value === 'basic') {
    attempted.value = true
    const missing = basicIssues(draft.value)
    if (missing.length) { error.value = '请先完成标有 * 的必填项。'; await nextTick(); basicRef.value?.focusField(missing[0].field); return }
  }
  busy.value = true
  try {
    if (isNew.value) await create()
    else if (await save()) await go(STEPS[stepIndex.value + 1].key)
  } catch (e) { error.value = e.message; guard.handle(e) } finally { busy.value = false }
}
const back = async () => {
  if (step.value === 'basic' || isNew.value) { await router.push('/me/posts?type=COLLECTION'); return }
  await go(STEPS[stepIndex.value - 1].key)
}
const saveNow = async () => { if (await save()) notice.value = '草稿已保存' }
const appendImport = async () => { if(draft.value.id && await save()) await router.push({path:'/me/collections/import',query:{append:draft.value.id}}) }
const jump = async key => { if (key !== step.value && draft.value.id && await save()) await go(key) }
const goto = async issue => {
  focusIssue.value = { ...issue }
  if (issue.step !== step.value) await go(issue.step)
  await nextTick()
  if (issue.step === 'basic') basicRef.value?.focusField(issue.field)
}
const publish = async () => {
  attempted.value = true; notice.value = ''
  if (extras.scheduled && !extras.scheduledTime) { error.value = '请选择定时公开的日期和时间。'; return }
  if (issues.value.length) { error.value = ''; await goto(issues.value[0]); return }
  operation.value = true; error.value = ''
  try {
    if (!await save()) return
    const result = await api(`/api/me/collections/${draft.value.id}/publish`, { method: 'POST', body: JSON.stringify({ revision: draft.value.revision, requestId: globalThis.crypto?.randomUUID?.() ?? String(Date.now()), scheduledAt: extras.scheduled && extras.scheduledTime ? new Date(extras.scheduledTime).toISOString() : null }) })
    loadFlows(); if (result?.scheduledAt) { draft.value.scheduledAt = result.scheduledAt; notice.value = '已通过检测，等待定时公开；到点只发布本次提交版本。'; return }
    if (result?.outcome === 'PENDING' || result?.outcome === 'AUTO_REJECTED') { notice.value = outcomeNotice(result); return }
    acknowledging = true; draft.value.revision = result.revision; draft.value.status = result.status; draft.value.publishedAt = result.publishedAt; draft.value.publishedVisibility = result.publishedVisibility; acknowledging = false
    published.value = { id: draft.value.id }
  } catch (e) { error.value = submitNotice(e); guard.handle(e) } finally { operation.value = false }
}

const lifecycle = async action => {
  if (action === 'archive' && !window.confirm('撤回后读者暂时无法访问，自选记录保留，重新发布后恢复。确定撤回发布吗？')) return
  if (action === 'delete' && !window.confirm('确认永久删除从未公开的私人草稿？此操作不可恢复。')) return
  operation.value=true;error.value=''
  try { if (!await save()) return; if (action==='delete') { await api(`/api/me/collections/${draft.value.id}/draft?revision=${draft.value.revision}`,{method:'DELETE'}); await router.push('/me/posts?type=COLLECTION') } else { setDraft(await api(`/api/me/collections/${draft.value.id}/${action}`,{method:'POST',body:JSON.stringify({revision:draft.value.revision})})); notice.value=action==='archive'?'已撤回发布，自选记录已隐藏并保留。':'已取消本次送审或定时发布。';await loadFlows() } } catch(e) {error.value=e.message;guard.handle(e)} finally {operation.value=false}
}
const dirtyNew = () => isNew.value && (draft.value.name || draft.value.description || draft.value.audience || draft.value.goal || extras.coverFile || extras.tags.length || extras.category || extras.seoTitle)
onBeforeRouteLeave(async () => {
  if (isNew.value) return !dirtyNew() || window.confirm('还没有创建草稿，离开会清除填写内容。确定离开吗？')
  return !queue.dirty || await save() || window.confirm('保存未成功。确认离开会丢失未保存的修改。')
})
const unload = event => { if (queue.dirty || dirtyNew()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { clearTimeout(timer); clearTimeout(toastTimer); window.removeEventListener('beforeunload', unload); if (extras.cover) URL.revokeObjectURL(extras.cover) })
const saveLabel = computed(() => isNew.value ? '尚未创建草稿' : saveState.value === '已保存' || !saveState.value ? (savedAt.value ? `已自动保存 ${savedAt.value}` : '已保存') : saveState.value)
</script>

<template>
  <section class="cw-shell"><ReauthPanel :guard="guard" />
    <div v-if="loading" class="loading cw-loading">正在打开专题创建向导…</div>
    <div v-else-if="route.params.id && !draft.id" class="empty-state"><p>{{ error || '没有找到这个专题。' }}</p><RouterLink to="/me/posts?type=COLLECTION">返回我的专题</RouterLink></div>
    <template v-else>
      <header v-if="step === 'content'" class="cw-header cw-header-builder">
        <div class="cw-hb-left"><button type="button" class="cw-back" aria-label="上一步" @click="back"><span aria-hidden="true">‹</span></button><h1>创建专题</h1><span class="cw-hb-title cw-input-wrap"><input v-model="draft.name" maxlength="80" aria-label="专题标题" placeholder="专题标题" /><span class="cw-count">{{ draft.name.length }}/80</span></span></div>
        <ol class="cw-steps" aria-label="创建进度"><template v-for="(s, i) in STEPS" :key="s.key"><li v-if="i" class="cw-step-line" aria-hidden="true"></li><li><button type="button" class="cw-step" :class="{ current: s.key === step, done: i < stepIndex }" :aria-current="s.key === step ? 'step' : null" :disabled="isNew && s.key !== 'basic'" @click="jump(s.key)"><span>{{ i + 1 }}</span>{{ s.label }}</button></li></template></ol>
        <div class="cw-header-actions"><span class="cw-save-state" role="status">{{ saveLabel }}</span><button type="button" class="btn btn-outline" :disabled="busy || operation" @click="saveNow">保存草稿</button><button type="button" class="btn btn-outline" :disabled="busy || operation" @click="next">预览</button><button type="button" class="btn btn-primary" :disabled="busy || operation" @click="publish">{{ draft.status === 'PUBLISHED' ? '更新发布' : '发布专题' }}</button></div>
      </header>
      <header v-else class="cw-header">
        <button type="button" class="cw-back" @click="back"><span aria-hidden="true">‹</span>{{ step === 'basic' || isNew ? '返回' : step === 'publish' ? '返回编辑' : '上一步' }}</button>
        <ol class="cw-steps" aria-label="创建进度"><template v-for="(s, i) in STEPS" :key="s.key"><li v-if="i" class="cw-step-line" aria-hidden="true"></li><li><button type="button" class="cw-step" :class="{ current: s.key === step, done: i < stepIndex }" :aria-current="s.key === step ? 'step' : null" :disabled="isNew && s.key !== 'basic'" @click="jump(s.key)"><span>{{ i + 1 }}</span>{{ s.label }}</button></li></template></ol>
        <div class="cw-header-actions"><span class="cw-save-state" role="status">{{ saveLabel }}</span>
          <button v-if="step === 'content'" type="button" class="btn btn-outline" :disabled="busy || operation" @click="saveNow">保存草稿</button>
          <button v-if="step !== 'publish'" type="button" class="btn btn-primary" :disabled="busy || operation" @click="next">{{ busy ? '正在保存…' : step === 'basic' ? '保存并继续' : '下一步：预览发布' }} <span aria-hidden="true">→</span></button></div>
      </header>

      <p v-if="published" class="cw-banner ok" role="status">{{ draft.publishedVisibility === 'RESTRICTED' ? '已发布（受限）' : '已发布' }}，公开版本已更新。<RouterLink :to="`/collections/${published.id}`">查看公开专题 →</RouterLink><RouterLink to="/me/posts?type=COLLECTION">返回我的专题</RouterLink></p>
      <p v-else-if="notice" class="cw-banner" role="status">{{ notice }}</p>
      <p v-if="error" class="cw-banner error" role="alert">{{ error }}</p>

      <div v-if="draft.id" class="cw-banner collection-lifecycle">
        <span>工作稿 v{{ draft.revision }} · {{ draft.status === 'PUBLISHED' ? '读者仍看到上次发布版，更新发布后替换' : draft.status === 'ARCHIVED' ? '已撤回，私人稿保留' : '私人草稿' }}</span>
        <span v-if="draft.scheduledAt">待定时公开：{{ new Date(draft.scheduledAt).toLocaleString() }}；继续改稿不替换已提交版本。</span>
        <button v-if="draft.status === 'PUBLISHED'" type="button" :disabled="operation" @click="lifecycle('archive')">撤回发布</button>
        <button v-if="draft.scheduledAt || flows.some(f => f.taskId && f.status !== 'AUTO_REJECTED')" type="button" :disabled="operation" @click="lifecycle('cancel')">取消本次送审 / 定时</button>
        <button v-if="!draft.publishedAt && draft.status === 'DRAFT'" type="button" :disabled="operation" @click="lifecycle('delete')">删除私人草稿</button>
        <details><summary>创作与审核流转</summary><ol><li v-for="(event,i) in activity" :key="i">{{ ({CREATE: '创建私人草稿', SAVE: '保存工作稿', PUBLISH: '发布版本', SCHEDULE: '通过检测，等待定时公开', WITHDRAW: '撤回发布', CANCEL: '取消送审或定时'})[event.action] || event.action }} · v{{ event.revision }} · {{ new Date(event.at).toLocaleString() }}</li></ol><p v-if="!flows.length">还没有发布提交。</p><FlowTimeline v-for="flow in flows" :key="flow.id" :flow="flow" audience="author" lit /></details>
      </div>
      <main class="cw-main" :class="{ builder: step === 'content' }">
        <StepBasic v-if="step === 'basic'" ref="basicRef" :draft="draft" :extras="extras" :attempted="attempted" :disabled="busy" @stub="stub" />
        <StepContent v-else-if="step === 'content'" :draft="draft" :extras="extras" :posts="posts" :focus="focusIssue?.step === 'content' ? focusIssue : null" :disabled="busy" @stub="stub" @append="appendImport" />
        <StepPublish v-else :draft="draft" :extras="extras" :posts="posts" :issues="issues" :can-restrict="canRestrict" :busy="operation" :published="draft.status === 'PUBLISHED'" :capabilities="capabilities" @save="saveNow" @publish="publish" @goto="goto" @stub="stub" />
      </main>
      <p v-if="toast" class="cw-toast" role="status">{{ toast }}</p>
    </template>
  </section>
</template>
