<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { api } from '@finns/lib/api'
import AdminLayout from '../components/AdminLayout.vue'
import PageNav from '../components/PageNav.vue'
const route = useRoute(), router = useRouter()
const result = ref(null), publications = ref([]), noted = ref([]), loading = ref(true), error = ref('')
const q = ref(''), type = ref(''), level = ref(''), days = ref('')
const status = computed(() => String(route.query.status || 'PENDING'))
const isPublications = computed(() => ['PUBLISHED', 'DOWN'].includes(status.value))
const isNoted = computed(() => status.value === 'NOTED')
const typeLabel = value => ({ ARTICLE: '文章', COLLECTION: '专题', COMMENT: '评论' }[value] || value)
const stateLabel = value => ({ PENDING: '待处理', APPROVED: '已通过', REJECTED: '已驳回', UPHELD: '已维持不通过', OVERTURNED: '已放行', INVALID: '已失效' }[value] || value)
const time = value => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const visiblePublications = computed(() => publications.value.filter(p => p.down === (status.value === 'DOWN') && (!type.value || p.type === type.value) && (p.title + p.authorName).includes(q.value.trim())))
const stepIndex = item => item.status === 'PENDING' ? 3 : 4
let request = 0
async function load() {
  const id = ++request; loading.value = true; error.value = ''
  q.value = String(route.query.q || ''); type.value = String(route.query.type || ''); level.value = String(route.query.level || ''); days.value = String(route.query.days || '')
  try {
    const data = await api('/api/review/queue?' + new URLSearchParams({ ...route.query, status: isPublications.value || isNoted.value ? 'ALL' : status.value }))
    const pub = isPublications.value ? await api('/api/review/publications') : null
    const note = isNoted.value ? await api('/api/review/noted') : null
    if (id === request) { result.value = data; if (pub) publications.value = pub.items; if (note) noted.value = note.items }
  } catch (e) { if (id === request) error.value = e.message }
  finally { if (id === request) loading.value = false }
}
const changeTab = value => router.push({ query: { status: value } })
function search() { const query = { status: status.value, ...(q.value ? { q: q.value } : {}), ...(type.value ? { type: type.value } : {}), ...(level.value && !isPublications.value ? { level: level.value } : {}), ...(days.value && !isPublications.value ? { days: days.value } : {}) }; if (JSON.stringify(query) === JSON.stringify(route.query)) load(); else router.push({ query }) }
async function markNoted(n) { try { await api('/api/review/noted', { method: 'POST', body: JSON.stringify({ docId: n.docId, action: n.reviewed ? 'UNREVIEWED' : 'REVIEWED' }) }); await load() } catch (e) { error.value = e.message } }
const openPublication = id => router.push({ path: '/admin/reviews/publications/' + id, query: route.query })
const openRule = n => router.push({ path: '/admin/sensitive-rules', query: { q: n.words[0] } })
const open = (item, mode = 'TASK') => router.push({ path: `/admin/reviews/${mode === 'TASK' ? 'tasks' : 'publications'}/${item.id}`, query: route.query })
watch(() => route.fullPath, load)
onMounted(() => { load(); window.addEventListener('finns-review-scene', load) })
onBeforeUnmount(() => window.removeEventListener('finns-review-scene', load))
</script>
<template>
  <AdminLayout title="内容审批" description="文章、评论和专题走同一条流程：检测放行低敏感，中敏感送审，高敏感自动不通过并复核。" kicker="TRUST · CONTENT REVIEW" version="FINNS-17 · R3">
    <div class="rv-topline"><span>三级敏感 · 统一流程 · 每次提交都有流转记录</span><RouterLink to="/me/submissions">我的发布与流转 ↗</RouterLink></div>
    <div class="rv3-cards" role="group" aria-label="审批概览">
      <button class="rv3-card" :class="{ on: status === 'PENDING' && level === 'MEDIUM' }" @click="router.push({ query: { status: 'PENDING', level: 'MEDIUM' } })"><span class="rv-badge amber">中敏感 · 送审</span><strong>{{ result?.medium ?? '—' }}</strong><small>需要另一位有权限的人判断</small></button>
      <button class="rv3-card" :class="{ on: status === 'PENDING' && level === 'HIGH' }" @click="router.push({ query: { status: 'PENDING', level: 'HIGH' } })"><span class="rv-badge danger">高敏感 · 复核</span><strong>{{ result?.high ?? '—' }}</strong><small>已自动不通过，等待人工复核</small></button>
      <button class="rv3-card" :class="{ on: status === 'DONE' }" @click="changeTab('DONE')"><span class="rv-badge green">今日已处理</span><strong>{{ result?.doneToday ?? '—' }}</strong><small>通过、驳回、维持或放行</small></button>
      <button class="rv3-card" :class="{ on: isNoted }" @click="changeTab('NOTED')"><span class="rv-badge neutral">低敏感 · 留痕</span><strong>{{ result?.low ?? '—' }}</strong><small>已放行，仅供事后查看</small></button>
    </div>
    <div class="rv-tabs" role="tablist" aria-label="内容处理状态"><button v-for="tab in [['PENDING','待处理'],['DONE','已处理'],['NOTED','低敏感留痕'],['PUBLISHED','已发布'],['DOWN','已下架']]" :key="tab[0]" role="tab" :aria-selected="status === tab[0]" :class="{ active: status === tab[0] }" @click="changeTab(tab[0])">{{ tab[1] }}</button></div>
    <form v-if="!isNoted" class="rv-filters" @submit.prevent="search">
      <label class="rv-search">搜索内容或作者<input v-model="q" placeholder="输入标题或作者昵称" /></label>
      <label>内容类型<select v-model="type"><option value="">全部类型</option><option value="ARTICLE">文章</option><option value="COLLECTION">专题</option><option value="COMMENT">评论</option></select></label>
      <label v-if="!isPublications">敏感等级<select v-model="level"><option value="">全部等级</option><option value="MEDIUM">中 · 送审</option><option value="HIGH">高 · 复核</option></select></label>
      <label v-if="!isPublications">提交时间<select v-model="days"><option value="">全部时间</option><option value="1">最近24小时</option><option value="7">最近7天</option><option value="30">最近30天</option></select></label>
      <button class="btn btn-primary" type="submit">查询</button><button class="btn btn-ghost" type="button" @click="router.push({ query: { status } })">重置</button>
    </form>
    <div v-if="loading" class="rv-empty" aria-live="polite"><span class="rv-spinner"></span><h2>正在读取内容</h2><p>稍等片刻，正在获取最新处理状态。</p></div>
    <div v-else-if="error" class="rv-empty" role="alert"><h2>暂时无法读取</h2><p>{{ error }}</p><button class="btn btn-primary" @click="load">重试</button></div>
    <template v-else-if="isNoted"><p class="rv-result-count">共 {{ noted.length }} 条低敏感放行记录 · 不需要人工审批，但可以事后查看和处理</p>
      <aside class="rv-notice" role="note"><strong>低敏感怎么处理？</strong> 命中低敏感规则的内容已经公开发布，系统只留痕，不拦截。你可以：<b>查看内容</b>确认是否真的有问题；确有问题时在“查看与下架”里<b>下架</b>（须填原因）；觉得规则太松时<b>调整该词的等级</b>（调高后只影响之后的提交）；没有问题就<b>标记已阅</b>。</aside>
      <div v-if="!noted.length" class="rv-empty"><h2>暂无低敏感记录</h2></div>
      <article v-for="n in noted" :key="n.docId" class="rv-item"><div class="rv-type-icon">低</div><div class="rv-item-copy"><div class="rv-tags"><span class="rv-badge neutral">低敏感</span><span class="rv-badge" :class="n.published ? 'green' : 'neutral'">{{ n.published ? '已放行 · 公开中' : '已不在公开中' }}</span><span v-if="n.reviewed" class="rv-badge green">已阅 · {{ n.reviewedBy }}</span></div><h2>{{ n.title }}</h2><p>{{ n.excerpt }}</p><small>命中：{{ n.words.join('、') }} · {{ n.authorName }} · {{ time(n.at) }}</small></div>
        <div class="rv3-actions"><button class="btn btn-ghost" @click="openPublication(n.docId)">查看内容 ↗</button><button class="btn btn-ghost" @click="markNoted(n)">{{ n.reviewed ? '取消已阅' : '标记已阅' }}</button><button class="btn btn-ghost" @click="openRule(n)">调整规则等级</button></div></article></template>
    <template v-else-if="isPublications"><p class="rv-result-count">共 {{ visiblePublications.length }} 条{{ status === 'DOWN' ? '已下架' : '有效发布' }}内容</p><div v-if="!visiblePublications.length" class="rv-empty"><h2>这里还没有内容</h2><p>试试调整类型或搜索条件。</p></div>
      <article v-for="item in visiblePublications" :key="item.id" class="rv-item"><div class="rv-type-icon">{{ typeLabel(item.type).slice(0, 1) }}</div><div class="rv-item-copy"><div class="rv-tags"><span class="rv-badge">{{ typeLabel(item.type) }}</span><span v-if="item.legacy" class="rv-badge neutral">历史发布基线</span><span class="rv-badge" :class="item.down ? 'danger' : 'green'">{{ item.down ? '已下架' : '已发布' }}</span></div><h2>{{ item.title }}</h2><p>{{ item.body }}</p><small>{{ item.authorName }} · 发布版 v{{ item.version }} · {{ item.visibility === 'RESTRICTED' ? '受限可见' : '公开可见' }}</small></div><button class="btn btn-ghost" @click="open(item, 'PUBLICATION')">{{ item.down ? '复核恢复' : '查看与下架' }} ↗</button></article></template>
    <template v-else><p class="rv-result-count">共 {{ result?.total || 0 }} 条提交 · 按提交时间排序</p>
      <div v-if="!result?.items.length" class="rv-empty"><span class="rv-empty-mark">✓</span><h2>{{ status === 'PENDING' ? '暂时没有待处理内容' : '没有符合条件的提交' }}</h2><p>没有命中或只命中低敏感的内容会直接发布，不会进入这里。</p></div>
      <article v-for="item in result?.items" :key="item.id" class="rv-item rv3-row"><div class="rv-type-icon">{{ typeLabel(item.type).slice(0, 1) }}</div>
        <div class="rv-item-copy"><div class="rv-tags"><span class="rv-badge">{{ typeLabel(item.type) }}</span><span class="rv-badge" :class="item.level === 'HIGH' ? 'danger' : 'amber'">{{ item.level === 'HIGH' ? '高敏感 · 人工复核' : '中敏感 · 送审' }}</span><span v-if="item.status !== 'PENDING'" class="rv-badge neutral">{{ stateLabel(item.status) }}</span><span v-if="item.self" class="rv-badge neutral">本人内容 · 禁止自审</span></div>
          <h2>{{ item.snapshot.title }}</h2><p>{{ item.snapshot.body }}</p>
          <ol class="rv3-steps" aria-label="流转进度"><li v-for="(label, n) in ['已提交', '已检测', item.level === 'HIGH' ? '自动不通过' : '已送审', item.status === 'PENDING' ? '等待处理' : stateLabel(item.status)]" :key="n" :class="{ done: n < stepIndex(item), now: n === stepIndex(item) - 1 }">{{ label }}</li></ol>
          <small>{{ item.authorName }} · {{ time(item.createdAt) }} · 提交版 v{{ item.snapshot.version }}</small></div>
        <button class="btn btn-ghost" @click="open(item)">{{ item.status === 'PENDING' ? '查看审批' : '查看结果' }} ↗</button></article>
      <PageNav v-if="result?.total" :page="result.page" :size="result.size" :total="result.total" @go="n => router.push({ query: { ...route.query, page: n } })" /></template>
    <p class="rv-footnote">审批仅针对当前提交快照。账号停用不会自动下架已发布作品；保存修改稿也不会覆盖发布版。</p>
  </AdminLayout>
</template>
