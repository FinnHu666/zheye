<script setup>
import { onMounted, ref, computed, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, dateLabel } from '../lib/api'
const emit = defineEmits(['write', 'toast'])
const props = defineProps({ user: { type: Object, default: null } })
const route = useRoute()
const posts = ref([]); const filter = ref('ALL'); const postsLoading = ref(true); const postsLoadingMore = ref(false); const postsError = ref(''); const postsPage = ref(-1); const postsTotal = ref(0)
const detail = ref(null); const detailLoading = ref(false); const detailError = ref(''); const comments = ref([]); const commentBody = ref('')
const siteStats = ref(null); const numberFormatter = new Intl.NumberFormat('zh-CN')
const commentsPage = ref(-1); const commentsTotal = ref(0); const commentsLoading = ref(false); const commentsError = ref(''); const commentSending = ref(false)
let postsRequestId = 0; let postQueryRequestId = 0; let detailRequestId = 0; let commentsRequestId = 0
const ownCollections = ref([]); const selectedCollectionId = ref(''); const collectionMessage = ref('')
const filteredPosts = computed(() => posts.value)
const hasMorePosts = computed(() => posts.value.length < postsTotal.value)
const hasMoreComments = computed(() => comments.value.length < commentsTotal.value)
const displayCount = value => Number.isFinite(value) ? numberFormatter.format(value) : '—'
const loadPosts = async (reset = false) => {
  if (!reset && (postsLoadingMore.value || !hasMorePosts.value)) return
  const page = reset ? 0 : postsPage.value + 1
  const requestId = ++postsRequestId
  if (reset) {
    postsLoading.value = true; postsLoadingMore.value = false; postsError.value = ''
    posts.value = []; postsPage.value = -1; postsTotal.value = 0
  } else {
    postsLoadingMore.value = true; postsError.value = ''
  }
  const typeQuery = filter.value === 'ALL' ? '' : `&type=${encodeURIComponent(filter.value)}`
  try {
    const result = await api(`/api/posts?page=${page}&size=20${typeQuery}`)
    if (requestId !== postsRequestId) return
    const combined = reset ? result.items : [...posts.value, ...result.items]
    posts.value = [...new Map(combined.map(item => [item.id, item])).values()]
    postsPage.value = result.page; postsTotal.value = result.total
  } catch (error) {
    if (requestId === postsRequestId) postsError.value = error.message
  } finally {
    if (requestId === postsRequestId) {
      postsLoading.value = false; postsLoadingMore.value = false
      if (route.query.post) openFromQuery()
    }
  }
}
const retryPosts = () => loadPosts(postsPage.value < 0 || posts.value.length === 0)
const loadStats = async () => {
  try { siteStats.value = await api('/api/stats') }
  catch (error) { emit('toast', error.message) }
}
const changeFilter = value => {
  if (filter.value !== value) filter.value = value
}
const sortComments = (items) => items.sort((left, right) => new Date(left.createdAt) - new Date(right.createdAt) || left.id - right.id)
const loadComments = async (reset = false) => {
  const postId = detail.value?.id
  if (!postId || commentsLoading.value) return
  const page = reset ? 0 : commentsPage.value + 1
  const requestId = ++commentsRequestId
  commentsLoading.value = true
  commentsError.value = ''
  try {
    const result = await api(`/api/posts/${postId}/comments?page=${page}&size=20`)
    if (requestId !== commentsRequestId || detail.value?.id !== postId) return
    const combined = reset ? result.items : [...comments.value, ...result.items]
    comments.value = sortComments([...new Map(combined.map(item => [item.id, item])).values()])
    commentsPage.value = result.page
    commentsTotal.value = result.total
  } catch (error) {
    if (requestId === commentsRequestId) { commentsError.value = error.message; emit('toast', error.message) }
  } finally {
    if (requestId === commentsRequestId) commentsLoading.value = false
  }
}
const openPost = async (post, bodyLoaded = false) => {
  const requestId = ++detailRequestId
  commentsRequestId++; commentsLoading.value = false; detail.value = post; detailLoading.value = !bodyLoaded; detailError.value = ''
  collectionMessage.value = ''; comments.value = []; commentsPage.value = -1; commentsTotal.value = 0; commentsError.value = ''; commentBody.value = ''
  loadComments(true)
  if (!bodyLoaded) {
    try {
      const fullPost = await api(`/api/posts/${encodeURIComponent(post.id)}`)
      if (requestId !== detailRequestId) return
      detail.value = fullPost
    } catch (error) {
      if (requestId === detailRequestId) { detailError.value = error.message; emit('toast', error.message) }
    } finally {
      if (requestId === detailRequestId) detailLoading.value = false
    }
  }
  if (props.user) {
    try {
      const result = await api('/api/me/collections')
      if (requestId !== detailRequestId) return
      ownCollections.value = result.items; selectedCollectionId.value = ownCollections.value[0]?.id || ''
    } catch {
      if (requestId === detailRequestId) ownCollections.value = []
    }
  }
}
const closePost = () => { detailRequestId++; commentsRequestId++; detail.value = null; detailLoading.value = false; detailError.value = ''; commentsLoading.value = false }
const retryDetail = () => { if (detail.value) openPost(detail.value) }
const openFromQuery = async () => {
  const targetId = typeof route.query.post === 'string' ? route.query.post : ''
  if (!targetId || !/^\d+$/.test(targetId)) return
  if (String(detail.value?.id) === targetId) return
  const requestId = ++postQueryRequestId
  const listed = posts.value.find(item => String(item.id) === targetId)
  if (listed) { if (requestId === postQueryRequestId && String(route.query.post) === targetId) openPost(listed); return }
  try {
    const post = await api(`/api/posts/${encodeURIComponent(targetId)}`)
    if (requestId === postQueryRequestId && String(route.query.post) === targetId) openPost(post, true)
  } catch (error) {
    if (requestId === postQueryRequestId && String(route.query.post) === targetId) emit('toast', error.message)
  }
}
watch(filter, () => loadPosts(true))
watch(() => route.query.post, () => { if (route.query.post) openFromQuery() })
const retryComments = () => loadComments(commentsPage.value < 0)
const loadMoreComments = () => { if (hasMoreComments.value) loadComments() }
const addToCollection = async () => { if (!selectedCollectionId.value || !detail.value) return; try { const result = await api(`/api/me/collections/${selectedCollectionId.value}/posts/${detail.value.id}`, { method: 'PUT' }); collectionMessage.value = `已加入「${result.name}」`; emit('toast', collectionMessage.value) } catch (error) { collectionMessage.value = error.message } }
const like = async () => { const postId = detail.value?.id; if (!postId) return; const updated = await api(`/api/posts/${postId}/like`, { method: 'POST' }); if (detail.value?.id !== postId) return; detail.value = updated; const index = posts.value.findIndex(p => p.id === postId); if (index >= 0) posts.value[index] = { ...posts.value[index], likes: updated.likes } }
const comment = async () => {
  const postId = detail.value?.id
  if (!commentBody.value.trim() || !postId || commentSending.value) return
  commentSending.value = true
  try {
    const created = await api(`/api/posts/${postId}/comments`, { method: 'POST', body: JSON.stringify({ body: commentBody.value }) })
    if (detail.value?.id === postId) {
      comments.value = sortComments([...new Map([...comments.value, created].map(item => [item.id, item])).values()])
      commentsTotal.value += 1
      commentBody.value = ''
    }
  } catch (error) { emit('toast', error.message) } finally { commentSending.value = false }
}
onMounted(() => { loadPosts(true); loadStats() })
</script>
<template>
  <section class="hero"><div class="eyebrow"><span class="pulse"></span> 为创造者准备的开放社区</div><h1>记录思考，连接<br /><em>每一种可能。</em></h1><p>分享技术洞见，发起真诚讨论，和有趣的人一起搭建属于我们的知识宇宙。</p><div class="hero-actions"><button class="btn btn-primary btn-large" @click="emit('write')">分享你的故事 <span>→</span></button><a class="text-link" href="#content">随便逛逛 <span>↓</span></a></div><div class="hero-stats" aria-live="polite"><div><strong>{{ displayCount(siteStats?.creatorCount) }}</strong><span>创作者</span></div><i></i><div><strong>{{ displayCount(siteStats?.contentCount) }}</strong><span>公开内容</span></div><i></i><div><strong>{{ displayCount(siteStats?.collectionCount) }}</strong><span>专题目录</span></div></div></section>
  <section id="content" class="section content-section"><div class="section-heading"><div><span class="kicker">DISCOVER</span><h2>今日值得阅读</h2></div><div class="tabs" role="tablist" aria-label="筛选文章话题"><button class="tab" :class="{ active: filter === 'ALL' }" role="tab" :aria-selected="filter === 'ALL'" @click="changeFilter('ALL')">全部</button><button class="tab" :class="{ active: filter === 'ARTICLE' }" role="tab" :aria-selected="filter === 'ARTICLE'" @click="changeFilter('ARTICLE')">文章</button><button class="tab" :class="{ active: filter === 'TOPIC' }" role="tab" :aria-selected="filter === 'TOPIC'" @click="changeFilter('TOPIC')">话题</button></div></div><div class="posts-grid"><div v-if="postsLoading && !filteredPosts.length" class="loading">正在收集好内容…</div><div v-else-if="!filteredPosts.length && !postsError" class="empty-state">这个分类还没有内容，来发布第一篇吧。</div><article v-for="post in filteredPosts" :key="post.id" class="post-card" tabindex="0" @click="openPost(post)" @keydown.enter="openPost(post)"><span class="post-tag">{{ post.type === 'ARTICLE' ? '深度文章' : '社区话题' }}</span><h3>{{ post.title }}</h3><p>{{ post.summary }}</p><div class="post-meta"><span><i class="avatar">{{ post.authorName.slice(0, 1) }}</i>{{ post.authorName }}</span><span>♡ {{ post.likes }} · {{ dateLabel(post.createdAt) }}</span></div></article></div><div v-if="postsError || hasMorePosts" class="pagination-control" aria-live="polite"><span v-if="postsTotal">已加载 {{ posts.length }} / {{ postsTotal }} 条</span><span v-if="postsError" class="form-error" role="alert">{{ postsError }}</span><button type="button" class="btn btn-outline" :disabled="postsLoading || postsLoadingMore" @click="postsError ? retryPosts() : loadPosts(false)">{{ postsError ? '重试' : postsLoadingMore ? '正在加载…' : '加载更多内容' }}</button></div></section>
  <div v-if="detail" class="overlay" @click.self="closePost">
    <section class="modal modal-detail">
      <button class="modal-close" aria-label="关闭" @click="closePost">×</button>
      <span class="post-tag">{{ detail.type === 'ARTICLE' ? '文章' : '话题' }}</span>
      <h2>{{ detail.title }}</h2>
      <div class="detail-meta">{{ detail.authorName }} · {{ dateLabel(detail.createdAt) }} · <button class="like-button" @click="like">♡ {{ detail.likes }}</button></div>
      <div v-if="detailLoading" class="loading">正在加载正文…</div>
      <p v-else-if="detailError" class="form-error" role="alert">{{ detailError }} <button type="button" class="btn btn-outline" @click="retryDetail">重试加载正文</button></p>
      <div v-else class="detail-body">{{ detail.body }}</div>
      <section v-if="user" class="collection-add"><div><h3>收录到我的专题</h3><p v-if="!ownCollections.length">先创建一个专题，再整理相关内容。<RouterLink to="/me/collections">创建专题 →</RouterLink></p><form v-else @submit.prevent="addToCollection"><select v-model="selectedCollectionId" aria-label="选择专题"><option v-for="item in ownCollections" :key="item.id" :value="item.id">{{ item.name }}</option></select><button class="btn btn-outline">加入专题</button></form><span v-if="collectionMessage" class="collection-feedback" role="status">{{ collectionMessage }}</span></div></section>
      <section class="discussion" aria-live="polite">
        <h3>讨论 <span v-if="commentsTotal">({{ commentsTotal }})</span></h3>
        <div v-if="commentsLoading && !comments.length" class="loading">正在加载讨论…</div>
        <div v-else-if="commentsError && !comments.length" class="empty" role="alert">{{ commentsError }} <button type="button" class="btn btn-outline" @click="retryComments">重试</button></div>
        <div v-else-if="!comments.length" class="empty">还没有讨论，欢迎留下第一个想法。</div>
        <div v-for="item in comments" :key="item.id" class="comment"><strong>{{ item.authorName }}</strong><p>{{ item.body }}</p></div>
        <p v-if="commentsError && comments.length" class="empty" role="alert">{{ commentsError }} <button type="button" class="btn btn-outline" @click="retryComments">重试</button></p>
        <button v-if="hasMoreComments" type="button" class="btn btn-outline" :disabled="commentsLoading" @click="loadMoreComments">{{ commentsLoading ? '正在加载…' : '加载更多讨论' }}</button>
        <form @submit.prevent="comment" :aria-busy="commentSending">
          <textarea v-model="commentBody" rows="2" maxlength="1000" required placeholder="登录后写下你的想法…"></textarea>
          <button class="btn btn-primary" :disabled="commentSending || !commentBody.trim()">{{ commentSending ? '正在发布…' : '参与讨论' }}</button>
        </form>
      </section>
    </section>
  </div>
</template>
