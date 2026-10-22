<script setup>
// FINNS-18：文章编辑页。保存（私人）与发布（检测并对外）分开；工作稿、已提交的冻结版本、读者可见的公开版三者分开展示。
// 登录失效/换号沿用 useSessionGuard：输入原位保留，不代存代发。
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { RouterLink, useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import PersonalLayout from '../components/PersonalLayout.vue'
import FlowTimeline from '../components/FlowTimeline.vue'
import ReauthPanel from '../components/ReauthPanel.vue'
import { useSessionGuard } from '../lib/sessionGuard'
import { timeLabel } from '../lib/reviewText'
import {
  LIMITS, FIELDS, FIELD_TEXT, articles, countChars, pickFields, sameFields, overLimit, missingForPublish, isBlank,
  newRequestId, publicChip, workChip, publishLabel, publishHint, outcomeTone, errorKind, errorText,
} from '../lib/articles'

const route = useRoute(), router = useRouter()
const guard = useSessionGuard()
const isNew = computed(() => !route.params.id)
const doc = ref(null), form = ref(pickFields()), base = ref(pickFields())
const loading = ref(true), loadFailed = ref(false), busy = ref('')
const error = ref(''), notice = ref(''), noticeTone = ref('success'), missing = ref([]), conflict = ref(null)
const dialog = ref('')
let leaveAnswer = null, bypass = false, lastPublish = null

const dirty = computed(() => !sameFields(form.value, base.value))
const empty = computed(() => isBlank(form.value))
const over = computed(() => overLimit(form.value))
const locked = computed(() => !!busy.value || guard.expired.value)
const status = computed(() => doc.value?.status)
const live = computed(() => doc.value?.status?.publicState === 'LIVE')
const latest = computed(() => doc.value?.flows?.[0] || null)
const older = computed(() => doc.value?.flows?.slice(1) || [])

const clearMessages = () => { error.value = ''; notice.value = ''; missing.value = []; conflict.value = null }
const say = (text, tone = 'success') => { notice.value = text; noticeTone.value = tone }
const adopt = (view) => { doc.value = view; base.value = pickFields(view) }

async function load() {
  loading.value = true; loadFailed.value = false; error.value = ''
  if (isNew.value) { doc.value = null; if (!dirty.value) { form.value = pickFields(); base.value = pickFields() } loading.value = false; return }
  try {
    const view = await articles.get(route.params.id)
    adopt(view); form.value = pickFields(view)
  } catch (e) { if (!guard.handle(e)) { loadFailed.value = true; error.value = e.status === 404 ? '没有找到这篇文章，可能已被删除。' : errorText(e) } }
  finally { loading.value = false }
}

// 刷新服务器状态但不覆盖用户正在输入的内容。
async function refresh() {
  if (!doc.value) return null
  try { const view = await articles.get(doc.value.id); doc.value = view; if (sameFields(form.value, pickFields(view))) base.value = pickFields(view); return view } catch { return null }
}

async function fail(e) {
  if (guard.handle(e)) { error.value = errorText({ status: 401 }); return }
  const kind = errorKind(e)
  error.value = errorText(e)
  if (kind === 'conflict' && doc.value) {
    const view = await refresh()
    if (view && !sameFields(form.value, pickFields(view))) conflict.value = view
  } else if (kind === 'retryable') await refresh() // 保存步骤可能已成功：取回新版本号，重试沿用同一 requestId
  else if (kind === 'state') await refresh()
  else if (e.code === 'VALIDATION' && Array.isArray(e.body?.fields)) missing.value = e.body.fields.filter((f) => FIELDS.includes(f))
}

function validateLocal(forPublish) {
  const bad = over.value
  if (bad.length) { error.value = `${bad.map((k) => FIELD_TEXT[k]).join('、')}超过上限（${bad.map((k) => `${countChars(form.value[k])}/${LIMITS[k]}`).join('，')}），请精简后再继续；输入已保留。`; return false }
  if (forPublish) {
    const lack = missingForPublish(form.value)
    if (lack.length) { missing.value = lack; error.value = `发布前请补全：${lack.map((k) => FIELD_TEXT[k]).join('、')}。现在可以先保存为私人草稿。`; nextTick(() => document.querySelector(`[data-field="${lack[0]}"]`)?.focus()); return false }
  }
  return true
}

// 新稿首次保存后把地址换成 /me/posts/:id/edit（同一组件实例，输入与进行中的请求不受影响；刷新后也能回到这份草稿）。
async function save() {
  clearMessages()
  if (!validateLocal(false)) return false
  if (empty.value && !doc.value) { error.value = '标题、摘要和正文都是空的，不会创建草稿。'; return false }
  if (!(await guard.beforeSubmit())) { error.value = errorText({ status: 401 }); return false }
  busy.value = 'save'
  try {
    if (!doc.value) {
      const created = await articles.create(form.value)
      adopt(created)
      bypass = true
      try { await router.replace(`/me/posts/${created.id}/edit`) } finally { bypass = false }
    } else adopt(await articles.save(doc.value.id, form.value, doc.value.version))
    say(`私人草稿已保存（工作稿 v${doc.value.version}），尚未公开${live.value ? '；读者看到的仍是旧公开版' : ''}。`)
    return true
  } catch (e) { await fail(e); return false } finally { busy.value = '' }
}

async function publish() {
  clearMessages()
  if (!validateLocal(true)) return
  if (!(await guard.beforeSubmit())) { error.value = errorText({ status: 401 }); return }
  if (!doc.value && !(await save())) return
  busy.value = 'publish'
  try {
    // 同一份内容在检测暂不可用后重试，沿用同一 requestId（服务端据此幂等）；内容变了或换了文章则用新的。
    const same = lastPublish && lastPublish.docId === doc.value.id && sameFields(lastPublish.fields, form.value)
    const requestId = same ? lastPublish.requestId : newRequestId()
    lastPublish = { docId: doc.value.id, requestId, fields: { ...form.value } }
    const result = await articles.publish(doc.value.id, form.value, doc.value.version, requestId)
    lastPublish = null
    adopt(result.article)
    say(result.replay ? `这次提交已经处理过，没有重复发布。${result.message}` : result.message, outcomeTone(result.outcome))
  } catch (e) {
    if (errorKind(e) !== 'retryable' && !guard.handle(e)) lastPublish = null
    await fail(e)
  } finally { busy.value = '' }
}

async function act(name, run) {
  dialog.value = ''; clearMessages()
  if (!(await guard.beforeSubmit())) { error.value = errorText({ status: 401 }); return }
  busy.value = name
  try { const result = await run(); adopt(result.article); say(result.message) }
  catch (e) { await fail(e) } finally { busy.value = '' }
}
const cancelSubmission = () => act('cancel', () => articles.cancel(doc.value.id))
const withdraw = () => act('withdraw', () => articles.withdraw(doc.value.id))

// “载入别处的版本”放弃本地输入；“以我的为准”把版本号对齐到服务器当前版本，下一次保存才会通过版本校验。
function takeServerVersion() { adopt(conflict.value); form.value = pickFields(conflict.value); conflict.value = null; say('已载入别处保存的版本，你刚才的输入已放弃。', 'amber') }
function keepMine() { conflict.value = null; say('将以你的输入为准，请再次点击“保存草稿”或“发布”。', 'amber') }

async function preview() {
  clearMessages()
  if (!doc.value && empty.value) { error.value = '还没有内容可以预览。'; return }
  if (!doc.value || dirty.value) { if (!(await save())) return }
  bypass = true; await router.push(`/me/posts/${doc.value.id}/preview`); bypass = false
}

// 页面内离开保护：保存并离开 / 放弃修改 / 继续编辑；保存失败时不离开。
const ask = () => new Promise((resolve) => { leaveAnswer = resolve; dialog.value = 'leave' })
function answer(choice) { dialog.value = ''; const resolve = leaveAnswer; leaveAnswer = null; resolve?.(choice) }
onBeforeRouteLeave(async () => {
  if (bypass || !dirty.value) return true
  const choice = await ask()
  if (choice === 'stay') return false
  if (choice === 'discard') return true
  return await save()
})
const beforeUnload = (e) => { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
const onKey = (e) => { if (e.key === 'Escape' && dialog.value) { dialog.value === 'leave' ? answer('stay') : dialog.value = '' } }
watch(() => route.params.id, (id) => { if (!dirty.value && String(id ?? '') !== String(doc.value?.id ?? '')) load() })
onMounted(() => { load(); window.addEventListener('beforeunload', beforeUnload); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('keydown', onKey) })
</script>

<template>
  <PersonalLayout kicker="MY CONTENT" :title="isNew ? '新建文章' : (doc?.title || '未命名文章')" desc="私人草稿、已提交版本与公开版分开管理；保存不会公开。">
    <template #actions><div class="heading-actions"><RouterLink class="btn btn-ghost" to="/me/posts">← 返回列表</RouterLink><RouterLink v-if="doc" class="btn btn-outline" :to="`/me/posts/${doc.id}/preview`">私人预览</RouterLink></div></template>
    <div v-if="loading" class="rv-empty" role="status"><span class="rv-spinner"></span><h2>正在读取文章</h2></div>
    <div v-else-if="loadFailed" class="rv-empty"><h2>无法打开这篇文章</h2><p role="alert">{{ error }}</p><RouterLink class="btn btn-primary" to="/me/posts">回到我的内容</RouterLink></div>
    <template v-else>
      <header class="ct-head">
        <div v-if="status" class="rv-tags" aria-label="当前状态"><span class="rv-badge" :class="publicChip(status)[1]">{{ publicChip(status)[0] }}</span><span class="rv-badge" :class="workChip(status)[1]">{{ workChip(status)[0] }}</span></div>
        <div v-else class="rv-tags"><span class="rv-badge neutral">尚未保存</span></div>
      </header>
      <ReauthPanel :guard="guard" />
      <p v-if="guard.expired.value" class="rv-notice amber" role="alert">登录已失效或账号已变化。输入已保留，不会代存或代发。</p>
      <p v-if="status?.publicState === 'DOWN'" class="rv-notice danger" role="status">这篇文章已被人工下架。保存、更新或撤回都不能解除下架，需要另一位审批者复核后恢复；你仍可继续编辑私人稿。</p>
      <p v-else-if="status?.publicState === 'WITHDRAWN'" class="rv-notice" role="status">你已撤回这篇文章，现在只有你自己可见。重新公开会重新检测。</p>
      <p v-if="notice" class="rv-notice" :class="noticeTone" role="status">{{ notice }}</p>
      <p v-if="error" class="rv-notice danger" role="alert">{{ error }}</p>
      <div v-if="conflict" class="rv-notice amber" role="alert">别处保存了不同的版本（v{{ conflict.version }}）。你的输入仍在下面。<button type="button" class="text-link" @click="keepMine">以我的为准</button> · <button type="button" class="text-link" @click="takeServerVersion">载入别处的版本（放弃我的输入）</button></div>
      <div class="ct-grid">
        <form class="rv-editor ct-form" novalidate @submit.prevent="publish">
          <div class="rv-editor-top"><span class="rv-badge">文章</span><span>{{ doc ? `工作稿 v${doc.version}` : '新稿' }} · {{ dirty ? '有未保存的修改' : doc ? '已保存' : '未保存' }}</span></div>
          <label class="rv-field" :class="{ bad: missing.includes('title') || over.includes('title') }">标题<input v-model="form.title" data-field="title" :aria-invalid="missing.includes('title') || over.includes('title')" autocomplete="off" /><span class="ct-count" :class="{ over: over.includes('title') }">{{ countChars(form.title) }}/{{ LIMITS.title }}</span></label>
          <label class="rv-field" :class="{ bad: missing.includes('summary') || over.includes('summary') }">摘要<textarea v-model="form.summary" data-field="summary" rows="3" :aria-invalid="missing.includes('summary') || over.includes('summary')" /><span class="ct-count" :class="{ over: over.includes('summary') }">{{ countChars(form.summary) }}/{{ LIMITS.summary }}</span></label>
          <label class="rv-field" :class="{ bad: missing.includes('body') || over.includes('body') }">正文<textarea v-model="form.body" data-field="body" rows="14" :aria-invalid="missing.includes('body') || over.includes('body')" /><span class="ct-count" :class="{ over: over.includes('body') }">{{ countChars(form.body) }}/{{ LIMITS.body }}</span></label>
          <p class="rv-footnote">支持安全 Markdown：<code># 标题</code>、<code>- 列表</code>、<code>**粗体**</code>、<code>`代码`</code>、<code>[链接](https://…)</code>。HTML、脚本和图片不会生效。草稿可以缺字段；发布时标题、摘要、正文都必填。长度按字符数计算。</p>
          <div class="rv-editor-actions ct-actions">
            <button type="button" class="btn btn-ghost" :disabled="locked || (empty && !doc)" @click="save()">{{ busy === 'save' ? '保存中…' : '保存草稿' }}</button>
            <button type="button" class="btn btn-ghost" :disabled="locked || (empty && !doc)" @click="preview">私人预览</button>
            <button class="btn btn-primary" :disabled="locked">{{ busy === 'publish' ? '处理中…' : publishLabel(doc) }}</button>
          </div>
          <p class="ct-hint">保存只是私人存稿，不会公开也不会送审。{{ publishHint(doc) }}</p>
          <div v-if="doc && (doc.canCancel || doc.canWithdraw)" class="ct-danger-row">
            <button v-if="doc.canCancel" type="button" class="text-link" :disabled="locked" @click="cancelSubmission">取消本次送审（保留工作稿和旧公开版）</button>
            <button v-if="doc.canWithdraw" type="button" class="text-link danger" :disabled="locked" @click="dialog = 'withdraw'">撤回公开（保留私人稿）</button>
          </div>
        </form>
        <aside class="ct-side" aria-label="版本与流转">
          <section class="ct-versions">
            <h2>三个版本</h2>
            <article class="ct-version" :class="{ on: doc?.published }"><small>读者现在看到</small><template v-if="doc?.published"><strong>公开版 v{{ doc.published.version }}</strong><p>{{ doc.published.title }}</p></template><template v-else><strong>读者看不到</strong><p>{{ doc?.status?.publicState === 'DOWN' ? '已被人工下架。' : doc?.status?.publicState === 'WITHDRAWN' ? '已撤回，仅你自己可见。' : '还没有公开版本。' }}</p></template></article>
            <article class="ct-version" :class="{ on: doc?.submitted }"><small>已提交 · 等待处理</small><template v-if="doc?.submitted"><strong>提交版 v{{ doc.submitted.version }}</strong><p>{{ doc.submitted.title }}</p><em>{{ doc.submitted.kind === 'RECHECK' ? '高敏感：已自动不通过，人工复核中。' : '中敏感：已送审，等待另一位审批者。' }}之后的修改不会替换它，重新提交才会使它作废。</em></template><template v-else><strong>没有待处理的提交</strong><p>保存、继续编辑都不会产生提交。</p></template></article>
            <article class="ct-version on"><small>我的工作稿</small><strong>工作稿 {{ doc ? `v${doc.version}` : '（未保存）' }}</strong><p>{{ dirty ? '输入里有尚未保存的修改。' : doc ? '输入与已保存的草稿一致。' : '保存后才会出现在“我的内容”。' }}</p></article>
          </section>
          <section class="rv3-flow ct-flow" aria-label="流转过程"><div class="rv3-flow-head"><h2>流转过程</h2></div>
            <p v-if="!latest" class="rv-footnote">还没有提交记录。保存草稿不会出现在这里；点击“发布”后，检测、分流和人工处理的每一步都会显示。</p>
            <template v-else><p class="rv-footnote">最近一次提交 v{{ latest.version }}（{{ timeLabel(latest.startedAt) }}）。{{ latest.level === 'HIGH' ? '高敏感内容不显示具体命中词。' : '' }}</p><FlowTimeline :flow="latest" audience="author" lit /><details v-if="older.length" class="rv-history"><summary>更早的提交（{{ older.length }}）</summary><div v-for="flow in older" :key="flow.id" class="rv3-older"><strong>提交版 v{{ flow.version }}</strong><FlowTimeline :flow="flow" audience="author" compact lit /></div></details></template>
          </section>
        </aside>
      </div>
    </template>
    <div v-if="dialog === 'leave'" class="ct-overlay" @click.self="answer('stay')"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-leave-title"><h2 id="ct-leave-title">还有没保存的修改</h2><p>离开前要怎么处理这些修改？保存只会存为私人草稿，不会公开。</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="answer('save')">保存并离开</button><button class="btn btn-ghost" @click="answer('discard')">放弃修改</button><button class="btn btn-ghost" @click="answer('stay')">继续编辑</button></div></section></div>
    <div v-if="dialog === 'withdraw'" class="ct-overlay" @click.self="dialog = ''"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-withdraw-title"><h2 id="ct-withdraw-title">撤回公开？</h2><p>撤回后读者、列表、搜索和收藏里都看不到这篇文章，待处理的提交也会作废；私人稿会保留，之后重新发布会重新检测。</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="withdraw">确认撤回</button><button class="btn btn-ghost" @click="dialog = ''">先不撤回</button></div></section></div>
  </PersonalLayout>
</template>
