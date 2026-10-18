<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '@finns/lib/api'
import { sessionUser } from '../proto/session'
import { now } from '../mock/db'
import { emailError, passwordError } from '../proto/validation'

const route = useRoute(), router = useRouter()
const mode = computed(() => route.meta.accountMode || 'login')
const signup = computed(() => ['register', 'setup'].includes(mode.value) || route.query.purpose === 'signup')
const purpose = computed(() => signup.value ? 'signup' : ['pending', 'verify'].includes(mode.value) ? 'verify' : 'reset')
const titles = { login: '欢迎回来', register: '加入 FINNS', setup: '设置你的账号', forgot: '找回密码', pending: '验证邮件已申请', verify: '验证邮箱', reset: '设置新密码' }
const subtitles = { login: '登录，继续你的阅读与分享。', register: '先验证邮箱，再设置昵称和密码。', setup: '邮箱已验证，还差最后一步。', forgot: '填写账号邮箱，接收密码重置链接。', pending: '打开邮件中的链接，继续下一步。', verify: '确认后即可继续，无需重新填写邮箱。', reset: '重置成功后，请在各设备上重新登录。' }
const form = ref({ email: '', displayName: '', password: '', confirmPassword: '' })
const busy = ref(false), error = ref(''), message = ref(''), success = ref(false), link = ref(null), remaining = ref(0), fieldErrors = ref({}), emailTouched = ref(false), showPassword = ref(false)
const emailInput = ref(null)
let timer, cooldownUntil = 0
const email = computed(() => typeof route.query.email === 'string' ? route.query.email : form.value.email)
const tick = () => { remaining.value = Math.max(0, Math.ceil((cooldownUntil - now()) / 1000)); if (!remaining.value) clearInterval(timer) }
const startCooldown = () => { cooldownUntil = now() + 60000; tick(); clearInterval(timer); timer = setInterval(tick, 1000) }
const checkEmail = () => { emailTouched.value = true; fieldErrors.value.email = emailError(email.value); return !fieldErrors.value.email }
const updateEmail = () => { if (emailTouched.value) fieldErrors.value.email = emailError(email.value) }
onMounted(() => window.addEventListener('finns-proto-change', tick))
watch(() => route.fullPath, async () => {
  clearInterval(timer); cooldownUntil = 0; remaining.value = 0; busy.value = false; error.value = ''; message.value = ''; success.value = false; link.value = null; fieldErrors.value = {}; emailTouched.value = false; showPassword.value = false
  form.value = { email: typeof route.query.email === 'string' ? route.query.email : '', displayName: '', password: '', confirmPassword: '' }
  if (mode.value === 'pending' && route.query.sent === '1') startCooldown()
  if (['verify', 'reset', 'setup'].includes(mode.value)) {
    busy.value = true
    try {
      link.value = await api(`/api/auth/link?purpose=${purpose.value}&token=${encodeURIComponent(route.query.token || '')}`)
      if (mode.value === 'setup' && !link.value.verified) { link.value = null; error.value = '请先打开邮件，完成邮箱验证。' }
    } catch (e) { error.value = e.message }
    finally { busy.value = false }
  }
}, { immediate: true })
onBeforeUnmount(() => { window.removeEventListener('finns-proto-change', tick); clearInterval(timer); form.value.password = ''; form.value.confirmPassword = '' })
async function requestLink() {
  if (busy.value || !checkEmail()) return
  busy.value = true; error.value = ''
  try { const r = await api('/api/auth/request-link', { method: 'POST', body: JSON.stringify({ email: email.value, purpose: purpose.value }) }); message.value = r.message; startCooldown(); if (mode.value === 'forgot') success.value = true }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function submit() {
  if (busy.value) return
  error.value = ''; fieldErrors.value = {}
  if (['login', 'register', 'forgot'].includes(mode.value) && !checkEmail()) { emailInput.value?.focus(); return }
  if (mode.value === 'login' && !form.value.password) { fieldErrors.value.password = '请输入登录密码。'; return }
  if (['setup', 'reset'].includes(mode.value)) {
    if (mode.value === 'setup' && !form.value.displayName.trim()) fieldErrors.value.displayName = '请填写一个昵称。'
    fieldErrors.value.password = passwordError(form.value.password, form.value.confirmPassword)
    if (Object.values(fieldErrors.value).some(Boolean)) return
  }
  if (mode.value === 'forgot') { await requestLink(); return }
  busy.value = true
  try {
    if (mode.value === 'login') {
      const u = await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email: form.value.email, password: form.value.password }) })
      sessionUser.value = u; form.value.password = ''
      const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') && !route.query.next.startsWith('//') ? route.query.next : '/me'
      await router.replace(next)
    } else if (mode.value === 'register') {
      const r = await api('/api/auth/signup-link', { method: 'POST', body: JSON.stringify({ email: form.value.email }) })
      await router.push({ path: '/auth/pending', query: { email: r.email, purpose: 'signup', sent: '1' } })
    } else if (mode.value === 'verify' && signup.value) {
      await api('/api/auth/signup-verify', { method: 'POST', body: JSON.stringify({ token: route.query.token }) })
      await router.push({ path: '/auth/setup', query: { token: route.query.token } })
    } else if (mode.value === 'setup') {
      const r = await api('/api/auth/register', { method: 'POST', body: JSON.stringify({ ...form.value, token: route.query.token }) })
      form.value.password = ''; form.value.confirmPassword = ''; sessionUser.value = null; message.value = r.message; success.value = true
    } else if (mode.value === 'verify') {
      const r = await api('/api/auth/verify', { method: 'POST', body: JSON.stringify({ token: route.query.token }) }); success.value = true; message.value = r.legacy ? '邮箱已验证，你可以继续使用当前账号。' : '邮箱已验证，请返回登录。'
    } else if (mode.value === 'reset') {
      const r = await api('/api/auth/reset', { method: 'POST', body: JSON.stringify({ token: route.query.token, password: form.value.password, confirmPassword: form.value.confirmPassword }) })
      form.value.password = ''; form.value.confirmPassword = ''; sessionUser.value = null; success.value = true; message.value = r.message
      if (r.noticeFailed) message.value += ' 密码已更新，通知邮件暂未送达。'
    }
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>

<template>
  <section class="account-stage">
    <div class="account-card">
      <RouterLink class="account-back" to="/">← 返回首页</RouterLink>
      <ol v-if="signup && !success" class="account-progress" aria-label="注册步骤"><li :class="{done:mode==='setup',active:mode!=='setup'}"><span>{{ mode==='setup'?'✓':'1' }}</span>验证邮箱</li><li :class="{active:mode==='setup'}"><span>2</span>设置账号</li></ol>
      <h1>{{ success && mode !== 'forgot' ? (mode === 'setup' ? '账号已创建' : '操作已完成') : titles[mode] }}</h1>
      <p v-if="!success" class="account-muted">{{ subtitles[mode] }}</p>
      <template v-if="success"><div class="account-success" role="status"><span class="account-status-icon">✓</span><p>{{ message }}</p></div><RouterLink v-if="mode !== 'forgot'" class="btn btn-primary btn-block" to="/auth/login">前往登录</RouterLink><button v-else class="btn btn-primary btn-block" :disabled="busy || remaining > 0" @click="requestLink">{{ remaining ? `${remaining} 秒后可重新发送` : '重新发送' }}</button></template>
      <template v-else-if="mode === 'pending'">
        <div class="account-envelope" aria-hidden="true">✉</div>
        <label v-if="!route.query.email" class="account-draft-label">邮箱<input ref="emailInput" v-model="form.email" type="email" autocomplete="email" :aria-invalid="!!fieldErrors.email" aria-describedby="email-error" placeholder="name@example.com" @blur="checkEmail" @input="updateEmail" /></label>
        <p v-if="fieldErrors.email" id="email-error" class="account-field-error" role="alert">{{ fieldErrors.email }}</p>
        <p>如果邮箱可接收账号邮件，验证链接将发送至<br><strong class="account-email">{{ email }}</strong></p><p class="account-muted">链接24小时内有效。没有收到时，可以检查垃圾邮件或稍后重发。</p>
        <p v-if="message" class="account-info" role="status">{{ message }}</p><p v-if="error" class="account-error" role="alert">{{ error }}</p>
        <button class="btn btn-primary btn-block" :disabled="busy || remaining > 0" @click="requestLink">{{ busy ? '发送中…' : remaining ? `${remaining} 秒后可重新发送` : '重新发送验证邮件' }}</button>
        <RouterLink class="account-text-link" to="/auth/register">更换邮箱</RouterLink>
      </template>
      <template v-else-if="['verify', 'reset', 'setup'].includes(mode) && !link"><div v-if="busy" class="account-info" role="status">正在检查链接…</div><template v-else><div class="account-expired" role="alert"><span class="account-status-icon">!</span><p>{{ error || '未找到有效链接。' }}</p></div><RouterLink class="btn btn-primary btn-block" :to="signup ? '/auth/register' : mode === 'verify' ? '/auth/pending' : '/auth/forgot'">重新申请链接</RouterLink></template></template>
      <form v-else class="account-form" novalidate @submit.prevent="submit">
        <div v-if="link" class="account-verified-email"><span aria-hidden="true">{{ mode === 'setup' ? '✓' : '＠' }}</span><div><small>{{ mode === 'setup' ? '已验证邮箱' : '账号邮箱' }}</small><strong>{{ link.email }}</strong></div></div>
        <p v-if="mode === 'reset' && link?.needsVerification" class="account-muted">本次操作会同时完成邮箱验证与密码重置。</p>
        <label v-if="['login', 'register', 'forgot'].includes(mode)" for="account-email">邮箱<input id="account-email" aria-label="邮箱" ref="emailInput" v-model="form.email" type="email" autocomplete="email" :aria-invalid="!!fieldErrors.email" aria-describedby="email-error" placeholder="name@example.com" @blur="checkEmail" @input="updateEmail" /><span v-if="fieldErrors.email" id="email-error" class="account-field-error" role="alert">{{ fieldErrors.email }}</span></label>
        <label v-if="mode === 'setup'" for="account-nickname">昵称<input id="account-nickname" aria-label="昵称" v-model="form.displayName" autocomplete="nickname" maxlength="40" :aria-invalid="!!fieldErrors.displayName" placeholder="希望大家怎么称呼你" /><span v-if="fieldErrors.displayName" class="account-field-error" role="alert">{{ fieldErrors.displayName }}</span></label>
        <label v-if="['login', 'setup', 'reset'].includes(mode)" for="account-password">{{ mode === 'reset' ? '新密码' : '密码' }}<input id="account-password" :aria-label="mode === 'reset' ? '新密码' : '密码'" v-model="form.password" :type="showPassword ? 'text' : 'password'" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :maxlength="mode === 'login' ? undefined : 20" :aria-invalid="!!fieldErrors.password" aria-describedby="password-note password-error" :placeholder="mode === 'login' ? '请输入登录密码' : '设置你的登录密码'" /><small v-if="mode !== 'login'" id="password-note" class="account-field-hint">至少8位，最多20位。</small></label>
        <label v-if="['setup', 'reset'].includes(mode)" for="account-password-confirm">确认密码<input id="account-password-confirm" aria-label="确认密码" v-model="form.confirmPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" maxlength="20" :aria-invalid="!!fieldErrors.password" placeholder="再输入一次密码" /></label>
        <p v-if="fieldErrors.password" id="password-error" class="account-field-error" role="alert">{{ fieldErrors.password }}</p>
        <label v-if="['setup', 'reset'].includes(mode)" class="account-checkbox"><input v-model="showPassword" type="checkbox" />显示密码</label>
        <RouterLink v-if="mode === 'login'" class="account-text-link account-align-right" to="/auth/forgot">忘记密码？</RouterLink>
        <p v-if="error" class="account-error" role="alert">{{ error }}</p>
        <button class="btn btn-primary btn-block" :disabled="busy">{{ busy ? '处理中…' : mode === 'verify' && signup ? '验证邮箱并继续' : {login:'登录',register:'发送验证邮件',setup:'完成注册',forgot:'发送重置链接',verify:'确认验证邮箱',reset:'确认重置密码'}[mode] }}</button>
      </form>
      <div class="account-footer-links"><RouterLink v-if="mode === 'login'" to="/auth/register">还没有账号？创建账号</RouterLink><RouterLink v-else-if="!success" to="/auth/login">已有账号？登录</RouterLink></div>
      <p class="account-purpose">分享所知，让彼此走得更远。</p>
      <p class="account-demo-note">原型演示：邮件请在右下角“账号演示”查看，请勿填写真实密码。</p>
    </div>
  </section>
</template>
