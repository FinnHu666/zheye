<script setup>
// 我的内容：文章与话题、我创建的专题、自选专题放在同一页，每条内容直接带自己的审核流转（提交→检测→分流→处理→结果）。
import { computed, reactive, ref, watch, onMounted } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PersonalLayout from '../components/PersonalLayout.vue'
import FlowTimeline from '../components/FlowTimeline.vue'
import SelectedCollections from '../components/SelectedCollections.vue'
import { api, dateLabel } from '../lib/api'
import { can } from '../lib/session'
import { flowChip, timeLabel } from '../lib/reviewText'

const route = useRoute(), router = useRouter()
const tabs = computed(() => [['ARTICLE', '文章与话题'], ...(can('collection.manage') ? [['COLLECTION', '我创建的专题']] : []), ...(can('selection.manage') ? [['SELECTED', '自选专题']] : [])])
const type = computed(() => tabs.value.some(([k]) => k === route.query.type) ? String(route.query.type) : 'ARTICLE')
const pick = (k) => router.replace({ query: k === 'ARTICLE' ? {} : { type: k } })

const loading = ref(true), loadingMore = ref(false), error = ref(''), message = ref(''), busy = ref(false)
const posts = ref([]), hasMore = ref(false), postPage = ref(-1)
const collections = ref([]), collectionMore = ref(false), collectionPage = ref(0)
const unpublished = ref([])
const flowMap = reactive({}), opened = reactive({})
let token = 0

const key = (kind, id) => `${kind}:${id}`
const latest = (kind, id) => flowMap[key(kind, id)]?.[0]
const toggle = (id) => { opened[id] = !opened[id] }

async function loadFlows(kind, ids) {
  if (!ids.length) return
  const result = await api(`/api/review/mine/content?type=${kind}&ids=${ids.join(',')}`)
  for (const id of ids) flowMap[key(kind, id)] = result.items[String(id)] || []
}

async function loadPosts(reset = false) {
  if (!reset && (loadingMore.value || !hasMore.value)) return
  const mine = ++token, next = reset ? 0 : postPage.value + 1
  if (reset) { loading.value = true; posts.value = []; postPage.value = -1; hasMore.value = false } else loadingMore.value = true
  error.value = ''
  try {
    if (reset) unpublished.value = (await api('/api/review/mine/unpublished?type=ARTICLE')).items
    const result = await api(`/api/me/posts?page=${next}&size=20`)
    if (mine !== token) return
    const seen = new Set(posts.value.map((p) => p.id)), fresh = result.items.filter((p) => !seen.has(p.id))
    posts.value = [...posts.value, ...fresh]; postPage.value = result.page; hasMore.value = result.hasMore
    await loadFlows('ARTICLE', fresh.map((p) => p.id))
  } catch (e) { if (mine === token) error.value = e.message }
  finally { if (mine === token) { loading.value = false; loadingMore.value = false } }
}

async function loadCollections(reset = false) {
  if (!reset && (loadingMore.value || !collectionMore.value)) return
  const mine = ++token, next = reset ? 0 : collectionPage.value
  if (reset) { loading.value = true; collections.value = []; collectionPage.value = 0 } else loadingMore.value = true
  error.value = ''
  try {
    const result = await api(`/api/me/collections?page=${next}&size=20`)
    if (mine !== token) return
    collections.value = [...collections.value, ...result.items]; collectionMore.value = result.hasMore; collectionPage.value = next + 1
    await loadFlows('COLLECTION', result.items.map((c) => c.id))
  } catch (e) { if (mine === token) error.value = e.message }
  finally { if (mine === token) { loading.value = false; loadingMore.value = false } }
}

const load = () => {
  message.value = ''
  if (type.value === 'COLLECTION') loadCollections(true)
  else if (type.value === 'SELECTED') loading.value = false
  else loadPosts(true)
}

async function withdraw(flow) {
  if (busy.value || !window.confirm('撤回后这条提交不会再被审批，私人原稿保留。确定撤回？')) return
  busy.value = true; message.value = ''
  try { message.value = (await api(`/api/review/mine/${flow.taskId}/withdraw`, { method: 'POST', body: '{}' })).message; load() }
  catch (e) { error.value = e.message } finally { busy.value = false }
}

const duplicate = async (id, structureOnly) => {
  busy.value = true; error.value = ''
  try { const result = await api(`/api/me/collections/${id}/duplicate`, { method: 'POST', body: JSON.stringify({ structureOnly }) }); await router.push(`/me/collections/${result.id}/edit`) }
  catch (e) { error.value = e.message } finally { busy.value = false }
}
const statusLabel = (status) => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }[status] || status)

watch(type, load)
onMounted(load)
</script>

<template>
  <PersonalLayout kicker="MY CONTENT" title="我的内容" desc="你创作的文章、创建的专题和自选专题都在这里；文章与专题可展开查看各自的审核流转。">
    <template #actions><div class="heading-actions"><template v-if="type === 'COLLECTION'"><RouterLink class="btn btn-primary" to="/me/collections/ai/new">AI 创建专题</RouterLink><RouterLink class="btn btn-outline" to="/me/collections/new">直接编写</RouterLink></template><RouterLink v-else-if="type === 'SELECTED'" class="text-link" to="/collections">浏览专题广场 →</RouterLink><RouterLink v-else class="btn btn-primary" to="/?create=post">开始创作 ↗</RouterLink></div></template>
    <div class="rv-tabs mc-tabs" role="tablist" aria-label="内容类型"><button v-for="[k, label] in tabs" :key="k" role="tab" :aria-selected="type === k" :class="{ active: type === k }" @click="pick(k)">{{ label }}</button></div>
    <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="loading && type !== 'SELECTED' && !posts.length && !collections.length" class="loading">正在加载内容…</div>

    <template v-else-if="type === 'ARTICLE'">
      <section v-if="unpublished.length" class="mc-group" aria-label="审核中与未通过">
        <h2>审核中与未通过</h2>
        <article v-for="flow in unpublished" :key="flow.id" class="mc-row">
          <div class="mc-main"><div class="rv-tags"><span class="rv-badge" :class="flowChip(flow)[1]">{{ flowChip(flow)[0] }}</span></div><h3>{{ flow.title }}</h3><small>提交版 v{{ flow.version }} · {{ timeLabel(flow.startedAt) }}</small></div>
          <div class="mc-actions"><button class="btn btn-ghost" @click="toggle('u' + flow.id)">{{ opened['u' + flow.id] ? '收起流转' : '查看流转' }}</button><button v-if="flow.taskId && flow.status === 'IN_REVIEW'" class="btn btn-ghost" :disabled="busy" @click="withdraw(flow)">撤回</button></div>
          <div v-if="opened['u' + flow.id]" class="mc-flow"><p v-if="flow.level === 'HIGH'" class="rv-footnote">高敏感内容不显示具体命中词。</p><FlowTimeline :flow="flow" audience="author" /></div>
        </article>
      </section>
      <section class="mc-group" aria-label="已发布">
        <h2>已发布</h2>
        <div v-if="!posts.length && !error" class="empty-state">还没有发布内容。分享你的第一个想法吧。</div>
        <article v-for="post in posts" :key="post.id" class="mc-row">
          <div class="mc-main"><div class="rv-tags"><span class="post-tag">{{ post.type === 'ARTICLE' ? '文章' : '话题' }} · {{ dateLabel(post.createdAt) }}</span><span v-if="latest('ARTICLE', post.id)" class="rv-badge" :class="flowChip(latest('ARTICLE', post.id))[1]">{{ flowChip(latest('ARTICLE', post.id))[0] }}</span></div><RouterLink class="collection-title-link" :to="{ path: '/', query: { post: post.id } }"><h3>{{ post.title }}</h3></RouterLink><p>{{ post.summary }}</p></div>
          <div class="mc-actions"><span class="row-stat">♡ {{ post.likes }}</span><button class="btn btn-ghost" @click="toggle('p' + post.id)">{{ opened['p' + post.id] ? '收起流转' : '审核流转' }}</button></div>
          <div v-if="opened['p' + post.id]" class="mc-flow"><p v-if="!flowMap[key('ARTICLE', post.id)]?.length" class="rv-footnote">这条内容没有审核流转记录（可能发布于审核功能上线之前）。</p><template v-else><div v-for="flow in flowMap[key('ARTICLE', post.id)]" :key="flow.id" class="mc-flow-one"><strong>提交版 v{{ flow.version }} · {{ timeLabel(flow.startedAt) }}</strong><FlowTimeline :flow="flow" audience="author" /></div></template></div>
        </article>
        <div v-if="hasMore" class="pagination-control"><span>已加载 {{ posts.length }} 条</span><button type="button" class="btn btn-outline" :disabled="loadingMore" @click="loadPosts(false)">{{ loadingMore ? '正在加载…' : '加载更多内容' }}</button></div>
      </section>
    </template>

    <template v-else-if="type === 'COLLECTION'">
      <div v-if="!collections.length && !error" class="empty-state">还没有创建专题。<br /><RouterLink to="/me/collections/new">选择模板开始制作 →</RouterLink></div>
      <article v-for="item in collections" :key="item.id" class="mc-row">
        <div class="mc-main"><div class="rv-tags"><span class="post-tag">{{ statusLabel(item.status) }} · {{ item.itemCount }} 个{{ item.editorType === 'BUILDER' ? '模块' : '条目' }}</span><span v-if="latest('COLLECTION', item.id)" class="rv-badge" :class="flowChip(latest('COLLECTION', item.id))[1]">{{ flowChip(latest('COLLECTION', item.id))[0] }}</span></div><h3>{{ item.name }}</h3><p>{{ item.description }}</p></div>
        <div class="mc-actions"><button v-if="item.editorType !== 'BUILDER'" class="btn btn-outline" :disabled="busy" @click="duplicate(item.id, false)">复制内容</button><button v-if="item.editorType !== 'BUILDER'" class="btn btn-outline" :disabled="busy" @click="duplicate(item.id, true)">只用结构</button><RouterLink :to="item.editorType === 'BUILDER' ? `/me/collections/${item.id}/builder/preview` : `/me/collections/${item.id}/preview`" class="text-link">私人预览</RouterLink><RouterLink class="btn btn-outline" :to="item.editorType === 'BUILDER' ? `/me/collections/${item.id}/builder` : `/me/collections/${item.id}/edit`">编辑</RouterLink><RouterLink v-if="item.status === 'PUBLISHED'" class="text-link" :to="`/collections/${item.id}`">查看公开页</RouterLink><button class="btn btn-ghost" @click="toggle('c' + item.id)">{{ opened['c' + item.id] ? '收起流转' : '审核流转' }}</button></div>
        <div v-if="opened['c' + item.id]" class="mc-flow"><p v-if="!flowMap[key('COLLECTION', item.id)]?.length" class="rv-footnote">还没有发布提交，因此没有审核流转。</p><template v-else><div v-for="flow in flowMap[key('COLLECTION', item.id)]" :key="flow.id" class="mc-flow-one"><strong>提交版 v{{ flow.version }} · {{ timeLabel(flow.startedAt) }}</strong><FlowTimeline :flow="flow" audience="author" /></div></template></div>
      </article>
      <div v-if="collectionMore" class="pagination-control"><button class="btn btn-outline" :disabled="loadingMore" @click="loadCollections(false)">{{ loadingMore ? '正在加载…' : '加载更多我的专题' }}</button></div>
    </template>

    <SelectedCollections v-else-if="type === 'SELECTED'" />
  </PersonalLayout>
</template>
