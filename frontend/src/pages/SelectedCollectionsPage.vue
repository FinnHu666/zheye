<script setup>
import { computed, onMounted, ref } from 'vue'
import PersonalNav from '../components/PersonalNav.vue'
import { api } from '../lib/api'
const selected = ref({ version: 0, items: [] }); const catalogItems = ref([]); const loading = ref(true); const error = ref(''); const busy = ref(false)
const catalogPage = ref(-1); const catalogTotal = ref(0); const catalogLoading = ref(true); const catalogLoadingMore = ref(false); const catalogError = ref('')
const hasMoreCatalog = computed(() => catalogItems.value.length < catalogTotal.value)
let catalogRequestId = 0
const loadSelected = async () => { loading.value = true; error.value = ''; try { selected.value = await api('/api/me/collections/selected') } catch (e) { error.value = e.message } finally { loading.value = false } }
const loadCatalog = async (reset = false) => {
  if (!reset && (catalogLoadingMore.value || !hasMoreCatalog.value)) return
  const page = reset ? 0 : catalogPage.value + 1
  const requestId = ++catalogRequestId
  if (reset) { catalogLoading.value = true; catalogLoadingMore.value = false; catalogItems.value = []; catalogPage.value = -1; catalogTotal.value = 0; catalogError.value = '' }
  else { catalogLoadingMore.value = true; catalogError.value = '' }
  try {
    const result = await api(`/api/collections?page=${page}&size=20`)
    if (requestId !== catalogRequestId) return
    const combined = reset ? result.items : [...catalogItems.value, ...result.items]
    catalogItems.value = [...new Map(combined.map(item => [item.id, item])).values()]
    catalogPage.value = result.page; catalogTotal.value = result.total
  } catch (e) {
    if (requestId === catalogRequestId) catalogError.value = e.message
  } finally {
    if (requestId === catalogRequestId) { catalogLoading.value = false; catalogLoadingMore.value = false }
  }
}
const retryCatalog = () => loadCatalog(catalogPage.value < 0 || catalogItems.value.length === 0)
onMounted(() => { loadSelected(); loadCatalog(true) })
const update = async (id, add) => { busy.value = true; error.value = ''; try { selected.value = await api(`/api/me/collections/selected/${id}`, { method: add ? 'PUT' : 'DELETE' }) } catch (e) { error.value = e.message } finally { busy.value = false } }
const move = async (index, offset) => { const ids = selected.value.items.map(item => item.id); const target = index + offset; if (target < 0 || target >= ids.length) return; [ids[index], ids[target]] = [ids[target], ids[index]]; busy.value = true; try { selected.value = await api('/api/me/collections/selected/order', { method: 'PATCH', body: JSON.stringify({ version: selected.value.version, collectionIds: ids }) }) } catch (e) { error.value = e.message; await loadSelected() } finally { busy.value = false } }
const isSelected = (id) => selected.value.items.some(item => item.id === id)
</script>
<template>
  <section class="page-hero"><span class="kicker">COLLECTIONS</span><h1>一起建立的<br /><em>知识地图。</em></h1><p>专题把分散的经验串成路径，让每一次探索都能找到下一站。</p></section>
  <section class="section collections-section personal-section"><PersonalNav /><div class="section-heading"><div><span class="kicker">YOUR SHORTLIST</span><h2>我的自选专题</h2></div><RouterLink class="text-link" to="/collections">浏览专题广场 →</RouterLink></div><p v-if="error" class="form-error" role="alert">{{ error }}</p><div v-if="loading" class="loading">正在加载自选专题…</div><div v-else-if="!selected.items.length" class="empty-state">还没有自选专题。浏览专题广场，收藏感兴趣的知识路径。</div><div v-else class="collections-grid"><article v-for="(item, index) in selected.items" :key="item.id" class="collection-card"><div class="collection-icon">✦</div><div><RouterLink class="collection-title-link" :to="`/collections/${item.id}`"><h3>{{ item.name }}</h3></RouterLink><p>{{ item.description }} · {{ item.ownerName }} 创建</p></div><div class="collection-controls"><span>{{ item.itemCount }} 篇</span><button :disabled="busy || index === 0" aria-label="上移" @click="move(index, -1)">↑</button><button :disabled="busy || index === selected.items.length - 1" aria-label="下移" @click="move(index, 1)">↓</button><button class="remove-link" :disabled="busy" @click="update(item.id, false)">移除</button></div></article></div></section>
  <section class="section catalog-section"><div class="section-heading"><div><span class="kicker">COMMUNITY CURATED</span><h2>发现更多专题</h2></div><RouterLink class="text-link" to="/collections">查看专题广场 →</RouterLink></div><p v-if="catalogError" class="form-error" role="alert">{{ catalogError }}</p><div class="collections-grid"><div v-if="catalogLoading && !catalogItems.length" class="loading">正在加载…</div><div v-else-if="!catalogItems.length && !catalogError" class="empty-state">暂时没有更多专题。</div><article v-for="item in catalogItems" :key="item.id" class="collection-card"><div class="collection-icon">⌁</div><div><h3>{{ item.name }}</h3><p>{{ item.description }} · {{ item.ownerName }} 创建</p></div><button class="btn" :class="isSelected(item.id) ? 'btn-outline' : 'btn-primary'" :disabled="busy" @click="update(item.id, !isSelected(item.id))">{{ isSelected(item.id) ? '已加入' : '加入自选' }}</button></article></div><div v-if="catalogError || hasMoreCatalog" class="pagination-control" aria-live="polite"><span v-if="catalogTotal">已加载 {{ catalogItems.length }} / {{ catalogTotal }} 个专题</span><button type="button" class="btn btn-outline" :disabled="catalogLoading || catalogLoadingMore" @click="catalogError ? retryCatalog() : loadCatalog(false)">{{ catalogError ? '重试' : catalogLoadingMore ? '正在加载…' : '加载更多专题' }}</button></div></section>
</template>
