<!-- 原型覆盖：frontend/src/pages/MyContentPage.vue。与真实站点同一入口 /me/posts：文章、我创建的专题、自选专题三个页签在同一个个人中心页面内。
     FINNS-18 R1 只重做“文章”页签（保存/发布分离、三个版本、取消送审/撤回、删除私人草稿）；专题页签沿用现有行为，专题创作与流转属 FINNS-19，未改。 -->
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PersonalLayout from '@finns/components/PersonalLayout.vue'
import SelectedCollections from '@finns/components/SelectedCollections.vue'
import { api } from '@finns/lib/api'
import { can } from '../proto/session'
const route = useRoute(), router = useRouter()
const tabs = computed(() => [['ARTICLE', '文章'], ...(can('collection.manage') ? [['COLLECTION', '我创建的专题']] : []), ...(can('selection.manage') ? [['SELECTED', '自选专题']] : [])])
const type = computed(() => tabs.value.some(([k]) => k === route.query.type) ? String(route.query.type) : 'ARTICLE')
const pick = key => router.replace({ query: key === 'ARTICLE' ? {} : { type: key } })

const data = ref(null), collections = ref([]), loading = ref(true), error = ref(''), message = ref(''), busy = ref(0), confirmItem = ref(null), query = ref(String(route.query.q || ''))
const state = computed(() => ['DRAFT', 'REVIEW', 'LIVE', 'OFF'].includes(route.query.state) ? route.query.state : 'ALL')
const states = [['ALL', '全部'], ['DRAFT', '私人草稿'], ['REVIEW', '审核相关'], ['LIVE', '已公开'], ['OFF', '已撤回 / 下架']]
const filtered = computed(() => !!route.query.q || state.value !== 'ALL')
const statusLabel = status => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }[status] || status)
const go = patch => router.push({ path: '/me/posts', query: Object.fromEntries(Object.entries({ state: state.value, q: route.query.q, ...patch }).filter(([, v]) => v && v !== 'ALL')) })
const search = () => go({ q: query.value.trim() || undefined })
const reset = () => { query.value = ''; router.push('/me/posts') }
const time = value => value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : ''
async function load() {
  message.value = ''; error.value = ''
  if (type.value === 'SELECTED') { loading.value = false; return }
  loading.value = true
  try {
    if (type.value === 'COLLECTION') collections.value = (await api('/api/me/collections?page=0&size=20')).items
    else data.value = await api(`/api/content/mine?state=${state.value}&q=${encodeURIComponent(String(route.query.q || ''))}`)
  } catch (e) { error.value = e.status === 401 ? '登录已失效，请重新登录后查看我的内容。' : e.message; if (type.value === 'ARTICLE') data.value = null }
  finally { loading.value = false }
}
async function run(item, action) {
  confirmItem.value = null; busy.value = item.id; error.value = ''; message.value = ''
  try {
    const call = {
      cancel: () => api(`/api/content/mine/${item.id}/cancel`, { method: 'POST', body: '{}' }),
      withdraw: () => api(`/api/content/mine/${item.id}/withdraw`, { method: 'POST', body: JSON.stringify({ confirm: true }) }),
      delete: () => api(`/api/content/mine/${item.id}`, { method: 'DELETE' }),
    }[action]
    message.value = (await call()).message; await load()
  } catch (e) { error.value = e.message; await load() } finally { busy.value = 0 }
}
const dialogText = {
  withdraw: ['撤回公开？', '撤回后读者、列表、搜索和收藏里都看不到这条内容，待处理的提交也会作废；私人稿保留，重新发布会重新检测。', '确认撤回'],
  delete: ['删除这份私人草稿？', '只能删除从未公开过的私人草稿，删除后无法恢复。', '确认删除'],
}
watch(() => route.query, () => { query.value = String(route.query.q || ''); load() })
const reload = () => load()
const onKey = e => { if (e.key === 'Escape') confirmItem.value = null }
onMounted(() => { load(); window.addEventListener('finns-content-scene', reload); window.addEventListener('finns-account-session', reload); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { window.removeEventListener('finns-content-scene', reload); window.removeEventListener('finns-account-session', reload); window.removeEventListener('keydown', onKey) })
</script>
<template>
  <PersonalLayout kicker="MY CONTENT" title="我的内容" desc="你创作的文章、创建的专题和自选专题都在这里；文章保存只是私人存稿，发布才会检测并对外。">
    <template #actions><div class="heading-actions">
      <template v-if="type === 'COLLECTION'"><RouterLink class="btn btn-primary" to="/me/collections/ai/new">AI 创建专题</RouterLink><RouterLink class="btn btn-outline" to="/me/collections/new">直接编写</RouterLink></template>
      <RouterLink v-else-if="type === 'SELECTED'" class="btn btn-outline" to="/collections">浏览专题广场</RouterLink>
      <RouterLink v-else class="btn btn-primary" to="/me/posts/new">写文章</RouterLink>
    </div></template>
    <div class="rv-tabs mc-tabs" role="tablist" aria-label="内容类型"><button v-for="[key, label] in tabs" :key="key" role="tab" :aria-selected="type === key" :class="{ active: type === key }" @click="pick(key)">{{ label }}</button></div>
    <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button class="text-link" @click="load">重试</button></p>

    <template v-if="type === 'ARTICLE'">
      <form class="ct-toolbar" role="search" @submit.prevent="search">
        <label class="ct-search"><span class="sr-only">搜索标题或摘要</span><input v-model="query" type="search" placeholder="搜索标题或摘要" autocomplete="off" /></label>
        <button class="btn btn-outline" type="submit">搜索</button><button v-if="filtered" class="btn btn-ghost" type="button" @click="reset">清除筛选</button>
      </form>
      <div class="ct-filters"><div class="ct-chips" role="group" aria-label="状态筛选"><button v-for="[value, label] in states" :key="value" :class="{ on: state === value }" :aria-pressed="state === value" @click="go({ state: value })">{{ label }}</button></div></div>
      <div v-if="loading && !data" class="loading" role="status">正在加载内容…</div>
      <div v-else-if="data && !data.items.length" class="empty-state">
        <template v-if="filtered">没有符合条件的文章。<br /><button class="text-link" @click="reset">清除筛选</button></template>
        <template v-else>还没有文章。从一份私人草稿开始，不完整也可以先保存。<br /><RouterLink to="/me/posts/new">写文章 →</RouterLink></template>
      </div>
      <section v-else-if="data" class="mc-group" aria-label="文章列表">
        <article v-for="item in data.items" :key="item.id" class="mc-row" :aria-busy="busy === item.id">
          <div class="mc-main">
            <div class="rv-tags"><span class="rv-badge" :class="item.status.publicState === 'LIVE' ? 'green' : item.status.publicState === 'DOWN' ? 'danger' : 'neutral'">{{ item.status.publicText }}</span><span class="rv-badge" :class="item.status.tone">{{ item.status.workText }}</span></div>
            <h3><RouterLink :to="`/me/posts/${item.id}/edit`">{{ item.title || '（未命名）' }}</RouterLink></h3>
            <p>{{ item.summary || '还没有摘要' }}</p>
            <small>工作稿 v{{ item.version }}<template v-if="item.hasPublished && item.publicTitle"> · 公开版：{{ item.publicTitle }}</template><template v-if="item.updatedAt"> · 最近处理 {{ time(item.updatedAt) }}</template></small>
          </div>
          <div class="mc-actions">
            <RouterLink class="btn btn-outline" :to="`/me/posts/${item.id}/edit`">编辑</RouterLink>
            <RouterLink class="btn btn-ghost" :to="`/me/posts/${item.id}/preview`">私人预览</RouterLink>
            <button v-if="item.canCancel" class="btn btn-ghost" :disabled="busy === item.id" @click="run(item, 'cancel')">取消送审</button>
            <button v-if="item.canWithdraw" class="btn btn-ghost" :disabled="busy === item.id" @click="confirmItem = { item, action: 'withdraw' }">撤回</button>
            <button v-if="item.canDelete" class="btn btn-ghost ct-danger" :disabled="busy === item.id" @click="confirmItem = { item, action: 'delete' }">删除草稿</button>
          </div>
        </article>
      </section>
    </template>

    <template v-else-if="type === 'COLLECTION'">
      <div v-if="loading" class="loading" role="status">正在加载专题…</div>
      <div v-else-if="!collections.length && !error" class="empty-state">还没有创建专题。<br /><RouterLink to="/me/collections/new">选择模板开始制作 →</RouterLink></div>
      <article v-for="item in collections" :key="item.id" class="mc-row">
        <div class="mc-main"><div class="rv-tags"><span class="post-tag">{{ statusLabel(item.status) }} · {{ item.itemCount }} 个条目</span></div><h3>{{ item.name }}</h3><p v-if="item.description">{{ item.description }}</p></div>
        <div class="mc-actions"><RouterLink class="btn btn-outline" :to="`/me/collections/${item.id}/edit`">编辑</RouterLink><RouterLink class="btn btn-ghost" :to="`/me/collections/${item.id}/preview`">私人预览</RouterLink></div>
      </article>
      <p v-if="!loading" class="rv-footnote">专题的创作与审核流转由 FINNS-19 另行呈现，此页签沿用现有行为。</p>
    </template>

    <SelectedCollections v-else-if="type === 'SELECTED'" />

    <div v-if="confirmItem" class="ct-overlay" @click.self="confirmItem = null"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-confirm-title"><h2 id="ct-confirm-title">{{ dialogText[confirmItem.action][0] }}</h2><p><strong>{{ confirmItem.item.title || '（未命名）' }}</strong><br />{{ dialogText[confirmItem.action][1] }}</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="run(confirmItem.item, confirmItem.action)">{{ dialogText[confirmItem.action][2] }}</button><button class="btn btn-ghost" @click="confirmItem = null">先不</button></div></section></div>
  </PersonalLayout>
</template>
