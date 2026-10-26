<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, onUpdated } from 'vue'
import { useRoute, useRouter, RouterLink, onBeforeRouteLeave } from 'vue-router'
import { api } from '../../lib/api'
import { typeLabel, stateLabel, timeLabel, readable } from '../../lib/reviewText'
import ReviewDocument from '../../components/ReviewDocument.vue'
import FlowTimeline from '../../components/FlowTimeline.vue'

const route = useRoute(), router = useRouter()
const data = ref(null), loading = ref(true), error = ref(''), note = ref(''), busy = ref(false), saveError = ref(''), message = ref(''), view = ref('SUBMITTED'), matchedTerms = ref([])
const publication = computed(() => route.meta.reviewMode === 'PUBLICATION')
const tab = computed(() => route.query.tab === 'flow' && !publication.value ? 'flow' : 'content')
const setTab = (value) => router.replace({ query: { ...route.query, tab: value === 'flow' ? 'flow' : undefined } })
const apiPath = computed(() => publication.value ? `/api/review/publications/${route.params.type}/${route.params.id}` : `/api/review/tasks/${route.params.id}`)
const type = computed(() => data.value?.type)
const recheck = computed(() => data.value?.kind === 'RECHECK')
const submitted = computed(() => publication.value ? readable(type.value, data.value?.content) : readable(type.value, data.value?.snapshot))
const published = computed(() => publication.value ? null : readable(type.value, data.value?.published))
const shown = computed(() => view.value === 'PUBLISHED' && published.value ? published.value : submitted.value)
const actionable = computed(() => data.value && (publication.value || data.value.status === 'PENDING'))
const disabled = computed(() => busy.value || data.value?.self || view.value === 'PUBLISHED')
let sequence = 0, observer
const nearEnd = ref(false)

async function load() {
  const request = ++sequence; loading.value = true; error.value = ''
  try { const result = await api(apiPath.value); if (request === sequence) data.value = result }
  catch (e) { if (request === sequence) error.value = e.message }
  finally { if (request === sequence) loading.value = false }
}
async function act(action, acknowledge = false) {
  busy.value = true; saveError.value = ''
  try {
    const item = data.value
    const body = publication.value
      ? { action, expectedModeration: item.moderation, note: note.value, acknowledgeMatches: acknowledge }
      : { action, expectedGeneration: item.generation, note: note.value }
    const result = await api(apiPath.value, { method: 'POST', body: JSON.stringify(body) })
    message.value = result.message; note.value = ''; matchedTerms.value = []; await load()
  } catch (e) {
    if (e.code === 'MATCHED' && e.body?.matches) {
      matchedTerms.value = e.body.matches
      if (window.confirm(`当前内容命中规则：${e.body.matches.join('、')}。\n请确认已阅读全文，恢复将同时完成敏感复核。继续恢复？`)) { busy.value = false; return act(action, true) }
    }
    saveError.value = e.message
  } finally { busy.value = false }
}
const goTo = (id) => document.getElementById(id)?.scrollIntoView({ behavior: 'instant', block: 'start' })
const removeCover = async () => { const reason=window.prompt('请填写移除封面的原因（1–200字）');if(!reason?.trim())return;busy.value=true;try{await api(`/api/admin/collection-covers/${shown.value.coverId}`,{method:'DELETE',body:JSON.stringify({reason})});message.value='封面已移除，正文保留。';await load()}catch(e){saveError.value=e.message}finally{busy.value=false} }
onUpdated(() => { if (!observer) { const end = document.getElementById('rr-end'); if (end) { observer = new IntersectionObserver((entries) => { nearEnd.value = entries.some((e) => e.isIntersecting) }, { threshold: 0.2 }); observer.observe(end) } } })
watch(() => route.fullPath, () => { if (!route.path.startsWith('/admin/reviews/')) return; if (route.params.id && data.value && String(data.value.id) !== String(route.params.id)) { view.value = 'SUBMITTED'; note.value = ''; message.value = ''; load() } })
onMounted(load)
onBeforeUnmount(() => observer?.disconnect())
onBeforeRouteLeave(() => !note.value.trim() || window.confirm('处理说明尚未提交，确定离开？'))
</script>

<template>
  <section class="rr-page">
    <nav class="rr-breadcrumb" aria-label="审批路径"><RouterLink :to="{ path: '/admin/reviews', query: { ...route.query, tab: undefined } }">← 返回审批列表</RouterLink><span>内容审批 / {{ publication ? '发布内容' : recheck ? '高敏感复核' : '审阅提交' }}</span></nav>
    <div v-if="loading && !data" class="rv-empty"><h2>正在读取内容</h2></div>
    <div v-else-if="error" class="rv-empty" role="alert"><h2>暂时无法读取内容</h2><p>{{ error }}</p><button class="btn btn-primary" @click="load">重新读取</button></div>
    <template v-else-if="data && submitted">
      <header class="rr-heading">
        <div class="rv-tags"><span class="rv-badge">{{ typeLabel(type) }}</span><span v-if="!publication" class="rv-badge" :class="data.level === 'HIGH' ? 'danger' : 'amber'">{{ data.level === 'HIGH' ? '高敏感 · 已自动不通过，待人工复核' : '中敏感 · 送审' }}</span><span class="rv-badge neutral">{{ publication ? (data.down ? '已下架' : '已发布') : stateLabel(data.status) }}</span></div>
        <h1>{{ shown.title }}</h1>
        <dl v-if="!publication" class="rv3-facts"><div><dt>作者</dt><dd>{{ data.authorName }}</dd></div><div><dt>提交时间</dt><dd>{{ timeLabel(data.createdAt) }}</dd></div><div><dt>提交版本</dt><dd>v{{ data.snapshotVersion }}</dd></div><div><dt>可见范围</dt><dd>{{ submitted.visibility === 'RESTRICTED' ? '受限可见' : '公开可见' }}</dd></div><div><dt>命中提示</dt><dd>{{ data.matches?.join('、') || '—' }}</dd></div><div><dt>当前发布版</dt><dd>{{ data.published ? '有（继续可见）' : '无（首次发布前不公开）' }}</dd></div></dl>
        <div v-else class="rr-meta"><span>{{ data.authorName }}</span><span>{{ shown.visibility === 'RESTRICTED' ? '受限可见' : '公开可见' }}</span></div>
      </header>
      <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
      <div v-if="!publication" class="rv-tabs rv3-tabs" role="tablist" aria-label="审批内容分区"><button role="tab" :aria-selected="tab === 'content'" :class="{ active: tab === 'content' }" @click="setTab('content')">内容与处理</button><button role="tab" :aria-selected="tab === 'flow'" :class="{ active: tab === 'flow' }" @click="setTab('flow')">流转过程 <span class="rv-badge neutral">{{ data.flow?.events.length || 0 }}</span></button></div>
      <section v-if="tab === 'flow'" class="rv3-flowtab" aria-label="流转过程"><h2>流转过程</h2><p class="rv3-side-note">与作者看到的是同一条流程；审批侧额外显示处理人和命中详情。</p><FlowTimeline :flow="data.flow" audience="reviewer" /></section>
      <div v-show="tab === 'content'" class="rv3-layout rv3-single">
        <div class="rv3-main">
          <div v-if="!publication && published" class="rr-version-bar"><div><strong>{{ view === 'SUBMITTED' ? '正在阅读本次送审内容' : '正在阅读当前已发布内容' }}</strong><span>送审版与已发布版分别阅读，不并排对照。</span></div><button class="btn btn-ghost" @click="view = view === 'SUBMITTED' ? 'PUBLISHED' : 'SUBMITTED'">{{ view === 'SUBMITTED' ? '查看当前发布版' : '返回本次送审内容' }} ↗</button></div>
          <div v-if="data.matches?.length && view === 'SUBMITTED'" class="rr-hit-summary"><span class="rv-badge amber">命中提示</span><p>本次命中：{{ data.matches.join('、') }}。请结合正文语境判断。</p><button @click="goTo('rr-content')">阅读正文 ↓</button></div>
          <div class="rr-reading-nav"><span>阅读全文后，在文末填写处理意见</span><button @click="goTo('rr-decision')">前往审批处理 ↓</button></div>
          <article id="rr-content" class="rr-content">
            <div class="rr-content-label"><span>{{ publication ? '待处置发布内容' : view === 'PUBLISHED' ? '当前已发布内容' : '本次送审内容' }}</span><small>完整正文</small></div>
            <p v-if="shown.summary" class="rr-abstract">{{ shown.summary }}</p>
            <div v-if="shown.coverId"><img :src="`/api/collection-covers/${shown.coverId}`" alt="提交快照封面" style="max-width:100%;max-height:240px;object-fit:contain" @error="$event.target.hidden=true" /><button type="button" :disabled="busy" @click="removeCover">仅移除封面</button></div><ReviewDocument :text="shown.body" :terms="view === 'SUBMITTED' ? (data.matches || matchedTerms) : []" />
            <div id="rr-end" class="rr-end">— 正文结束 —</div>
          </article>
          <aside id="rr-decision" class="rr-decision">
            <span class="kicker">{{ publication ? 'CONTENT ACTION' : recheck ? 'HIGH · RECHECK' : 'REVIEW DECISION' }}</span>
            <h2>{{ publication ? (data.down ? '复核恢复' : '内容处置') : recheck ? '高敏感人工复核' : '审批处理' }}</h2>
            <p class="rr-action-scope">{{ publication ? '本次操作针对上方指定发布版。' : recheck ? '内容已被自动判为不通过，当前不公开。请判断是否属于误判：维持不通过，或放行发布。' : '审批对象始终是本次送审内容，不会发布作者后续改稿。' }}</p>
            <p v-if="data.self && (!publication || data.down)" class="rv-notice amber">不能处理本人提交或批准恢复本人作品，请交给另一位审批者。</p>
            <p v-else-if="!publication && data.status === 'PENDING' && !data.otherReviewerAvailable" class="rv-notice amber">暂无其他可用审批者，内容会保持待审，不会自动发布。</p>
            <p v-if="view === 'PUBLISHED'" class="rv-notice">正在查看旧发布版。请返回送审内容后再作审批决定。</p>
            <p v-if="data.result" class="rv-notice">{{ data.operator ? data.operator + '：' : '' }}{{ data.result }}</p>
            <p v-if="publication && data.down" class="rr-action-scope">恢复前重新检测；若命中中/高敏感规则，需显式确认，本次同时完成敏感复核。作者保存不能解除下架。</p>
            <template v-if="actionable">
              <label class="rv-field">处理说明<span>{{ publication || recheck ? '请填写原因（必填）' : '通过可选，驳回时必填' }}</span><textarea v-model="note" rows="4" maxlength="200" placeholder="说明处理依据，最多200字" /></label>
              <p v-if="saveError" class="rv-notice danger" role="alert">{{ saveError }}</p>
              <div class="rr-decision-buttons">
                <template v-if="!publication && !recheck"><button class="btn btn-primary" :disabled="disabled" @click="act('APPROVE')">{{ busy ? '处理中…' : '通过并发布' }}</button><button class="btn btn-ghost danger" :disabled="disabled" @click="act('REJECT')">驳回并告知作者</button></template>
                <template v-else-if="recheck"><button class="btn btn-ghost danger" :disabled="disabled" @click="act('UPHOLD')">{{ busy ? '处理中…' : '维持不通过' }}</button><button class="btn btn-primary" :disabled="disabled" @click="act('OVERTURN')">判定误判并放行</button></template>
                <button v-else class="btn" :class="data.down ? 'btn-primary' : 'btn-ghost danger'" :disabled="busy || (data.down && data.self)" @click="act(data.down ? 'RESTORE' : 'TAKE_DOWN')">{{ busy ? '处理中…' : data.down ? '复核并恢复' : '确认下架' }}</button>
              </div>
              <p class="rr-action-hint">{{ nearEnd ? '已到正文末尾，请结合全文与命中语境作决定。' : '请完整阅读正文，再填写处理意见。' }}</p>
            </template>
            <RouterLink class="rr-return" :to="{ path: '/admin/reviews', query: { ...route.query, tab: undefined } }">返回审批列表</RouterLink>
          </aside>
        </div>
      </div>
      <details v-if="data.history?.length" class="rv-history rr-history"><summary>这条内容的处理记录（{{ data.history.length }}）</summary><p v-for="entry in data.history" :key="entry.id"><strong>{{ entry.type }} · {{ entry.outcome }}</strong> · {{ entry.operator }}<br>{{ entry.note }} {{ entry.result }}<small>{{ timeLabel(entry.at) }}</small></p></details>
    </template>
  </section>
</template>
