<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { api } from '../lib/api'
const props = defineProps({ user: { type: Object, default: null } })
const router = useRouter()
const items = ref([]); const page = ref(-1); const total = ref(0); const loading = ref(true); const loadingMore = ref(false); const error = ref('')
const hasMore = computed(() => items.value.length < total.value)
let requestId = 0
const loadPage = async (reset = false) => {
  if (!reset && (loadingMore.value || !hasMore.value)) return
  const requestedPage = reset ? 0 : page.value + 1
  const currentRequest = ++requestId
  if (reset) { loading.value = true; loadingMore.value = false; items.value = []; page.value = -1; total.value = 0; error.value = '' }
  else { loadingMore.value = true; error.value = '' }
  try {
    const result = await api(`/api/collections?page=${requestedPage}&size=20`)
    if (currentRequest !== requestId) return
    const combined = reset ? result.items : [...items.value, ...result.items]
    items.value = [...new Map(combined.map(item => [item.id, item])).values()]
    page.value = result.page; total.value = result.total
  } catch (e) {
    if (currentRequest === requestId) error.value = e.message
  } finally {
    if (currentRequest === requestId) { loading.value = false; loadingMore.value = false }
  }
}
const retry = () => loadPage(page.value < 0 || items.value.length === 0)
onMounted(() => loadPage(true))
const add = async (id) => {
  if (!props.user) { router.push({ path: '/', query: { signin: '1', next: '/my/collections' } }); return }
  try { await api(`/api/me/collections/selected/${id}`, { method: 'PUT' }); router.push('/my/collections') }
  catch (e) { error.value = e.message }
}
</script>
<template>
  <section class="page-hero"><span class="kicker">COLLECTIONS</span><h1>一起建立的<br /><em>知识地图。</em></h1><p>专题把分散的经验串成路径，让每一次探索都能找到下一站。</p></section>
  <section class="section collections-section"><div class="section-heading"><div><span class="kicker">COMMUNITY CURATED</span><h2>正在生长的专题</h2></div><RouterLink class="text-link" to="/my/collections">查看我的自选 →</RouterLink></div><p v-if="error" class="form-error" role="alert">{{ error }}</p><div class="collections-grid"><div v-if="loading && !items.length" class="loading">正在加载专题…</div><div v-else-if="!items.length && !error" class="empty-state">专题广场还没有内容，登录后创建第一个专题吧。</div><article v-for="(item, index) in items" :key="item.id" class="collection-card"><div class="collection-icon">{{ index % 2 ? '⌁' : '✦' }}</div><div><RouterLink class="collection-title-link" :to="`/collections/${item.id}`"><h3>{{ item.name }}</h3></RouterLink><p>{{ item.description }} · {{ item.ownerName }} 创建</p></div><div class="collection-action"><div class="collection-count"><strong>{{ item.itemCount }}</strong>篇内容</div><button class="btn btn-outline" @click="add(item.id)">加入自选</button></div></article></div><div v-if="error || hasMore" class="pagination-control" aria-live="polite"><span v-if="total">已加载 {{ items.length }} / {{ total }} 个专题</span><button type="button" class="btn btn-outline" :disabled="loading || loadingMore" @click="error ? retry() : loadPage(false)">{{ error ? '重试' : loadingMore ? '正在加载…' : '加载更多专题' }}</button></div></section><section class="page-cta"><h2>你想建立怎样的专题？</h2><p>从一个问题开始，邀请更多人一起补全答案。</p><RouterLink class="btn btn-primary" to="/me/collections/new">开始创建 <span>→</span></RouterLink></section>
</template>
