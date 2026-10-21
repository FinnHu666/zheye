<!-- 原型覆盖：frontend/src/App.vue。除标注“0.7.0”的部分外，与当前源码保持一致。 -->
<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { api } from '@finns/lib/api'
import { sessionUser as user, can, siteVisit } from '../proto/session'
import ProtoConsole from '../components/ProtoConsole.vue'
import AccountLab from '../components/AccountLab.vue'
import UserAdminLab from '../components/UserAdminLab.vue'
import ReviewLab from '../components/ReviewLab.vue'

const router = useRouter()
const route = useRoute()
const composerOpen = ref(false)
const toastMessage = ref('')
const logoutBusy = ref(false)
let toastTimer

const activePath = computed(() => route.path)
const reviewContext = computed(() => ['/admin/reviews', '/admin/sensitive-rules', '/me/submissions', '/review/reading'].some(path => route.path.startsWith(path) || String(route.query.next || '').startsWith(path)))
const managementPath = computed(() => can('admin.users') ? '/admin/users' : '/admin/reviews')
// 0.7.0：页面按角色展示；无权限时保留地址并显示拒绝页（直达/刷新一致）。
const requiredPerm = computed(() => route.matched.map((record) => record.meta.perm).filter(Boolean).pop())
const forbidden = computed(() => !!user.value && ((!!requiredPerm.value && !can(requiredPerm.value)) || (route.meta.ownerOnly && user.value.role !== 'OWNER')))
const showWrite = () => {
  if (!user.value) { openAuth(); return }
  if (reviewContext.value) { router.push('/me/submissions'); return }
  composerOpen.value = true
}
const showToast = (message) => { toastMessage.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toastMessage.value = '' }, 2400) }
const openAuth = (register = false) => router.push({ path: register ? '/auth/register' : '/auth/login', query: typeof route.query.next === 'string' ? { next: route.query.next } : {} })
const logout = async () => {
  if (logoutBusy.value) return
  logoutBusy.value = true
  try {
    if (route.meta.requiresAuth && await router.push('/')) return
    await api('/api/auth/logout', { method: 'POST' })
    user.value = null
    showToast('已退出登录')
  } catch (error) { showToast(error.message) }
  finally { logoutBusy.value = false }
}

// 0.7.0 FINNS-10：每次加载网站上报一次“进入网站”，由服务端按访问窗口去重；
// 站内切换只刷新活跃时间，不计数。上报失败静默，不影响使用。
const reportEntry = async () => {
  try { const result = await api('/api/site-visits', { method: 'POST', body: JSON.stringify({ kind: 'entry' }) }); siteVisit.value = result }
  catch { siteVisit.value = { total: null, counted: null, reason: '上报失败' } }
}
let firstNavigation = true
router.afterEach(() => {
  if (firstNavigation) { firstNavigation = false; return }
  api('/api/site-visits', { method: 'POST', body: JSON.stringify({ kind: 'touch' }) }).catch(() => {})
})

const refreshAccount = async () => { try { user.value = await api('/api/auth/me') } catch { user.value = null } }
onMounted(async () => { await refreshAccount(); reportEntry(); window.addEventListener('finns-account-session', refreshAccount) })
onBeforeUnmount(() => window.removeEventListener('finns-account-session', refreshAccount))
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
        <RouterLink v-if="can('admin.users') || can('content.review')" :class="{ active: activePath.startsWith('/admin') }" :to="managementPath">管理</RouterLink>
      </nav>
      <div class="header-actions"><button class="icon-btn" aria-label="回到顶部" @click="router.push('/')">⌕</button><button v-if="!user" class="btn btn-ghost" @click="openAuth()">登录</button><template v-else><RouterLink class="btn btn-ghost user-button" to="/me">个人中心</RouterLink><span class="proto-role-chip" :title="`当前角色：${user.roleLabel}`">{{ user.roleLabel }}</span><button class="btn btn-ghost logout-button" :disabled="logoutBusy" @click="logout">退出</button></template><button v-if="!user || can('post.create')" class="btn btn-primary" @click="showWrite">开始创作 <span>↗</span></button></div>
    </header>

    <div class="account-prototype-bar"><span>{{ reviewContext ? 'FINNS 0.7.0 · 内容审核原型 R3（需规修订6）· 仅浏览器模拟' : 'FINNS 0.7.0 · 用户管理原型 R3 / 账号 R2.1 · 仅浏览器模拟' }}</span><RouterLink class="rv-entry-link" to="/review/reading">体验内容审核 →</RouterLink><RouterLink v-if="!reviewContext" to="/admin/users">用户管理 →</RouterLink></div>
    <main>
      <section v-if="forbidden" class="page-hero personal-hero proto-forbidden" role="alert">
        <span class="kicker">403 · 无访问权限</span>
        <h1>当前角色<br /><em>无法访问此页面。</em></h1>
        <p>你的角色是「{{ user.roleLabel }}」。这个页面需要的权限当前角色没有开启。地址已保留，角色调整后刷新即可进入；如需开通请联系管理员。</p>
        <div class="hero-actions proto-forbidden-actions"><RouterLink class="btn btn-primary" to="/">返回首页</RouterLink><RouterLink class="text-link" to="/me">查看个人资料 →</RouterLink></div>
      </section>
      <RouterView v-else :user="user" @toast="showToast" @write="showWrite" @user-updated="user = { ...user, ...$event }" />
    </main>

    <footer><RouterLink class="brand" to="/" aria-label="FINNS 首页"><span class="brand-mark">F</span><span>FINNS</span></RouterLink><p>为独立思考与开放创造而生。</p><div class="footer-links"><RouterLink to="/collections">专题广场</RouterLink><RouterLink to="/vps">VPS 指南</RouterLink><RouterLink to="/me">个人中心</RouterLink><RouterLink v-if="can('admin.users')" to="/admin/users">管理</RouterLink></div><span>© 2026 FINNS</span></footer>

    <div v-if="composerOpen" class="overlay" @click.self="composerOpen = false"><section class="modal modal-wide" role="dialog" aria-modal="true"><button class="modal-close" aria-label="关闭" @click="composerOpen = false">×</button><span class="kicker">CREATE</span><h2>分享新的内容</h2><ComposerForm @ai-collection="composerOpen = false; router.push('/me/collections/ai/new')" @collection="composerOpen = false; router.push('/me/collections/new')" @done="composerOpen = false; showToast('内容已发布')" @error="showToast" /></section></div>
    <div v-if="toastMessage" class="toast show" role="status">{{ toastMessage }}</div>
    <ProtoConsole v-if="!reviewContext && !route.path.startsWith('/admin')" />
    <AccountLab v-if="!reviewContext" />
    <UserAdminLab v-if="!reviewContext" />
    <ReviewLab v-if="reviewContext" />
  </div>
</template>

<script>
import ComposerForm from '@finns/components/ComposerForm.vue'
export default { components: { ComposerForm } }
</script>
