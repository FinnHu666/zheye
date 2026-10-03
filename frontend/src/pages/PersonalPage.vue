<script setup>
import { onMounted, ref } from 'vue'
import { api, dateLabel } from '../lib/api'
import PersonalNav from '../components/PersonalNav.vue'

const emit = defineEmits(['toast', 'user-updated'])
const profile = ref({ displayName: '', email: '', bio: '' })
const loading = ref(true)
const saving = ref(false)
const error = ref('')
onMounted(async () => {
  try { profile.value = await api('/api/me/profile') }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
})
const save = async () => {
  saving.value = true; error.value = ''
  try { profile.value = await api('/api/me/profile', { method: 'PATCH', body: JSON.stringify(profile.value) }); emit('user-updated', { ...profile.value }); emit('toast', '个人资料已保存') }
  catch (e) { error.value = e.message }
  finally { saving.value = false }
}
</script>
<template>
  <section class="page-hero personal-hero"><span class="kicker">YOUR SPACE</span><h1>让这里成为<br /><em>你的创作基地。</em></h1><p>管理个人资料、发布内容，以及你正在整理的专题。</p></section>
  <section class="section personal-section"><PersonalNav /><div v-if="loading" class="loading">正在加载个人资料…</div><form v-else class="profile-card" @submit.prevent="save"><div><span class="kicker">PROFILE</span><h2>个人资料</h2><p class="muted-copy">邮箱用于登录，目前不能修改。</p></div><div class="profile-stats"><div><strong>{{ profile.postCount }}</strong><span>篇内容</span></div><div><strong>{{ profile.collectionCount }}</strong><span>个专题</span></div><div><strong>{{ profile.selectedCollectionCount }}</strong><span>个自选</span></div><div><strong>{{ dateLabel(profile.createdAt) }}</strong><span>加入 FINNS</span></div></div><label>昵称<input v-model="profile.displayName" maxlength="40" required /></label><label>邮箱<input :value="profile.email" disabled /></label><label>个人简介<textarea v-model="profile.bio" maxlength="500" rows="4" placeholder="介绍一下你关注的方向…" /></label><p v-if="error" class="form-error" role="alert">{{ error }}</p><button class="btn btn-primary" :disabled="saving">{{ saving ? '保存中…' : '保存资料' }}</button></form></section>
</template>
