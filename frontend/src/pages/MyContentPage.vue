<script setup>
// 我的内容：文章、我创建的专题、自选专题放在同一页。文章页签是作者的草稿与发布管理（FINNS-18）：
// 保存只是私人存稿，发布才检测并对外；状态/搜索/页码都在地址里，直达、刷新、前进后退一致。
import { computed, reactive, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PersonalLayout from '../components/PersonalLayout.vue'
import FlowTimeline from '../components/FlowTimeline.vue'
import SelectedCollections from '../components/SelectedCollections.vue'
import { api } from '../lib/api'
import { can } from '../lib/session'
import { flowChip, timeLabel } from '../lib/reviewText'
import { articles, STATE_FILTERS, publicChip, workChip, errorText } from '../lib/articles'

const route = useRoute(), router = useRouter()
const tabs = computed(() => [['ARTICLE', '文章'], ...(can('collection.manage') ? [['COLLECTION', '我创建的专题']] : []), ...(can('selection.manage') ? [['SELECTED', '自选专题']] : [])])
const type = computed(() => tabs.value.some(([k]) => k === route.query.type) ? String(route.query.type) : 'ARTICLE')
const pick = (k) => router.replace({ query: k === 'ARTICLE' ? {} : { type: k } })

// ---- 文章页签：状态/搜索/页码来自地址
const articleState = computed(() => STATE_FILTERS.some(([k]) => k === route.query.state) ? String(route.query.state) : 'ALL')
const articleQuery = computed(() => String(route.query.q || ''))
const articlePage = computed(() => Math.max(0, Number.parseInt(route.query.page, 10) || 0))
const filtered = computed(() => !!articleQuery.value || articleState.value !== 'ALL')
const search = ref(articleQuery.value)
const goArticles = (patch) => router.push({ path: '/me/posts', query: Object.fromEntries(Object.entries({ state: articleState.value, q: articleQuery.value, ...patch }).filter(([k, v]) => v && v !== 'ALL' && !(k === 'page' && v === 0))) })
const doSearch = () => goArticles({ q: search.value.trim() || undefined, page: 0 })
const resetFilters = () => { search.value = ''; router.push('/me/posts') }
const data = ref(null), confirmItem = ref(null)
const dialogText = {
  withdraw: ['撤回公开？', '撤回后读者、列表、搜索和收藏里都看不到这篇文章，待处理的提交也会作废；私人稿保留，重新发布会重新检测。', '确认撤回'],
  delete: ['删除这份私人草稿？', '只能删除从未公开过的私人草稿，删除后无法恢复。', '确认删除'],
}

const loading = ref(true), loadingMore = ref(false), error = ref(''), message = ref(''), busy = ref(false)
const collections = ref([]), collectionMore = ref(false), collectionPage = ref(0)
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

async function loadArticles() {
  const mine = ++token
  loading.value = true; error.value = ''
  try {
    const result = await articles.list({ state: articleState.value, q: articleQuery.value, page: articlePage.value })
    if (mine !== token) return
    if (!result.items.length && articlePage.value > 0 && result.total > 0) { goArticles({ page: Math.max(0, Math.ceil(result.total / result.size) - 1) }); return }
    data.value = result
  } catch (e) { if (mine === token) { error.value = errorText(e); data.value = null } }
  finally { if (mine === token) loading.value = false }
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
  else loadArticles()
}

async function run(item, action) {
  confirmItem.value = null; busy.value = true; error.value = ''; message.value = ''
  let failure = ''
  try {
    const call = { cancel: () => articles.cancel(item.id), withdraw: () => articles.withdraw(item.id), delete: () => articles.remove(item.id) }[action]
    message.value = (await call()).message
  } catch (e) { failure = errorText(e) }
  finally { busy.value = false; await loadArticles(); if (failure) error.value = failure }
}

const duplicate = async (id, structureOnly) => {
  busy.value = true; error.value = ''
  try { const result = await api(`/api/me/collections/${id}/duplicate`, { method: 'POST', body: JSON.stringify({ structureOnly }) }); await router.push(`/me/collections/${result.id}/edit`) }
  catch (e) { error.value = e.message } finally { busy.value = false }
}
const statusLabel = (status) => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }[status] || status)

watch(() => [type.value, articleState.value, articleQuery.value, articlePage.value], () => { search.value = articleQuery.value; load() })
const onKey = (e) => { if (e.key === 'Escape') confirmItem.value = null }
onMounted(() => { load(); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <PersonalLayout kicker="MY CONTENT" title="我的内容" desc="你创作的文章、创建的专题和自选专题都在这里；文章保存只是私人存稿，发布才会检测并对外。">
    <template #actions><div class="heading-actions"><template v-if="type === 'COLLECTION'"><RouterLink class="btn btn-primary" to="/me/collections/ai/new">AI 创建专题</RouterLink><RouterLink class="btn btn-outline" to="/me/collections/new">直接编写</RouterLink></template><RouterLink v-else-if="type === 'SELECTED'" class="text-link" to="/collections">浏览专题广场 →</RouterLink><RouterLink v-else class="btn btn-primary" to="/me/posts/new">写文章</RouterLink></div></template>
    <div class="rv-tabs mc-tabs" role="tablist" aria-label="内容类型"><button v-for="[k, label] in tabs" :key="k" role="tab" :aria-selected="type === k" :class="{ active: type === k }" @click="pick(k)">{{ label }}</button></div>
    <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="text-link" @click="load">重试</button></p>
    <div v-if="loading && type === 'ARTICLE' && !data" class="loading" role="status">正在加载内容…</div>
    <div v-else-if="loading && type === 'COLLECTION' && !collections.length" class="loading" role="status">正在加载内容…</div>

    <template v-else-if="type === 'ARTICLE'">
      <form class="ct-toolbar" role="search" @submit.prevent="doSearch">
        <label class="ct-search"><span class="sr-only">搜索标题或摘要</span><input v-model="search" type="search" maxlength="80" placeholder="搜索标题或摘要" autocomplete="off" /></label>
        <button class="btn btn-outline" type="submit">搜索</button><button v-if="filtered" class="btn btn-ghost" type="button" @click="resetFilters">清除筛选</button>
      </form>
      <div class="ct-filters"><div class="ct-chips" role="group" aria-label="状态筛选"><button v-for="[value, label] in STATE_FILTERS" :key="value" type="button" :class="{ on: articleState === value }" :aria-pressed="articleState === value" @click="goArticles({ state: value, page: 0 })">{{ label }}</button></div></div>
      <div v-if="data && !data.items.length" class="empty-state">
        <template v-if="filtered">没有符合条件的文章。<br /><button type="button" class="text-link" @click="resetFilters">清除筛选</button></template>
        <template v-else>还没有文章。从一份私人草稿开始，不完整也可以先保存。<br /><RouterLink to="/me/posts/new">写文章 →</RouterLink></template>
      </div>
      <section v-else-if="data" class="mc-group" aria-label="文章列表">
        <article v-for="item in data.items" :key="item.id" class="mc-row" :aria-busy="busy">
          <div class="mc-main">
            <div class="rv-tags"><span class="rv-badge" :class="publicChip(item.status)[1]">{{ publicChip(item.status)[0] }}</span><span class="rv-badge" :class="workChip(item.status)[1]">{{ workChip(item.status)[0] }}</span></div>
            <h3><RouterLink :to="`/me/posts/${item.id}/edit`">{{ item.title || '（未命名）' }}</RouterLink></h3>
            <p>{{ item.summary || '还没有摘要' }}</p>
            <small>工作稿 v{{ item.version }}<template v-if="item.hasPublished && item.publicTitle"> · 公开版：{{ item.publicTitle }}</template><template v-if="item.updatedAt"> · 最近处理 {{ timeLabel(item.updatedAt) }}</template></small>
          </div>
          <div class="mc-actions">
            <RouterLink class="btn btn-outline" :to="`/me/posts/${item.id}/edit`">编辑</RouterLink>
            <RouterLink class="btn btn-ghost" :to="`/me/posts/${item.id}/preview`">私人预览</RouterLink>
            <button v-if="item.canCancel" type="button" class="btn btn-ghost" :disabled="busy" @click="run(item, 'cancel')">取消送审</button>
            <button v-if="item.canWithdraw" type="button" class="btn btn-ghost" :disabled="busy" @click="confirmItem = { item, action: 'withdraw' }">撤回</button>
            <button v-if="item.canDelete" type="button" class="btn btn-ghost ct-danger" :disabled="busy" @click="confirmItem = { item, action: 'delete' }">删除草稿</button>
          </div>
        </article>
        <div v-if="data.total > data.size" class="pagination-control"><span>第 {{ data.page + 1 }} / {{ Math.ceil(data.total / data.size) }} 页 · 共 {{ data.total }} 篇</span><button type="button" class="btn btn-outline" :disabled="data.page <= 0" @click="goArticles({ page: data.page - 1 })">上一页</button><button type="button" class="btn btn-outline" :disabled="!data.hasMore" @click="goArticles({ page: data.page + 1 })">下一页</button></div>
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
      <div v-if="confirmItem" class="ct-overlay" @click.self="confirmItem = null"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-confirm-title"><h2 id="ct-confirm-title">{{ dialogText[confirmItem.action][0] }}</h2><p><strong>{{ confirmItem.item.title || '（未命名）' }}</strong><br />{{ dialogText[confirmItem.action][1] }}</p><div class="ct-modal-actions"><button type="button" class="btn btn-primary" @click="run(confirmItem.item, confirmItem.action)">{{ dialogText[confirmItem.action][2] }}</button><button type="button" class="btn btn-ghost" @click="confirmItem = null">先不</button></div></section></div>
  </PersonalLayout>
</template>
