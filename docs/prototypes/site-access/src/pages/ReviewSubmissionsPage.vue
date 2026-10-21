<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { RouterLink, useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { api } from '@finns/lib/api'
import { sessionUser } from '../proto/session'
import FlowTimeline from '../components/FlowTimeline.vue'
const route = useRoute(), router = useRouter(), items = ref([]), selected = ref(null), form = ref({}), loading = ref(true), error = ref(''), message = ref(''), outcome = ref(null), busy = ref(false), dirty = ref(false), boundAuthor = ref(null), newType = ref('ARTICLE')
const typeLabel = value => ({ ARTICLE: '文章', COLLECTION: '专题', COMMENT: '评论' }[value])
const changedIdentity = computed(() => boundAuthor.value && boundAuthor.value !== sessionUser.value?.id)
const latest = computed(() => selected.value?.flows?.[0] || null)
const older = computed(() => selected.value?.flows?.slice(1) || [])
const canRestrict = computed(() => sessionUser.value?.permissions.includes('content.restricted'))
function chip(item) {
  const flow = item.flows?.[0]
  if (item.down) return ['已下架', 'danger']
  if (item.withdrawn) return ['已撤回', 'neutral']
  if (flow?.status === 'IN_REVIEW') return ['待审核', 'amber']
  if (flow?.status === 'AUTO_REJECTED') return ['未通过 · 复核中', 'danger']
  if (flow && ['REJECTED', 'UPHELD'].includes(flow.status)) return ['未通过', 'danger']
  if (item.published) return ['已发布', 'green']
  return ['草稿', 'neutral']
}
function select(item) { selected.value = item; form.value = { ...item.draft }; dirty.value = false; boundAuthor.value = sessionUser.value?.id; outcome.value = null }
async function load() {
  loading.value = true; error.value = ''
  try { const data = await api('/api/review/mine'); items.value = data.items; const chosen = data.items.find(x => x.id === Number(route.query.id)) || data.items[0]; if (chosen && (!dirty.value || !selected.value)) select(chosen); else if (!chosen && !dirty.value) selected.value = null; else if (chosen && selected.value?.id === chosen.id) selected.value = { ...chosen } }
  catch (e) { error.value = e.message } finally { loading.value = false }
}
function choose(item) { if (dirty.value && !window.confirm('离开会放弃尚未保存的修改，确定切换内容？')) return; select(item); router.replace({ query: { id: item.id } }) }
async function save() {
  if (changedIdentity.value) { error.value = '账号已变化，当前输入已保留，不能替其他账号提交。'; return false }
  error.value = ''
  try { const doc = await api('/api/review/mine/' + selected.value.id, { method: 'PUT', body: JSON.stringify({ ...form.value, expectedVersion: form.value.version }) }); selected.value = { ...selected.value, ...doc }; form.value = { ...doc.draft }; dirty.value = false; message.value = '私人工作稿已保存，尚未公开。'; return true } catch (e) { error.value = e.message; return false }
}
let lastSubmission = null
async function submit(retry = false) {
  busy.value = true; error.value = ''; message.value = ''
  try {
    if (changedIdentity.value) throw new Error('账号已变化，不能替其他账号提交；输入已保留。')
    if (dirty.value && !await save()) return
    const id = selected.value.id, body = retry && lastSubmission?.id === id ? lastSubmission.body : { expectedVersion: form.value.version, requestId: crypto.randomUUID() }
    lastSubmission = { id, body }
    const result = await api(`/api/review/mine/${id}/submit`, { method: 'POST', body: JSON.stringify(body) })
    await load(); outcome.value = result; message.value = result.message
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function create() { if (dirty.value && !window.confirm('新建前放弃未保存的修改？')) return; busy.value = true; try { const doc = await api('/api/review/mine', { method: 'POST', body: JSON.stringify({ type: newType.value }) }); dirty.value = false; await router.replace({ query: { id: doc.id } }); await load() } catch (e) { error.value = e.message } finally { busy.value = false } }
async function withdraw() { busy.value = true; try { const result = await api(`/api/review/mine/${selected.value.id}/${selected.value.type === 'COMMENT' ? 'delete' : 'withdraw'}`, { method: 'POST', body: '{}' }); dirty.value = false; await load(); message.value = result.message } catch (e) { error.value = e.message } finally { busy.value = false } }
const retry = () => submit(true)
const beforeUnload = e => { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
watch(() => route.query.id, () => { if (!dirty.value) load() })
watch(() => sessionUser.value?.id, () => { if (!dirty.value) load() })
onBeforeRouteLeave(() => !dirty.value || window.confirm('还有未保存的修改，确定离开？'))
onMounted(() => { load(); window.addEventListener('finns-review-scene', load); window.addEventListener('finns-review-retry', retry); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { window.removeEventListener('finns-review-scene', load); window.removeEventListener('finns-review-retry', retry); window.removeEventListener('beforeunload', beforeUnload) })
</script>
<template>
  <section class="rv-author section">
    <div class="rv-topline"><RouterLink to="/me">个人中心 / 发布与流转</RouterLink><RouterLink to="/review/reading">查看读者可见内容 ↗</RouterLink></div>
    <header class="rv-author-heading"><span class="kicker">CREATE · PUBLISH WITH CARE</span><h1>你的每次提交，<br><em>都看得见去了哪里。</em></h1><p>文章、评论和专题走同一条流程：没有命中或只命中低敏感会直接发布；命中中敏感会送审；命中高敏感会自动不通过并交人工复核。每次提交的流转过程都显示在下方。</p></header>
    <p v-if="changedIdentity" class="rv-notice amber" role="alert">账号已变化。输入已保留，不能将它提交到其他账号；请切回原作者后继续。</p>
    <div v-if="loading && !selected" class="rv-empty"><h2>正在读取我的内容</h2></div>
    <div class="rv-author-grid">
      <aside class="rv-draft-list"><h2>我的内容 <span>{{ items.length }}</span></h2>
        <div class="rv-new"><label class="sr-only" for="rv-new-type">新建内容类型</label><select id="rv-new-type" v-model="newType"><option value="ARTICLE">文章</option><option value="COLLECTION">专题</option><option value="COMMENT">评论</option></select><button class="btn btn-ghost" :disabled="busy" @click="create">新建</button></div>
        <button v-for="item in items" :key="item.id" class="rv-draft-choice" :class="{ active: selected?.id === item.id }" @click="choose(item)"><small>{{ typeLabel(item.type) }} · <span class="rv-badge" :class="chip(item)[1]">{{ chip(item)[0] }}</span></small><strong>{{ item.draft.title || '未命名' }}</strong></button>
      </aside>
      <div v-if="selected" class="rv-editor">
        <div class="rv-editor-top"><span class="rv-badge">{{ typeLabel(selected.type) }}</span><span>工作稿 v{{ form.version }} · {{ dirty ? '有未保存修改' : '已保存' }}</span></div>
        <form @submit.prevent="submit(false)">
          <label class="rv-field">标题<input v-model="form.title" maxlength="160" required @input="dirty = true" /></label>
          <label v-if="selected.type !== 'COMMENT'" class="rv-field">摘要<textarea v-model="form.summary" rows="2" maxlength="500" @input="dirty = true" /></label>
          <label class="rv-field">{{ selected.type === 'COMMENT' ? '评论内容' : selected.type === 'COLLECTION' ? '专题正文' : '正文' }}<textarea v-model="form.body" rows="8" maxlength="20000" required @input="dirty = true" /></label>
          <label v-if="canRestrict && selected.type !== 'COMMENT'" class="rv-field">可见范围<select v-model="form.visibility" @change="dirty = true"><option value="PUBLIC">公开</option><option value="RESTRICTED">受限</option></select></label>
          <p v-if="selected.down" class="rv-notice amber">该内容已被独立下架，保存或正常检测通过不能解除下架，须由另一位审批者复核恢复。</p>
          <p v-if="selected.pending" class="rv-notice">提交版正在等待人工处理。现在修改和保存工作稿不会改变已提交的内容；重新提交会使旧任务失效。</p>
          <p v-if="message" class="rv-notice" :class="outcome?.outcome === 'AUTO_REJECTED' ? 'danger' : outcome?.outcome === 'PENDING' ? 'amber' : 'success'" role="status">{{ message }}</p>
          <p v-if="error" class="rv-notice danger" role="alert">{{ error }}</p>
          <div class="rv-editor-actions"><button class="btn btn-ghost" type="button" :disabled="busy || changedIdentity" @click="busy = true; save().finally(() => busy = false)">保存私人草稿</button><button class="btn btn-primary" :disabled="busy || changedIdentity || selected.down">{{ busy ? '处理中…' : selected.pending ? '重新提交' : '提交发布' }}</button></div>
        </form>
        <section class="rv3-flow" aria-label="流转过程"><div class="rv3-flow-head"><h2>流转过程</h2><span v-if="latest" class="rv-badge" :class="chip(selected)[1]">{{ chip(selected)[0] }}</span></div>
          <p v-if="!latest" class="rv-footnote">还没有提交记录。保存只是私人草稿；提交后，检测、分流和人工处理的每一步都会出现在这里。</p>
          <template v-else><p class="rv-footnote">最近一次提交 v{{ latest.version }}。{{ latest.level === 'HIGH' ? '高敏感内容不显示具体命中词。' : '' }}</p><FlowTimeline :flow="latest" audience="author" />
            <details v-if="older.length" class="rv-history"><summary>更早的提交（{{ older.length }}）</summary><div v-for="flow in older" :key="flow.id" class="rv3-older"><strong>提交版 v{{ flow.version }}</strong><FlowTimeline :flow="flow" audience="author" compact /></div></details></template>
        </section>
        <section class="rv-published-preview"><h2>当前读者可见</h2><template v-if="selected.published && !selected.down && !selected.withdrawn"><small>发布版 v{{ selected.published.version }}{{ selected.legacy ? ' · 历史发布基线，无补造审批' : '' }}</small><h3>{{ selected.published.title }}</h3><p class="rv-body">{{ selected.published.body }}</p></template><p v-else>当前没有对读者可见的发布版本。</p></section>
        <div class="rv-withdraw"><button class="text-link danger" :disabled="busy || changedIdentity" @click="withdraw">{{ selected.type === 'COMMENT' ? '删除这条评论' : '撤回公开与待审提交，保留私人稿' }}</button></div>
      </div>
      <div v-else class="rv-empty"><h2>从一份私人草稿开始</h2><p>在左侧新建内容，无需审批即可保存。</p><p v-if="error" role="alert">{{ error }}</p></div>
    </div>
  </section>
</template>
