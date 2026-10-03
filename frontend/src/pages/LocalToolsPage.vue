<script setup>
import { computed, ref } from 'vue'
import { api } from '../lib/api'
const key = ref(''), query = ref(''), error = ref(''), busy = ref(false), inventory = ref(null)
const visible = computed(() => (inventory.value?.skills || []).filter(s => `${s.name} ${s.description} ${s.location}`.toLowerCase().includes(query.value.toLowerCase())))
async function refresh() {
  busy.value = true; error.value = ''; inventory.value = null
  try { inventory.value = await api('/api/private/tools', { headers: { 'X-Tools-Key': key.value } }) }
  catch (e) { error.value = e.message } finally { busy.value = false }
}
function lock() { key.value = ''; inventory.value = null; query.value = '' }
</script>
<template>
  <section class="section tools-page">
    <span class="kicker">PRIVATE WORKSPACE</span><h1>本地工具</h1><p>私人 Skills 清单 · 从本机配置目录读取</p>
    <form v-if="!inventory" class="unlock" @submit.prevent="refresh">
      <label>私人访问密钥<input v-model="key" type="password" autocomplete="off" required placeholder="输入专属密钥" /></label>
      <button class="btn btn-primary" :disabled="busy">{{ busy ? '读取中…' : '解锁清单' }}</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="inventory">
      <div class="tools-bar"><strong>{{ inventory.total }} 个 Skills</strong><span>当前显示 {{ visible.length }} 个</span><button class="btn btn-outline" :disabled="busy" @click="refresh">刷新统计</button><button class="btn btn-ghost" @click="lock">锁定</button></div>
      <label>搜索技能<input v-model="query" class="tool-search" placeholder="名称、用途或来源路径" /></label>
      <p v-for="(warning, i) in inventory.warnings" :key="i" role="status">{{ warning }}</p>
      <div class="tool-grid"><article v-for="skill in visible" :key="skill.source + skill.location" class="tool-card"><span class="kicker">SKILL</span><h2>{{ skill.name }}</h2><p>{{ skill.description }}</p><small>{{ skill.source }} / {{ skill.location }}</small><small>更新于 {{ new Date(skill.updatedAt).toLocaleString() }}</small></article></div>
      <p v-if="!visible.length">没有匹配的技能。</p>
    </template>
  </section>
</template>
<style scoped>
.tools-page{min-height:75vh}.tools-page h1{font-size:40px}.unlock{display:flex;gap:16px;align-items:end;flex-wrap:wrap;margin-top:32px}label{display:grid;gap:8px}input{padding:12px;border:1px solid #bdcbbf;border-radius:10px;max-width:100%}.tools-bar{display:flex;gap:20px;align-items:center;flex-wrap:wrap;margin:24px 0}.tool-search{width:100%;margin-bottom:24px}.tool-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr));gap:18px}.tool-card{background:white;padding:24px;border:1px solid #dce3dc;border-radius:16px;overflow-wrap:anywhere}.tool-card h2{font-size:20px}.tool-card p{color:#55665b}.tool-card small{display:block;margin-top:12px;color:#69736f}
</style>
