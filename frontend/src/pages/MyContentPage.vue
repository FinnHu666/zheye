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
const PAGE_SIZES = [10, 20, 50]
const articleSize = computed(() => PAGE_SIZES.includes(Number(route.query.size)) ? Number(route.query.size) : 20)
const stateSel = ref(articleState.value)
const filtered = computed(() => !!articleQuery.value || articleState.value !== 'ALL')
const search = ref(articleQuery.value)
const goArticles = (patch) => router.push({ path: '/me/posts', query: Object.fromEntries(Object.entries({ state: articleState.value, q: articleQuery.value, size: articleSize.value === 20 ? undefined : articleSize.value, ...patch }).filter(([k, v]) => v && v !== 'ALL' && !(k === 'page' && v === 0))) })
const doSearch = () => goArticles({ q: search.value.trim() || undefined, state: stateSel.value, page: 0 })
const pageNumbers = (data) => { const last = Math.max(1, Math.ceil(data.total / data.size)), cur = data.page + 1, from = Math.max(1, Math.min(cur - 2, last - 4)); return Array.from({ length: Math.min(5, last) }, (_, i) => from + i) }
const fmt = (v) => v ? new Date(v).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).replace(/\//g, '-') : '—'
const resetFilters = () => { search.value = ''; stateSel.value = 'ALL'; router.push('/me/posts') }
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
    const result = await articles.list({ state: articleState.value, q: articleQuery.value, page: articlePage.value, size: articleSize.value })
    if (mine !== token) return
    if (!result.items.length && articlePage.value > 0 && result.total > 0) { goArticles({ page: Math.max(0, Math.ceil(result.total / result.size) - 1) }); return }
    data.value = result
  } catch (e) { if (mine === token) { error.value = errorText(e); data.value = null } }
  finally { if (mine === token) loading.value = false }
}

const collectionSearch=ref(''),collectionState=ref('')
watch([collectionSearch,collectionState],()=>loadCollections(true))
async function loadCollections(reset = false) {
  if (!reset && (loadingMore.value || !collectionMore.value)) return
  const mine = ++token, next = reset ? 0 : collectionPage.value
  if (reset) { loading.value = true; collections.value = []; collectionPage.value = 0 } else loadingMore.value = true
  error.value = ''
  try {
    const result = await api(`/api/me/collections?page=${next}&size=20&q=${encodeURIComponent(collectionSearch.value)}&state=${collectionState.value}`)
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
const statusLabel = (status) => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已撤回' }[status] || status)

watch(() => [type.value, articleState.value, articleQuery.value, articlePage.value, articleSize.value], () => { search.value = articleQuery.value; stateSel.value = articleState.value; load() })
const onKey = (e) => { if (e.key === 'Escape') confirmItem.value = null }
onMounted(() => { load(); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <PersonalLayout :class="{ 'mc-collections-page': type === 'COLLECTION' }" kicker="MY CONTENT" title="我的内容" desc="你创作的文章、创建的专题和自选专题都在这里；文章保存只是私人存稿，发布才会检测并对外。">
    <template #actions><div class="heading-actions"><template v-if="type === 'COLLECTION'"><RouterLink class="btn btn-primary" to="/me/collections/ai/new">AI 创建专题</RouterLink><RouterLink class="btn btn-outline" to="/me/collections/new">直接编写</RouterLink></template><RouterLink v-else-if="type === 'SELECTED'" class="text-link" to="/collections">浏览专题广场 →</RouterLink><RouterLink v-else class="btn btn-primary create-button" to="/me/posts/new"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20.24 12.24a6 6 0 0 0-8.49-8.49L5 10.5V19h8.5z"/><path d="M16 8 2 22"/><path d="M17.5 15H9"/></svg>写文章</RouterLink></div></template>
    <div class="rv-tabs mc-tabs" role="tablist" aria-label="内容类型"><button v-for="[k, label] in tabs" :key="k" role="tab" :aria-selected="type === k" :class="{ active: type === k }" @click="pick(k)">{{ label }}</button></div>
    <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="text-link" @click="load">重试</button></p>
    <div v-if="loading && type === 'ARTICLE' && !data" class="loading" role="status">正在加载内容…</div>
    <div v-else-if="loading && type === 'COLLECTION' && !collections.length" class="loading" role="status">正在加载内容…</div>

    <template v-else-if="type === 'ARTICLE'">
      <form class="mc-filter" role="search" @submit.prevent="doSearch">
        <label class="mc-search"><span class="sr-only">搜索标题或摘要</span><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg><input v-model="search" type="search" maxlength="80" placeholder="搜索标题或摘要…" autocomplete="off" /></label>
        <div class="mc-filter-row">
          <label>状态<select v-model="stateSel" aria-label="状态筛选"><option v-for="[value, label] in STATE_FILTERS" :key="value" :value="value">{{ label }}</option></select></label>
          <span class="mc-filter-actions"><button type="button" class="mc-btn" @click="resetFilters">重置</button><button type="submit" class="mc-btn primary"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>查询</button></span>
        </div>
      </form>
      <div v-if="data && !data.items.length" class="empty-state">
        <template v-if="filtered">没有符合条件的文章。<br /><button type="button" class="text-link" @click="resetFilters">清除筛选</button></template>
        <template v-else>还没有文章。从一份私人草稿开始，不完整也可以先保存。<br /><RouterLink to="/me/posts/new">写文章 →</RouterLink></template>
      </div>
      <section v-else-if="data" class="mc-table-card" aria-label="文章列表">
        <div class="mc-table-wrap">
          <table class="mc-table">
            <thead><tr><th>标题</th><th>状态</th><th>版本</th><th>最后编辑时间</th><th>创建时间</th><th class="mc-ops-h">操作</th></tr></thead>
            <tbody>
              <tr v-for="item in data.items" :key="item.id" class="mc-trow" :aria-busy="busy">
                <td class="mc-title-cell" data-label="标题"><RouterLink :to="`/me/posts/${item.id}/edit`"><strong>{{ item.title || '（未命名）' }}</strong></RouterLink><small>{{ item.summary || '还没有摘要' }}</small><small v-if="item.hasPublished && item.publicTitle && item.publicTitle !== item.title">公开版：{{ item.publicTitle }}</small></td>
                <td data-label="状态"><span class="rv-tags"><span class="rv-badge" :class="publicChip(item.status)[1]">{{ publicChip(item.status)[0] }}</span><span class="rv-badge" :class="workChip(item.status)[1]">{{ workChip(item.status)[0] }}</span></span></td>
                <td data-label="版本">v{{ item.version }}</td>
                <td data-label="最后编辑时间">{{ fmt(item.updatedAt) }}</td>
                <td data-label="创建时间">{{ fmt(item.createdAt) }}</td>
                <td class="mc-ops" data-label="操作">
                  <RouterLink class="mc-icon" :to="`/me/posts/${item.id}/edit`" title="编辑" aria-label="编辑"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg></RouterLink>
                  <RouterLink class="mc-icon" :to="`/me/posts/${item.id}/preview`" title="私人预览" aria-label="私人预览"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg></RouterLink>
                  <details v-if="item.canCancel || item.canWithdraw || item.canDelete" class="mc-more"><summary class="mc-icon" title="更多操作" aria-label="更多操作"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="19" cy="12" r="1.9"/></svg></summary>
                    <div class="mc-menu">
                      <button v-if="item.canCancel" type="button" :disabled="busy" @click="run(item, 'cancel')">取消送审</button>
                      <button v-if="item.canWithdraw" type="button" :disabled="busy" @click="confirmItem = { item, action: 'withdraw' }">撤回</button>
                      <button v-if="item.canDelete" type="button" class="danger" :disabled="busy" @click="confirmItem = { item, action: 'delete' }">删除草稿</button>
                    </div>
                  </details>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="mc-foot">
          <span>共 {{ data.total }} 条记录</span>
          <span class="mc-pager">
            <button type="button" class="mc-page" :disabled="data.page <= 0" aria-label="上一页" @click="goArticles({ page: data.page - 1 })">‹</button>
            <button v-for="n in pageNumbers(data)" :key="n" type="button" class="mc-page" :class="{ on: n === data.page + 1 }" :aria-current="n === data.page + 1 ? 'page' : undefined" @click="goArticles({ page: n - 1 })">{{ n }}</button>
            <button type="button" class="mc-page" :disabled="!data.hasMore" aria-label="下一页" @click="goArticles({ page: data.page + 1 })">›</button>
            <select class="mc-size" :value="articleSize" aria-label="每页条数" @change="goArticles({ size: Number($event.target.value), page: 0 })"><option v-for="n in PAGE_SIZES" :key="n" :value="n">{{ n }} 条/页</option></select>
          </span>
        </div>
      </section>
    </template>

    <template v-else-if="type === 'COLLECTION'">
      <div class="mc-filter mc-collection-filters">
        <label class="mc-search"><span class="sr-only">搜索专题</span><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg><input v-model="collectionSearch" type="search" maxlength="80" placeholder="按名称搜索专题…" /></label>
        <div class="mc-filter-row"><label>状态<select v-model="collectionState" aria-label="专题状态筛选"><option value="">全部状态</option><option value="DRAFT">私人草稿</option><option value="PUBLISHED">已发布</option><option value="ARCHIVED">已撤回</option></select></label></div>
      </div>
      <div v-if="!collections.length && !error" class="empty-state">还没有创建专题。<br /><RouterLink to="/me/collections/new">开始创建专题 →</RouterLink></div>
      <section v-else-if="collections.length" class="mc-table-card mc-collection-table-card" aria-label="专题列表">
        <div class="mc-table-wrap">
          <table class="mc-table mc-collection-table">
            <colgroup><col style="width:32%" /><col style="width:18%" /><col style="width:8%" /><col style="width:22%" /><col style="width:20%" /></colgroup>
            <thead><tr><th scope="col">专题</th><th scope="col">状态</th><th scope="col">版本</th><th scope="col">最后编辑时间</th><th scope="col" class="mc-ops-h">操作</th></tr></thead>
            <tbody>
              <template v-for="item in collections" :key="item.id">
                <tr class="mc-trow" :aria-busy="busy">
                  <td class="mc-title-cell" data-label="专题"><RouterLink :to="item.editorType === 'BUILDER' ? `/me/collections/${item.id}/builder` : `/me/collections/${item.id}/edit`"><strong>{{ item.name || '（未命名）' }}</strong></RouterLink><small>{{ item.description || '还没有简介' }}</small><small>{{ item.itemCount }} 个{{ item.editorType === 'BUILDER' ? '模块' : '条目' }}</small></td>
                  <td data-label="状态"><span class="rv-tags"><span class="rv-badge" :class="item.status === 'PUBLISHED' ? 'green' : 'neutral'">{{ statusLabel(item.status) }}</span><span v-if="latest('COLLECTION', item.id) && latest('COLLECTION', item.id).status !== 'PUBLISHED'" class="rv-badge" :class="flowChip(latest('COLLECTION', item.id))[1]">{{ flowChip(latest('COLLECTION', item.id))[0] }}</span></span></td>
                  <td data-label="版本">{{ item.revision == null ? '—' : `v${item.revision}` }}</td>
                  <td data-label="最后编辑时间">{{ fmt(item.updatedAt) }}</td>
                  <td class="mc-ops" data-label="操作">
                    <RouterLink class="mc-icon" :to="item.editorType === 'BUILDER' ? `/me/collections/${item.id}/builder` : `/me/collections/${item.id}/edit`" title="编辑" aria-label="编辑"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg></RouterLink>
                    <RouterLink class="mc-icon" :to="item.editorType === 'BUILDER' ? `/me/collections/${item.id}/builder/preview` : `/me/collections/${item.id}/preview`" title="私人预览" aria-label="私人预览"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg></RouterLink>
                    <details class="mc-more"><summary class="mc-icon" title="更多操作" aria-label="更多操作"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="19" cy="12" r="1.9"/></svg></summary><div class="mc-menu">
                      <button v-if="item.editorType !== 'BUILDER'" type="button" :disabled="busy" @click="duplicate(item.id, false)">复制内容</button><button v-if="item.editorType !== 'BUILDER'" type="button" :disabled="busy" @click="duplicate(item.id, true)">只用结构</button>
                      <RouterLink v-if="item.status === 'PUBLISHED'" :to="`/collections/${item.id}`">查看公开页</RouterLink>
                      <button type="button" :aria-expanded="!!opened['c' + item.id]" :aria-controls="`collection-flow-${item.id}`" @click="toggle('c' + item.id); $event.currentTarget.closest('details').open = false">{{ opened['c' + item.id] ? '收起流转' : '审核流转' }}</button>
                    </div></details>
                  </td>
                </tr>
                <tr v-if="opened['c' + item.id]" class="mc-collection-flow-row"><td colspan="5"><div :id="`collection-flow-${item.id}`" class="mc-flow"><p v-if="!flowMap[key('COLLECTION', item.id)]?.length" class="rv-footnote">还没有发布提交，因此没有审核流转。</p><template v-else><div v-for="flow in flowMap[key('COLLECTION', item.id)]" :key="flow.id" class="mc-flow-one"><strong>提交版 v{{ flow.version }} · {{ timeLabel(flow.startedAt) }}</strong><FlowTimeline :flow="flow" audience="author" /></div></template></div></td></tr>
              </template>
            </tbody>
          </table>
        </div>
      </section>
      <div v-if="collectionMore" class="pagination-control"><button class="btn btn-outline" :disabled="loadingMore" @click="loadCollections(false)">{{ loadingMore ? '正在加载…' : '加载更多我的专题' }}</button></div>
    </template>

    <SelectedCollections v-else-if="type === 'SELECTED'" />
      <div v-if="confirmItem" class="ct-overlay" @click.self="confirmItem = null"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-confirm-title"><h2 id="ct-confirm-title">{{ dialogText[confirmItem.action][0] }}</h2><p><strong>{{ confirmItem.item.title || '（未命名）' }}</strong><br />{{ dialogText[confirmItem.action][1] }}</p><div class="ct-modal-actions"><button type="button" class="btn btn-primary" @click="run(confirmItem.item, confirmItem.action)">{{ dialogText[confirmItem.action][2] }}</button><button type="button" class="btn btn-ghost" @click="confirmItem = null">先不</button></div></section></div>
  </PersonalLayout>
</template>
