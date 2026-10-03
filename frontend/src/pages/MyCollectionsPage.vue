<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PersonalNav from '../components/PersonalNav.vue'
import { api } from '../lib/api'
const router = useRouter(), busy = ref(false), page = ref(0), hasMore = ref(false)
const duplicate = async (id, structureOnly) => { busy.value = true; error.value = ''; try { const result = await api(`/api/me/collections/${id}/duplicate`, { method: 'POST', body: JSON.stringify({ structureOnly }) }); await router.push(`/me/collections/${result.id}/edit`) } catch (e) { error.value = e.message } finally { busy.value = false } }
const items = ref([]); const loading = ref(false); const error = ref('')
const load = async () => { if (loading.value) return; loading.value = true; try { const result = await api(`/api/me/collections?page=${page.value}&size=20`); items.value.push(...result.items); hasMore.value = result.hasMore; page.value++ } catch (e) { error.value = e.message } finally { loading.value = false } }
onMounted(load)
const statusLabel = (status) => ({ DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }[status] || status)
</script>
<template>
  <section class="page-hero personal-hero"><span class="kicker">YOUR COLLECTIONS</span><h1>把相关的内容，<br /><em>串成一条路径。</em></h1><p>创建属于你的专题，持续整理值得分享的知识。</p></section>
  <section class="section personal-section"><PersonalNav /><div class="section-heading"><div><span class="kicker">CREATED BY YOU</span><h2>我创建的专题</h2></div><div class="heading-actions"><RouterLink class="text-link" to="/my/collections">管理自选专题 →</RouterLink><RouterLink class="btn btn-primary" to="/me/collections/new">新建专题</RouterLink></div></div><p v-if="error" class="form-error" role="alert">{{ error }}</p><div v-if="loading" class="loading">正在加载专题…</div><div v-else-if="!items.length" class="empty-state">还没有创建专题。<br /><RouterLink to="/me/collections/new">选择模板开始制作 →</RouterLink></div><div v-else class="personal-list"><article v-for="item in items" :key="item.id" class="personal-row"><div><span class="post-tag">{{ statusLabel(item.status) }} · {{ item.itemCount }} 个条目</span><h3>{{ item.name }}</h3><p>{{ item.description }}</p></div><div class="row-actions"><button class="btn btn-outline" :disabled="busy" @click="duplicate(item.id, false)">复制内容</button><button class="btn btn-outline" :disabled="busy" @click="duplicate(item.id, true)">只用结构</button><RouterLink :to="`/me/collections/${item.id}/preview`" class="text-link">私人预览</RouterLink><RouterLink class="btn btn-outline" :to="`/me/collections/${item.id}/edit`">编辑</RouterLink><RouterLink v-if="item.status === 'PUBLISHED'" class="text-link" :to="`/collections/${item.id}`">查看公开页</RouterLink></div></article></div><button v-if="hasMore" class="btn btn-outline" :disabled="loading" @click="load">加载更多我的专题</button></section>
</template>
