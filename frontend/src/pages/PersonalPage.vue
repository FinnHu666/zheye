<script setup>
import { onMounted, ref, computed } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api'
import { currentUser } from '../lib/session'
import PersonalLayout from '../components/PersonalLayout.vue'
import ReauthPanel from '../components/ReauthPanel.vue'
import { useSessionGuard } from '../lib/sessionGuard'

const emit = defineEmits(['toast', 'user-updated'])
// 默认只读：/me 查看；点击“修改资料”进入 /me/profile/edit 才能编辑，保存或取消后回到 /me
const route = useRoute(), router = useRouter()
const editing = computed(() => route.path === '/me/profile/edit')
let leaving = false
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
    if (!dirty.value) { leaving = true; await router.push('/me'); leaving = false } // 保存期间又改了内容则留在编辑页，不丢输入
  }
  catch (e) { error.value = e.message; guard.handle(e) }
  finally { saving.value = false }
}
const cancel = async () => { profile.value = { ...profile.value, ...saved.value }; await router.push('/me') }
onBeforeRouteLeave(() => leaving || !dirty.value || window.confirm('有未保存的修改，离开会丢失。确定离开吗？'))
</script>
<template>
  <PersonalLayout kicker="YOUR SPACE" title="个人资料" desc="完善个人信息，让更多人了解你，也方便团队协作与内容交流。">
    <template #actions><span class="pf-privacy"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>个人资料仅用于展示，邮箱不会公开</span></template>
    <div v-if="loading" class="loading">正在加载个人资料…</div>
    <form v-else class="pf-grid" @submit.prevent="save">
      <aside class="pf-me" aria-label="资料概览">
        <span class="pf-avatar" aria-hidden="true">{{ [...(saved.displayName || profile.displayName || '?')][0] }}</span>
        <strong class="pf-name">{{ saved.displayName || profile.displayName }}</strong>
        <p class="pf-bio">{{ saved.bio || '还没有个人简介' }}</p>
        <div class="pf-nums">
          <div><strong>{{ profile.postCount }}</strong><small>篇内容</small></div>
          <div><strong>{{ profile.collectionCount }}</strong><small>个专题</small></div>
          <div><strong>{{ profile.selectedCollectionCount }}</strong><small>个自选</small></div>
        </div>
        <dl class="pf-meta"><div><dt><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>加入时间</dt><dd>{{ profile.createdAt ? new Date(profile.createdAt).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }) : '—' }}</dd></div></dl>
      </aside>
      <section class="pf-edit">
        <h2 class="pf-sec"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>基本信息<RouterLink v-if="!editing" class="pf-edit-link" to="/me/profile/edit"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>修改资料</RouterLink></h2>
        <template v-if="editing">
          <label class="pf-field"><span class="pf-label">昵称 <b aria-hidden="true">*</b></span><input v-model="profile.displayName" maxlength="40" required autocomplete="off" /><span class="pf-count">{{ [...profile.displayName].length }}/40</span></label>
          <label class="pf-field"><span class="pf-label">邮箱 <b aria-hidden="true">*</b></span><span class="pf-readonly"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></svg><input :value="profile.email" disabled aria-label="邮箱（用于登录，不能修改）" /></span></label>
          <label v-if="isAdmin" class="pf-field"><span class="pf-label">角色</span><input :value="currentUser.role.name" disabled /></label>
          <label class="pf-field"><span class="pf-label">个人简介</span><textarea v-model="profile.bio" maxlength="500" rows="5" placeholder="介绍一下你关注的方向、擅长的领域或想分享的内容…" /><span class="pf-count">{{ [...(profile.bio || '')].length }}/500</span></label>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <ReauthPanel :guard="guard" />
          <div class="pf-actions"><button type="button" class="pf-cancel" :disabled="saving" @click="cancel">取消</button><button class="pf-save" :disabled="saving || !dirty || guard.expired.value"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 3h11l4 4v14H5z"/><path d="M8 3v6h7V3M8 21v-7h8v7"/></svg>{{ saving ? '保存中…' : '保存修改' }}</button></div>
        </template>
        <dl v-else class="pf-view">
          <div><dt>昵称</dt><dd>{{ profile.displayName }}</dd></div>
          <div><dt>邮箱</dt><dd class="pf-view-mail"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/></svg>{{ profile.email }}</dd></div>
          <div v-if="isAdmin"><dt>角色</dt><dd>{{ currentUser.role.name }}</dd></div>
          <div><dt>个人简介</dt><dd class="pf-view-bio">{{ profile.bio || '还没有个人简介' }}</dd></div>
        </dl>
      </section>
    </form>
  </PersonalLayout>
</template>
