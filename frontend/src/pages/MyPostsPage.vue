<script setup>
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PersonalNav from '../components/PersonalNav.vue'
import { api, dateLabel } from '../lib/api'
const posts = ref([]); const loading = ref(true); const loadingMore = ref(false); const error = ref(''); const page = ref(-1); const hasMore = ref(false)
let requestId = 0
const load = async (reset = false) => {
  if (!reset && (loadingMore.value || !hasMore.value)) return
  const nextPage = reset ? 0 : page.value + 1
  const currentRequest = ++requestId
  if (reset) { loading.value = true; loadingMore.value = false; posts.value = []; page.value = -1; hasMore.value = false; error.value = '' }
  else { loadingMore.value = true; error.value = '' }
  try {
    const result = await api(`/api/me/posts?page=${nextPage}&size=20`)
    if (currentRequest !== requestId) return
    const combined = reset ? result.items : [...posts.value, ...result.items]
    posts.value = [...new Map(combined.map(item => [item.id, item])).values()]
    page.value = result.page; hasMore.value = result.hasMore
  } catch (e) {
    if (currentRequest === requestId) error.value = e.message
  } finally {
    if (currentRequest === requestId) { loading.value = false; loadingMore.value = false }
  }
}
const retry = () => load(page.value < 0 || posts.value.length === 0)
onMounted(() => load(true))
</script>
<template>
  <section class="page-hero personal-hero"><span class="kicker">YOUR WRITING</span><h1>每一份思考，<br /><em>都值得留存。</em></h1><p>回看你发布的文章与讨论。</p></section>
  <section class="section personal-section"><PersonalNav /><div class="section-heading"><div><span class="kicker">MY POSTS</span><h2>我的内容</h2></div><RouterLink class="btn btn-primary" to="/?create=post">开始创作 ↗</RouterLink></div><p v-if="error" class="form-error" role="alert">{{ error }}</p><div v-if="loading && !posts.length" class="loading">正在加载内容…</div><div v-else-if="!posts.length && !error" class="empty-state">还没有发布内容。分享你的第一个想法吧。</div><div v-else class="personal-list"><article v-for="post in posts" :key="post.id" class="personal-row"><div><span class="post-tag">{{ post.type === 'ARTICLE' ? '文章' : '话题' }} · {{ dateLabel(post.createdAt) }}</span><RouterLink class="collection-title-link" :to="{ path: '/', query: { post: post.id } }"><h3>{{ post.title }}</h3></RouterLink><p>{{ post.summary }}</p></div><span class="row-stat">♡ {{ post.likes }}</span></article></div><div v-if="error || hasMore" class="pagination-control" aria-live="polite"><span v-if="posts.length">已加载 {{ posts.length }} 条</span><span v-if="error" class="form-error" role="alert">{{ error }}</span><button type="button" class="btn btn-outline" :disabled="loading || loadingMore" @click="error ? retry() : load(false)">{{ error ? '重试' : loadingMore ? '正在加载…' : '加载更多内容' }}</button></div></section>
</template>
