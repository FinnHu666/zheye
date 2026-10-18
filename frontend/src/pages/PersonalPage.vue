<script setup>
import { onMounted, ref, computed } from 'vue'
import { api, dateLabel } from '../lib/api'
import { currentUser } from '../lib/session'
import PersonalLayout from '../components/PersonalLayout.vue'
import ReauthPanel from '../components/ReauthPanel.vue'
import { useSessionGuard } from '../lib/sessionGuard'

const emit = defineEmits(['toast', 'user-updated'])
const guard = useSessionGuard() // 登录失效时保留未保存的资料并就地重新登录
const profile = ref({ displayName: '', email: '', bio: '' })
const saved = ref({ displayName: '', bio: '' })
const fields = (p) => ({ displayName: p.displayName || '', bio: p.bio || '' })
const dirty = computed(() => JSON.stringify(fields(profile.value)) !== JSON.stringify(saved.value))
const isAdmin = computed(() => currentUser.value?.role?.code === 'ADMIN') // 只有管理员在个人资料中显示角色
const loading = ref(true)
const saving = ref(false)
const error = ref('')
onMounted(async () => {
  try { profile.value = await api('/api/me/profile'); saved.value = fields(profile.value) }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
})
const save = async () => {
  if (saving.value || !dirty.value) return
  if (!(await guard.beforeSubmit())) return // 会话失效或已换账号：不提交，留在原页
  const snapshot = fields(profile.value)
  saving.value = true; error.value = ''
  try {
    const result = await api('/api/me/profile', { method: 'PATCH', body: JSON.stringify(snapshot) })
    if (JSON.stringify(fields(profile.value)) === JSON.stringify(snapshot)) profile.value = result
    saved.value = fields(result); emit('user-updated', { ...result }); emit('toast', '个人资料已保存')
  }
  catch (e) { error.value = e.message; guard.handle(e) }
  finally { saving.value = false }
}
</script>
<template>
  <PersonalLayout kicker="YOUR SPACE" title="个人资料" desc="邮箱用于登录，目前不能修改。"><div v-if="loading" class="loading">正在加载个人资料…</div><form v-else class="profile-card" @submit.prevent="save"><div class="profile-stats"><div><strong>{{ profile.postCount }}</strong><span>篇内容</span></div><div><strong>{{ profile.collectionCount }}</strong><span>个专题</span></div><div><strong>{{ profile.selectedCollectionCount }}</strong><span>个自选</span></div><div><strong>{{ dateLabel(profile.createdAt) }}</strong><span>加入 FINNS</span></div></div><label>昵称<input v-model="profile.displayName" maxlength="40" required /></label><label>邮箱<input :value="profile.email" disabled /></label><label v-if="isAdmin">角色<input :value="currentUser.role.name" disabled /></label><label>个人简介<textarea v-model="profile.bio" maxlength="500" rows="4" placeholder="介绍一下你关注的方向…" /></label><p v-if="error" class="form-error" role="alert">{{ error }}</p><ReauthPanel :guard="guard" /><button v-if="dirty" class="btn btn-primary" :disabled="saving || guard.expired.value">{{ saving ? '保存中…' : '保存资料' }}</button></form></PersonalLayout>
</template>
