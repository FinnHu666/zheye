<!-- 原型覆盖：frontend/src/pages/MyContentPage.vue。同一入口 /me/posts：文章、我创建的专题、自选专题三个页签在同一个个人中心页面内。
     FINNS-18/19 R1（按用户批注修订）：文章与专题都用“属性条件查询 + 表格列表 + 行尾操作”，条件与页码在地址里。 -->
<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import PersonalLayout from '@finns/components/PersonalLayout.vue'
import SelectedCollections from '@finns/components/SelectedCollections.vue'
import FlowTimeline from '@finns/components/FlowTimeline.vue'
import { api } from '@finns/lib/api'
import { publicChip, workChip } from '@finns/lib/articles'
import { can } from '../proto/session'
const route = useRoute(), router = useRouter()
const tabs = computed(() => [['ARTICLE', '文章'], ...(can('collection.manage') ? [['COLLECTION', '我创建的专题']] : []), ...(can('selection.manage') ? [['SELECTED', '自选专题']] : [])])
const type = computed(() => tabs.value.some(([k]) => k === route.query.type) ? String(route.query.type) : 'ARTICLE')
const pick = key => router.replace({ query: key === 'ARTICLE' ? {} : { type: key } })

const PUB = [['', '全部'], ['NONE', '未公开'], ['LIVE', '已公开'], ['WITHDRAWN', '已撤回'], ['DOWN', '已下架']]
const WORK = [['', '全部'], ['PRIVATE', '私人草稿'], ['SAVED', '已保存'], ['MODIFIED', '有未发布的修改'], ['REVIEWING', '审核中'], ['RECHECK', '未通过 · 复核中'], ['REJECTED', '未通过']]
const VIS = [['', '全部'], ['PUBLIC', '公开'], ['RESTRICTED', '受限']]
const BAD = [['', '全部'], ['1', '有失效引用']]
const nameKey = computed(() => type.value === 'COLLECTION' ? 'name' : 'title')
const form = reactive({ text: '', pub: '', work: '', vis: '', bad: '' })
const syncForm = () => { form.text = String(route.query[nameKey.value] || ''); form.pub = String(route.query.pub || ''); form.work = String(route.query.work || ''); form.vis = String(route.query.vis || ''); form.bad = String(route.query.bad || '') }
const page = computed(() => Math.max(0, Number.parseInt(route.query.page, 10) || 0))
const filtered = computed(() => !!(route.query[nameKey.value] || route.query.pub || route.query.work || route.query.vis || route.query.bad))
const push = patch => router.push({ path: '/me/posts', query: Object.fromEntries(Object.entries({ type: type.value === 'ARTICLE' ? undefined : type.value, [nameKey.value]: form.text.trim() || undefined, pub: form.pub, work: form.work, ...(type.value === 'COLLECTION' ? { vis: form.vis, bad: form.bad } : {}), ...patch }).filter(([, v]) => v)) })
const search = () => push({ page: undefined })
const reset = () => router.push({ path: '/me/posts', query: type.value === 'ARTICLE' ? {} : { type: type.value } })
const goPage = n => push({ page: n || undefined })

const data = ref(null), loading = ref(true), error = ref(''), message = ref(''), busy = ref(0), confirmItem = ref(null), opened = ref({})
const time = value => value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : ''
const base = item => type.value === 'COLLECTION' ? `/api/me/collections/${item.id}` : `/api/content/mine/${item.id}`
const editPath = item => type.value === 'COLLECTION' ? `/me/collections/${item.id}/edit` : `/me/posts/${item.id}/edit`
const previewPath = item => type.value === 'COLLECTION' ? `/me/collections/${item.id}/preview` : `/me/posts/${item.id}/preview`
const nameOf = item => (type.value === 'COLLECTION' ? item.name : item.title) || '（未命名）'
const descOf = item => (type.value === 'COLLECTION' ? item.description : item.summary) || '还没有简介'
async function load() {
  message.value = ''; error.value = ''
  if (type.value === 'SELECTED') { loading.value = false; return }
  loading.value = true
  const qs = new URLSearchParams(Object.entries({ [nameKey.value]: route.query[nameKey.value], pub: route.query.pub, work: route.query.work, vis: route.query.vis, bad: route.query.bad, page: page.value }).filter(([, v]) => v !== undefined && v !== '')).toString()
  try { data.value = await api(type.value === 'COLLECTION' ? `/api/me/collections?${qs}` : `/api/content/mine?${qs}`) }
  catch (e) { error.value = e.status === 401 ? '登录已失效，请重新登录后查看我的内容。' : e.message; data.value = null }
  finally { loading.value = false }
}
async function run(item, action) {
  confirmItem.value = null; busy.value = item.id; error.value = ''; message.value = ''
  try {
    const call = { cancel: () => api(`${base(item)}/cancel`, { method: 'POST', body: '{}' }), withdraw: () => api(`${base(item)}/withdraw`, { method: 'POST', body: JSON.stringify({ confirm: true }) }), delete: () => api(base(item), { method: 'DELETE' }) }[action]
    message.value = (await call()).message; await load()
  } catch (e) { error.value = e.message; await load() } finally { busy.value = 0 }
}
const duplicate = async (item, structureOnly) => { busy.value = item.id; error.value = ''; try { const r = await api(`/api/me/collections/${item.id}/duplicate`, { method: 'POST', body: JSON.stringify({ structureOnly }) }); router.push(`/me/collections/${r.id}/edit?created=1`) } catch (e) { error.value = e.message } finally { busy.value = 0 } }
const dialogText = computed(() => ({
  withdraw: type.value === 'COLLECTION' ? ['撤回发布？', '撤回后读者、专题广场、搜索和自选页里都看不到这个专题，待处理的提交也会作废；私人稿保留，重新发布会重新检测。读者的自选记录保留，撤回期间隐藏，重新发布后自动恢复。', '确认撤回'] : ['撤回公开？', '撤回后读者、列表、搜索和收藏里都看不到这条内容，待处理的提交也会作废；私人稿保留，重新发布会重新检测。', '确认撤回'],
  delete: ['删除这份私人草稿？', '只能删除从未公开过的私人草稿，删除后无法恢复。', '确认删除'],
}))
watch(() => [type.value, route.query], () => { syncForm(); load() })
const reload = () => load()
const onKey = e => { if (e.key === 'Escape') confirmItem.value = null }
onMounted(() => { syncForm(); load(); window.addEventListener('finns-content-scene', reload); window.addEventListener('finns-account-session', reload); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { window.removeEventListener('finns-content-scene', reload); window.removeEventListener('finns-account-session', reload); window.removeEventListener('keydown', onKey) })
</script>
<template>
  <PersonalLayout kicker="MY CONTENT" title="我的内容" desc="你创作的文章、创建的专题和自选专题都在这里；保存只是私人存稿，发布才会检测并对外。">
    <template #actions><div class="heading-actions">
      <template v-if="type === 'COLLECTION'"><RouterLink class="btn btn-primary" to="/me/collections/new">直接编写</RouterLink><RouterLink class="btn btn-outline" to="/me/collections/ai/new">AI 创建专题</RouterLink></template>
      <RouterLink v-else-if="type === 'SELECTED'" class="btn btn-outline" to="/collections">浏览专题广场</RouterLink>
      <RouterLink v-else class="btn btn-primary" to="/me/posts/new">写文章</RouterLink>
    </div></template>
    <div class="rv-tabs mc-tabs" role="tablist" aria-label="内容类型"><button v-for="[key, label] in tabs" :key="key" role="tab" :aria-selected="type === key" :class="{ active: type === key }" @click="pick(key)">{{ label }}</button></div>
    <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button class="text-link" @click="load">重试</button></p>

    <template v-if="type !== 'SELECTED'">
      <form class="fc-filter" role="search" @submit.prevent="search">
        <label>{{ type === 'COLLECTION' ? '专题名称' : '标题' }}<input v-model="form.text" type="search" autocomplete="off" /></label>
        <label>公开状态<select v-model="form.pub" @change="search"><option v-for="[v, l] in PUB" :key="v" :value="v">{{ l }}</option></select></label>
        <label>工作状态<select v-model="form.work" @change="search"><option v-for="[v, l] in WORK" :key="v" :value="v">{{ l }}</option></select></label>
        <template v-if="type === 'COLLECTION'"><label>可见范围<select v-model="form.vis" @change="search"><option v-for="[v, l] in VIS" :key="v" :value="v">{{ l }}</option></select></label><label>引用<select v-model="form.bad" @change="search"><option v-for="[v, l] in BAD" :key="v" :value="v">{{ l }}</option></select></label></template>
        <div class="fc-filter-actions"><button class="btn btn-primary" type="submit">查询</button><button class="btn btn-ghost" type="button" :disabled="!filtered" @click="reset">重置</button></div>
      </form>
      <div v-if="loading && !data" class="loading" role="status">正在加载…</div>
      <div v-else-if="data && !data.items.length" class="empty-state">
        <template v-if="filtered">没有符合条件的{{ type === 'COLLECTION' ? '专题' : '文章' }}。<br /><button class="text-link" @click="reset">重置条件</button></template>
        <template v-else-if="type === 'COLLECTION'">还没有创建专题。<br /><RouterLink to="/me/collections/new">选择模板开始制作 →</RouterLink></template>
        <template v-else>还没有文章。从一份私人草稿开始，不完整也可以先保存。<br /><RouterLink to="/me/posts/new">写文章 →</RouterLink></template>
      </div>
      <div v-else-if="data" class="fc-table-wrap">
        <table class="fc-table">
          <thead><tr><th>{{ type === 'COLLECTION' ? '专题' : '文章' }}</th><th>公开状态</th><th>工作状态</th><th v-if="type === 'COLLECTION'">可见范围</th><th>版本</th><th>最近处理</th><th class="fc-ops-h">操作</th></tr></thead>
          <tbody>
            <template v-for="item in data.items" :key="item.id">
              <tr :aria-busy="busy === item.id">
                <td class="fc-name-cell" data-label="名称"><RouterLink :to="editPath(item)"><strong>{{ nameOf(item) }}</strong></RouterLink><small>{{ descOf(item) }}</small><small v-if="type === 'COLLECTION'">{{ item.itemCount }} 个条目<template v-if="item.hasInvalidRefs"> · <em class="fc-bad">有失效引用</em></template></small></td>
                <td data-label="公开状态"><span class="rv-badge" :class="publicChip(item.status)[1]">{{ publicChip(item.status)[0] }}</span></td>
                <td data-label="工作状态"><span class="rv-badge" :class="workChip(item.status)[1]">{{ workChip(item.status)[0] }}</span></td>
                <td v-if="type === 'COLLECTION'" data-label="可见范围">{{ item.visibility === 'RESTRICTED' ? '受限' : '公开' }}</td>
                <td data-label="版本">v{{ item.version }}</td>
                <td data-label="最近处理">{{ time(item.updatedAt) || '—' }}</td>
                <td class="fc-ops" data-label="操作">
                  <RouterLink class="fc-op" :to="editPath(item)">编辑</RouterLink>
                  <RouterLink class="fc-op" :to="previewPath(item)">预览</RouterLink>
                  <button v-if="item.canCancel" class="fc-op" :disabled="busy === item.id" @click="run(item, 'cancel')">取消送审</button>
                  <button v-if="item.canWithdraw" class="fc-op" :disabled="busy === item.id" @click="confirmItem = { item, action: 'withdraw' }">撤回</button>
                  <button v-if="item.canDelete" class="fc-op danger" :disabled="busy === item.id" @click="confirmItem = { item, action: 'delete' }">删除</button>
                  <details v-if="type === 'COLLECTION'" class="fc-more"><summary>更多</summary><div class="fc-menu"><button :disabled="busy === item.id" @click="duplicate(item, false)">复制内容</button><button :disabled="busy === item.id" @click="duplicate(item, true)">只用结构</button><RouterLink v-if="item.publicVisible" :to="`/collections/${item.id}`">查看公开页</RouterLink><button @click="opened[item.id] = !opened[item.id]">{{ opened[item.id] ? '收起流转' : '审核流转' }}</button></div></details>
                </td>
              </tr>
              <tr v-if="opened[item.id]" class="fc-flow-row"><td :colspan="type === 'COLLECTION' ? 7 : 6"><p v-if="!item.flows.length" class="rv-footnote">还没有发布提交，因此没有审核流转。</p><div v-for="flow in item.flows" :key="flow.id" class="mc-flow-one"><strong>提交版 v{{ flow.version }} · {{ time(flow.startedAt) }}</strong><FlowTimeline :flow="flow" audience="author" /></div></td></tr>
            </template>
          </tbody>
        </table>
        <div v-if="data.total > data.size" class="pagination-control"><span>第 {{ data.page + 1 }} / {{ Math.ceil(data.total / data.size) }} 页 · 共 {{ data.total }} 条</span><button class="btn btn-outline" :disabled="data.page <= 0" @click="goPage(data.page - 1)">上一页</button><button class="btn btn-outline" :disabled="!data.hasMore" @click="goPage(data.page + 1)">下一页</button></div>
      </div>
    </template>

    <SelectedCollections v-else />

    <div v-if="confirmItem" class="ct-overlay" @click.self="confirmItem = null"><section class="ct-modal" role="dialog" aria-modal="true" aria-labelledby="ct-confirm-title"><h2 id="ct-confirm-title">{{ dialogText[confirmItem.action][0] }}</h2><p><strong>{{ nameOf(confirmItem.item) }}</strong><br />{{ dialogText[confirmItem.action][1] }}</p><div class="ct-modal-actions"><button class="btn btn-primary" @click="run(confirmItem.item, confirmItem.action)">{{ dialogText[confirmItem.action][2] }}</button><button class="btn btn-ghost" @click="confirmItem = null">先不</button></div></section></div>
  </PersonalLayout>
</template>
