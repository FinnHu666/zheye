<script setup>
// 账号安全：邮箱验证状态、原密码修改、已登录但忘记原密码时的邮箱重置。
// 密码只留在本页内存，成功、取消或离开页面即清空，不进入任何持久存储。
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '../lib/api'
import { currentUser } from '../lib/session'
import { passwordError } from '../lib/accountValidation'
import PersonalLayout from '../components/PersonalLayout.vue'

const profile = ref(null), busy = ref(false), error = ref(''), result = ref(''), verifyInfo = ref(''), editing = ref(false), showPassword = ref(false)
const recoveryOpen = ref(false), recoveryBusy = ref(false), recoveryError = ref(''), recoveryInfo = ref(''), remaining = ref(0)
let cooldownUntil = 0, cooldownTimer
const tick = () => { remaining.value = Math.max(0, Math.ceil((cooldownUntil - Date.now()) / 1000)) }
const setCooldown = seconds => { cooldownUntil = Date.now() + seconds * 1000; tick() }
const form = ref({ oldPassword: '', password: '', confirmPassword: '' })
const clear = () => { form.value = { oldPassword: '', password: '', confirmPassword: '' }; editing.value = false }
const load = async () => { try { profile.value = await api('/api/account/security'); setCooldown(profile.value.resetCooldown || 0) } catch (e) { profile.value = null; error.value = e.message } }
onMounted(() => { load(); cooldownTimer = setInterval(tick, 1000) })
onBeforeUnmount(() => { clear(); clearInterval(cooldownTimer) })
watch(() => currentUser.value?.id, () => { clear(); recoveryOpen.value = false; recoveryInfo.value = ''; recoveryError.value = ''; load() })
const openRecovery = () => { clear(); error.value = ''; result.value = ''; recoveryOpen.value = true }
const openPassword = () => { recoveryOpen.value = false; editing.value = true; error.value = ''; result.value = '' }

async function requestRecovery() {
  if (recoveryBusy.value || remaining.value > 0) return
  recoveryBusy.value = true; recoveryError.value = ''
  try { const r = await api('/api/account/password-reset-link', { method: 'POST', body: '{}' }); recoveryInfo.value = r.message; setCooldown(r.cooldown) }
  catch (e) { recoveryError.value = e.message }
  finally { recoveryBusy.value = false }
}
async function verify() {
  busy.value = true; error.value = ''
  try { const r = await api('/api/account/verification-link', { method: 'POST', body: '{}' }); verifyInfo.value = r.message }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
async function save() {
  error.value = passwordError(form.value.password, form.value.confirmPassword)
  if (!error.value && !form.value.oldPassword) error.value = '请输入原密码。'
  if (error.value) return
  busy.value = true; result.value = ''
  try {
    const r = await api('/api/account/password', { method: 'POST', body: JSON.stringify(form.value) })
    result.value = r.message + (r.noticeFailed ? ' 密码已更新，通知邮件暂未送达。' : ''); clear(); await load()
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <PersonalLayout class="account-security" kicker="ACCOUNT & SECURITY" title="账号安全" desc="管理你的登录方式，让创作安心继续。"><div class="account-security-main"><p v-if="error && !profile" class="account-error" role="alert">{{ error }}</p><div v-if="!profile" class="account-info"><template v-if="error">请重新登录后管理账号。<RouterLink to="/auth/login?next=/me/security">前往登录 →</RouterLink></template><template v-else>正在读取账号…</template></div><template v-else>
    <article class="account-panel"><div class="account-panel-head"><span class="account-icon">＠</span><div><h2>邮箱验证</h2><p>{{ profile.email }}</p></div><span :class="['account-badge', profile.emailVerified ? 'verified' : 'pending']">{{ profile.emailVerified ? '已验证' : '待补验证' }}</span></div><template v-if="!profile.emailVerified"><p>你的旧账号可以继续使用。验证邮箱后，找回密码会更方便。</p><button class="btn btn-primary" :disabled="busy" @click="verify">发送验证邮件</button></template><p v-else class="account-muted">邮箱是你的账号恢复方式，当前不提供修改登录邮箱。</p><p v-if="verifyInfo" class="account-info" role="status">{{ verifyInfo }}</p></article>
    <article class="account-panel"><div class="account-panel-head"><span class="account-icon">＊</span><div><h2>登录密码</h2><p>{{ recoveryOpen ? '忘记原密码，也可以通过账号邮箱重置。' : '使用原密码修改后，当前设备保持登录。' }}</p></div><button v-if="!editing && !recoveryOpen" class="btn btn-ghost" @click="openPassword">修改密码</button></div><div v-if="result" class="account-info" role="status">{{ result }}</div><div v-if="!recoveryOpen" class="password-recovery-entry"><button class="account-link-button" type="button" @click="openRecovery">忘记原密码？通过邮箱重置 →</button><small>无需原密码，重置完成后需重新登录。</small></div><section v-if="recoveryOpen" class="password-email-recovery" aria-label="通过邮箱重置密码"><div class="password-recovery-email"><span>当前账号邮箱</span><strong>{{ profile.email }}</strong></div><p>我们会向这个邮箱发送一次性重置链接，无需再次填写邮箱或输入原密码。</p><p v-if="!profile.emailVerified" class="account-info">该邮箱尚未验证，本次邮件操作将同时完成邮箱验证与密码重置。</p><p class="password-recovery-rule">申请链接时保持当前登录；完成重置后，包含当前设备在内的所有设备都会退出。</p><p v-if="recoveryInfo" class="account-info" role="status">{{ recoveryInfo }} 请在30分钟内打开最新链接。</p><p v-if="recoveryError" class="account-error" role="alert">{{ recoveryError }}</p><div class="account-buttons"><button class="btn btn-primary" type="button" :disabled="recoveryBusy || remaining > 0" @click="requestRecovery">{{ recoveryBusy ? '申请中…' : remaining ? `${remaining} 秒后可重新发送` : recoveryInfo ? '重新发送重置邮件' : '发送重置邮件' }}</button><button class="btn btn-ghost" type="button" :disabled="recoveryBusy" @click="openPassword">返回原密码修改</button></div></section><form v-if="editing && !recoveryOpen" class="account-form" novalidate @submit.prevent="save"><label>原密码<input v-model="form.oldPassword" type="password" autocomplete="current-password" required placeholder="请输入原密码" /></label><label>新密码<input v-model="form.password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="8" maxlength="20" required placeholder="设置新的登录密码" /></label><small class="account-field-hint">至少8位，最多20位。</small><label>确认新密码<input v-model="form.confirmPassword" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="8" maxlength="20" required placeholder="再次输入新密码" /></label><label class="account-checkbox"><input v-model="showPassword" type="checkbox" />显示新密码</label><p v-if="error" class="account-error" role="alert">{{ error }}</p><div class="account-buttons"><button class="btn btn-primary" :disabled="busy">{{ busy ? '保存中…' : '保存新密码' }}</button><button class="btn btn-ghost" type="button" @click="clear">取消</button></div></form></article>
  </template></div></PersonalLayout>
</template>
