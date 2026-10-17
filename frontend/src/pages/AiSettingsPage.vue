<script setup>
import { onMounted, ref } from 'vue'
import PersonalNav from '../components/PersonalNav.vue'
import { api } from '../lib/api'
const emit = defineEmits(['toast'])
const state = ref(null), key = ref(''), busy = ref(false), error = ref(''), confirmDelete = ref(false)
onMounted(async () => {
  try { state.value = await api('/api/me/ai-settings/deepseek') }
  catch (e) { error.value = e.message }
})
async function save() {
  if (busy.value || !key.value) return
  busy.value = true; error.value = ''
  try {
    state.value = await api('/api/me/ai-settings/deepseek', { method: 'PUT', body: JSON.stringify({ key: key.value }) })
    key.value = ''; confirmDelete.value = false; emit('toast', 'DeepSeek Key 已保存')
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function remove() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    state.value = await api('/api/me/ai-settings/deepseek', { method: 'DELETE' })
    key.value = ''; confirmDelete.value = false; emit('toast', 'DeepSeek Key 已删除')
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>
<template>
  <section class="page-hero personal-hero"><span class="kicker">YOUR SPACE</span><h1>让灵感，<em>有自己的助手。</em></h1><p>为你的专题创作配置 AI。</p></section>
  <section class="section personal-section"><div class="personal-container"><PersonalNav />
    <form class="profile-card ai-settings" @submit.prevent="save">
      <span class="kicker">AI SETTINGS</span><h2>AI 配置</h2>
      <p class="muted-copy">DeepSeek · 使用你自己的账号额度</p>
      <p v-if="!state && !error" role="status">正在读取配置…</p>
      <template v-if="state">
        <p class="key-status" role="status">{{ state.configured ? `已配置 · 末四位 ${state.lastFour}` : '尚未配置 DeepSeek Key' }}</p>
        <p v-if="!state.storageAvailable" class="form-error">凭据存储暂不可用，请联系管理员检查服务端加密配置。</p>
        <label>{{ state.configured ? '替换 Key' : 'DeepSeek API Key' }}
          <input v-model="key" type="password" autocomplete="new-password" spellcheck="false" maxlength="512" placeholder="输入你的 DeepSeek Key" :disabled="busy || !state.storageAvailable" />
        </label>
        <p class="muted-copy">Key 加密保存，仅用于你的 AI 沟通和有资料生成。保存配置不会调用模型；沟通或生成时，本次输入会发送给 DeepSeek。</p>
        <div class="settings-actions">
          <button v-if="key" class="btn btn-primary" :disabled="busy || !state.storageAvailable">{{ busy ? '处理中…' : state.configured ? '替换 Key' : '保存 Key' }}</button>
          <button v-if="state.configured && !confirmDelete" type="button" class="btn btn-outline" :disabled="busy" @click="confirmDelete = true">删除 Key</button>
        </div>
        <div v-if="confirmDelete" class="delete-confirm"><p>删除后，需要重新配置才能使用 AI 沟通和有资料生成。</p><button type="button" class="btn btn-outline" :disabled="busy" @click="remove">确认删除</button><button type="button" class="btn" :disabled="busy" @click="confirmDelete = false">取消</button></div>
      </template>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </form>
  </div></section>
</template>
<style scoped>
.settings-actions{display:flex;gap:12px;flex-wrap:wrap}.key-status{background:#eff4ee;padding:16px;border-radius:12px;color:#245d49}.delete-confirm{border-top:1px solid #dce3da;padding-top:16px}.ai-settings input{width:100%}
</style>
