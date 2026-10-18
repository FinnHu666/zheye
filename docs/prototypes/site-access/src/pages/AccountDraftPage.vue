<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { api } from '@finns/lib/api'
import { sessionUser } from '../proto/session'
import PersonalNav from '@finns/components/PersonalNav.vue'
const router=useRouter(),owner=ref(sessionUser.value?.id),text=ref('一段尚未提交的演示笔记：我的灵感还在这里。'),saved=ref(false),expired=ref(false),email=ref(sessionUser.value?.email||''),password=ref(''),error=ref(''),busy=ref(false)
const detect=async()=>{try{const u=await api('/api/auth/me');if(u.id!==owner.value)expired.value=true}catch{expired.value=true}}
onMounted(()=>window.addEventListener('finns-account-session',detect))
onBeforeUnmount(()=>{window.removeEventListener('finns-account-session',detect);password.value='';text.value=''})
onBeforeRouteLeave(()=>saved.value||!text.value||window.confirm('这段演示稿尚未提交。确定离开并丢弃吗？'))
async function login(){busy.value=true;error.value='';try{const u=await api('/api/auth/login',{method:'POST',body:JSON.stringify({email:email.value,password:password.value})});password.value='';sessionUser.value=u;if(u.id!==owner.value){error.value='当前登录的是另一个账号，不能提交之前账号的内容。请切回原账号，或离开丢弃演示稿。';return}expired.value=false}catch(e){error.value=e.message}finally{busy.value=false}}
async function submit(){try{const u=await api('/api/auth/me');if(u.id!==owner.value){expired.value=true;return}saved.value=true}catch{expired.value=true}}
</script>
<template><section class="section account-security"><div class="personal-container"><PersonalNav/><div class="account-security-main"><span class="kicker">SESSION RECOVERY · DEMO</span><h1>让灵感留在原地。</h1><p class="account-muted">账号原型中的非敏感演示稿，不会提交到真实项目或保存到浏览器草稿。打开右下“账号演示 → 场景控制”，让当前会话失效。</p><div class="account-panel"><label class="account-draft-label">尚未提交的演示内容<textarea v-model="text" rows="9" :disabled="expired || saved"/></label><div class="account-buttons"><button class="btn btn-primary" :disabled="saved" @click="submit">{{ saved?'已模拟提交':'模拟提交' }}</button><button class="btn btn-ghost" @click="saved=true;router.push('/me/security')">丢弃演示稿并返回</button></div><p v-if="saved" class="account-info" role="status">模拟提交完成。没有写入真实业务数据。</p></div>
<div v-if="expired" class="account-panel account-reauth" role="region" aria-label="重新登录保护"><h2>登录已失效，内容还在。</h2><p>请使用原账号重新登录后继续。切换账号不能提交这段内容。</p><form class="account-form" @submit.prevent="login"><label>邮箱<input v-model="email" type="email" autocomplete="email" required/></label><label>演示登录密码<input v-model="password" type="password" autocomplete="current-password" required/></label><p v-if="error" class="account-error" role="alert">{{ error }}</p><button class="btn btn-primary" :disabled="busy">{{busy?'登录中…':'重新登录并继续'}}</button></form></div></div></div></section></template>
