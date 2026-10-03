<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { api } from './lib/api'

const router = useRouter()
const route = useRoute()
const user = ref(null)
const authOpen = ref(false)
const registerMode = ref(false)
const composerOpen = ref(false)
const authError = ref('')
const authBusy = ref(false)
const authForm = ref({ email: '', password: '', displayName: '' })
const toastMessage = ref('')
let toastTimer

const activePath = computed(() => route.path)
const showWrite = () => {
  if (!user.value) { registerMode.value = false; authOpen.value = true; authError.value = '登录后才能发布内容'; return }
  composerOpen.value = true
}
const showToast = (message) => { toastMessage.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toastMessage.value = '' }, 2400) }
const openAuth = (register = false) => { registerMode.value = register; authError.value = ''; authOpen.value = true }
const submitAuth = async () => {
  authBusy.value = true; authError.value = ''
  try {
    const payload = registerMode.value
      ? authForm.value
      : { email: authForm.value.email, password: authForm.value.password }
    user.value = await api(`/api/auth/${registerMode.value ? 'register' : 'login'}`, { method: 'POST', body: JSON.stringify(payload) })
    authOpen.value = false; authForm.value = { email: '', password: '', displayName: '' }; showToast(`你好，${user.value.displayName}`)
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') && !route.query.next.startsWith('//') ? route.query.next : ''
    if (route.query.signin === '1') router.replace(next || '/')
  } catch (error) { authError.value = error.message } finally { authBusy.value = false }
}
const logout = async () => { await api('/api/auth/logout', { method: 'POST' }); user.value = null; showToast('已退出登录'); if (route.meta.requiresAuth) router.push('/') }
const closeDialogs = () => { authOpen.value = false; composerOpen.value = false }
onMounted(async () => { try { user.value = await api('/api/auth/me') } catch { user.value = null } })
watch(() => route.query.signin, (value) => { if (value === '1') openAuth() }, { immediate: true })
watch(() => route.query.create, (value) => { if (value === 'post' || value === 'topic') composerOpen.value = true }, { immediate: true })
</script>

<template>
  <div class="app-shell">
    <div class="ambient ambient-a"></div><div class="ambient ambient-b"></div>
    <header class="site-header">
      <RouterLink class="brand" to="/" aria-label="FINNS 首页"><span class="brand-mark">F</span><span>FINNS</span></RouterLink>
      <nav class="main-nav" aria-label="主导航">
        <RouterLink :class="{ active: activePath === '/' }" to="/">发现</RouterLink>
        <RouterLink :class="{ active: activePath === '/collections' }" to="/collections">专题</RouterLink>
        <RouterLink :class="{ active: activePath === '/my/collections' }" to="/my/collections">自选专题</RouterLink>
      </nav>
      <div class="header-actions"><button class="icon-btn" aria-label="回到顶部" @click="router.push('/')">⌕</button><button v-if="!user" class="btn btn-ghost" @click="openAuth()">登录</button><template v-else><RouterLink class="btn btn-ghost user-button" to="/me">{{ user.displayName }}</RouterLink><button class="btn btn-ghost logout-button" @click="logout">退出</button></template><button class="btn btn-primary" @click="showWrite">开始创作 <span>↗</span></button></div>
    </header>

    <main><RouterView :user="user" @toast="showToast" @write="showWrite" @user-updated="user = $event" /></main>

    <footer><RouterLink class="brand" to="/" aria-label="FINNS 首页"><span class="brand-mark">F</span><span>FINNS</span></RouterLink><p>为独立思考与开放创造而生。</p><div class="footer-links"><RouterLink to="/collections">专题广场</RouterLink><RouterLink to="/vps">VPS 指南</RouterLink><RouterLink to="/me">个人中心</RouterLink></div><span>© 2026 FINNS</span></footer>

    <div v-if="authOpen" class="overlay" @click.self="closeDialogs">
      <section class="modal" role="dialog" aria-modal="true" aria-labelledby="auth-title">
        <button class="modal-close" type="button" aria-label="关闭" @click="authOpen = false">×</button>
        <span class="kicker">WELCOME</span>
        <h2 id="auth-title">{{ registerMode ? '加入 FINNS' : '欢迎回来' }}</h2>
        <p>{{ registerMode ? '创建账号，开始你的第一次分享。' : '登录后继续写作与讨论。' }}</p>
        <form @submit.prevent="submitAuth">
          <label v-if="registerMode">昵称<input v-model="authForm.displayName" autocomplete="nickname" maxlength="40" required placeholder="大家如何称呼你" /></label>
          <label>邮箱<input v-model="authForm.email" autocomplete="email" type="email" required placeholder="you@example.com" /></label>
          <label>密码<input v-model="authForm.password" :autocomplete="registerMode ? 'new-password' : 'current-password'" type="password" :minlength="registerMode ? 8 : undefined" required :placeholder="registerMode ? '至少 8 位' : '请输入密码'" /></label>
          <p class="form-error" role="alert">{{ authError }}</p>
          <button class="btn btn-primary btn-block" :disabled="authBusy">{{ authBusy ? '处理中…' : '继续' }}</button>
        </form>
        <button class="switch-btn" type="button" @click="openAuth(!registerMode)">{{ registerMode ? '已有账号？返回登录' : '还没有账号？立即注册' }}</button>
      </section>
    </div>

    <div v-if="composerOpen" class="overlay" @click.self="composerOpen = false"><section class="modal modal-wide" role="dialog" aria-modal="true"><button class="modal-close" aria-label="关闭" @click="composerOpen = false">×</button><span class="kicker">CREATE</span><h2>分享新的内容</h2><ComposerForm @collection="composerOpen = false; router.push('/me/collections/new')" @done="composerOpen = false; showToast('内容已发布')" @error="showToast" /></section></div>
    <div v-if="toastMessage" class="toast" role="status">{{ toastMessage }}</div>
  </div>
</template>

<script>
import ComposerForm from './components/ComposerForm.vue'
export default { components: { ComposerForm } }
</script>
