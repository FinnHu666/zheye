<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api } from '@finns/lib/api'
import { sessionUser } from '../proto/session'
import { emailError } from '../proto/validation'
import PersonalNav from '@finns/components/PersonalNav.vue'
const props = defineProps({ user: Object })
const emit = defineEmits(['toast', 'user-updated', 'write'])
const route = useRoute(), router = useRouter(), edit = computed(() => !!route.meta.profileEdit)
const profile = ref(null), form = ref({ displayName: '', bio: '' }), original = ref(''), owner = ref(null)
const loading = ref(true), busy = ref(false), error = ref(''), nameError = ref(''), expired = ref(false), loginEmail = ref(''), loginPassword = ref(''), loginError = ref(''), discarding = ref(false)
let allowLeave = false, resolveLeave
const dirty = computed(() => edit.value && JSON.stringify(form.value) !== original.value)
const initial = computed(() => profile.value?.displayName?.slice(0,1) || 'F')
const joined = computed(() => profile.value?.createdAt ? new Date(profile.value.createdAt).toLocaleDateString('zh-CN',{year:'numeric',month:'long',day:'numeric'}) : '—')
async function load() {
  loading.value = true; error.value = ''; nameError.value = ''; expired.value = false; allowLeave = false
  try { profile.value = await api('/api/me/profile'); owner.value = profile.value.id; form.value = { displayName: profile.value.displayName, bio: profile.value.bio }; original.value = JSON.stringify(form.value); loginEmail.value = profile.value.email }
  catch(e) { error.value = e.message }
  finally { loading.value = false }
}
watch(() => route.path, load, {immediate:true})
const guard = () => {
  if (!dirty.value || allowLeave) return true
  discarding.value = true
  return new Promise(resolve => { resolveLeave = resolve })
}
const decideLeave = yes => { discarding.value = false; resolveLeave?.(yes); resolveLeave = undefined }
onBeforeRouteLeave(guard); onBeforeRouteUpdate(guard)
const detect = async () => { if (!edit.value) return; try { const u=await api('/api/auth/me'); if (u.id !== owner.value) expired.value=true } catch { expired.value=true } }
onMounted(() => window.addEventListener('finns-account-session',detect))
onBeforeUnmount(() => { window.removeEventListener('finns-account-session',detect); loginPassword.value=''; resolveLeave?.(false) })
async function save() {
  if (busy.value || expired.value) return
  nameError.value = form.value.displayName.trim() ? '' : '请填写昵称。'
  if (nameError.value) return
  busy.value = true; error.value = ''
  try {
    const current = await api('/api/auth/me')
    if (current.id !== owner.value) { expired.value = true; return }
    const result = await api('/api/me/profile',{method:'PATCH',body:JSON.stringify({...form.value,expectedUserId:owner.value})})
    allowLeave = true; emit('user-updated',result); await router.push('/me'); emit('toast','个人资料已更新')
  } catch(e) { error.value=e.message; if (e.status===401 || /登录|账号已切换/.test(e.message)) expired.value=true }
  finally { busy.value=false }
}
async function login() {
  loginError.value = emailError(loginEmail.value)
  if (!loginError.value && !loginPassword.value) loginError.value='请输入演示登录密码。'
  if (loginError.value) return
  busy.value = true
  try { const u=await api('/api/auth/login',{method:'POST',body:JSON.stringify({email:loginEmail.value,password:loginPassword.value})});loginPassword.value='';sessionUser.value=u;if(u.id!==owner.value){loginError.value='登录账号与这份资料不同，请切回原账号后继续。';return}expired.value=false;error.value='' }
  catch(e){loginError.value=e.message}finally{busy.value=false}
}
</script>
<template>
  <section class="section profile-space"><div class="personal-container"><PersonalNav/><div class="profile-content">
    <div class="profile-page-heading"><div><span class="kicker">{{ edit ? 'EDIT PROFILE' : 'YOUR PROFILE' }}</span><h1>{{ edit ? '修改资料' : '个人资料' }}</h1><p>{{ edit ? '让大家更好地认识你。' : '记录你的介绍，也留下分享的足迹。' }}</p></div><RouterLink v-if="!edit && profile" class="btn btn-primary" to="/me/profile/edit">修改资料 <span aria-hidden="true">↗</span></RouterLink><RouterLink v-if="edit" class="account-back" to="/me">← 返回资料</RouterLink></div>
    <p v-if="loading" class="account-info" role="status">正在读取资料…</p><p v-if="error" class="account-error" role="alert">{{ error }}</p>
    <template v-if="profile && !loading">
      <template v-if="!edit">
        <article class="profile-overview"><div class="profile-identity"><div class="profile-avatar" aria-hidden="true">{{ initial }}</div><div><h2>{{ profile.displayName }}</h2><p>{{ joined }} 加入 FINNS</p></div><span class="profile-private">私人资料</span></div><div class="profile-about"><h3>关于我</h3><p :class="{'profile-empty':!profile.bio}">{{ profile.bio || '还没有填写简介。在修改资料中，介绍一下你的兴趣和关注的方向。' }}</p></div><div class="profile-address"><span>登录邮箱</span><strong>{{ profile.email }}</strong><span class="account-badge" :class="profile.emailVerified?'verified':'pending'">{{ profile.emailVerified?'已验证':'待验证' }}</span><small>用于登录和找回账号，当前不支持修改。</small></div></article>
        <div class="profile-numbers"><article><strong>{{ profile.postCount }}</strong><span>篇内容</span></article><article><strong>{{ profile.collectionCount }}</strong><span>个创建的专题</span></article><article><strong>{{ profile.selectedCollectionCount }}</strong><span>个自选专题</span></article></div>
        <div class="profile-security-link"><div><h3>账号与安全</h3><p>管理邮箱验证和登录密码。</p></div><RouterLink to="/me/security">前往管理 <span aria-hidden="true">→</span></RouterLink></div>
        <p class="profile-privacy-note">此处为本人资料管理，登录邮箱不会出现在公开作者主页。公开主页仍待后续评审。</p>
      </template>
      <template v-else>
        <form class="profile-editor" novalidate @submit.prevent="save"><div class="profile-editor-intro"><div class="profile-avatar small" aria-hidden="true">{{ initial }}</div><p>只修改昵称和个人简介，<br>登录邮箱保持不变。</p></div><fieldset :disabled="busy || expired"><label for="profile-name">昵称<input id="profile-name" aria-label="昵称" v-model="form.displayName" maxlength="40" autocomplete="nickname" :aria-invalid="!!nameError" aria-describedby="profile-name-error" placeholder="希望大家怎么称呼你" /><small v-if="nameError" id="profile-name-error" class="account-field-error" role="alert">{{ nameError }}</small></label><label for="profile-bio">个人简介<textarea id="profile-bio" aria-label="个人简介" v-model="form.bio" maxlength="500" rows="5" placeholder="聊聊你的兴趣、经历，或正在探索的方向…"/><small class="profile-field-foot"><span>可以留空，最多500字。</span><span>{{ form.bio.length }} / 500</span></small></label></fieldset><div class="profile-edit-email"><span>登录邮箱</span><strong>{{ profile.email }}</strong><small>邮箱无法在这里修改。</small></div><div class="profile-editor-actions"><span>{{ dirty?'有尚未保存的修改':'修改后点击保存即可生效' }}</span><div><RouterLink class="btn btn-ghost" to="/me">取消</RouterLink><button class="btn btn-primary" :disabled="busy || expired || !dirty">{{ busy?'保存中…':'保存修改' }}</button></div></div></form>
        <section v-if="expired" class="account-panel account-reauth" aria-label="资料编辑重新登录"><h2>登录状态已变化，修改仍保留</h2><p>请使用原账号重新登录，再继续保存。</p><form class="account-form" novalidate @submit.prevent="login"><label>邮箱<input v-model="loginEmail" type="email" autocomplete="email"/></label><label>演示登录密码<input v-model="loginPassword" type="password" autocomplete="current-password"/></label><p v-if="loginError" class="account-field-error" role="alert">{{ loginError }}</p><button class="btn btn-primary" :disabled="busy">重新登录并继续</button></form></section>
      </template>
    </template>
    <div v-if="discarding" class="overlay profile-confirm-overlay"><section class="modal profile-confirm" role="dialog" aria-modal="true" aria-labelledby="discard-title"><h2 id="discard-title">放弃这次修改？</h2><p>尚未保存的内容会被丢弃，原资料不会改变。</p><div class="account-buttons"><button class="btn btn-primary" @click="decideLeave(false)">继续编辑</button><button class="btn btn-ghost" @click="decideLeave(true)">放弃修改</button></div></section></div>
  </div></div></section>
</template>
