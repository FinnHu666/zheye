<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { api } from './lib/api'
import { reportSiteEntry } from './lib/siteVisit'
import { currentUser as user, can, refreshSession } from './lib/session'

const router = useRouter()
const route = useRoute()
const composerOpen = ref(false)
const toastMessage = ref('')
const logoutBusy = ref(false)
let toastTimer

const activePath = computed(() => route.path)
// 页面按权限展示；无权限时保留地址并显示 403（直达/刷新一致），接口由服务端另行检查。
const requiredPerm = computed(() => route.matched.map((record) => record.meta.perm).filter(Boolean).pop())
const forbidden = computed(() => !!user.value && !!requiredPerm.value && !can(requiredPerm.value))
const showWrite = () => {
  if (!user.value) { openAuth(); return }
  composerOpen.value = true
}
const showToast = (message) => { toastMessage.value = message; clearTimeout(toastTimer); toastTimer = setTimeout(() => { toastMessage.value = '' }, 2400) }
const openAuth = (register = false) => router.push({ path: register ? '/auth/register' : '/auth/login', query: route.path.startsWith('/auth') ? {} : { next: route.fullPath } })
const logout = async () => {
  if (logoutBusy.value) return
  logoutBusy.value = true
  try {
    // Run the editor's leave guard while the session can still save its private draft.
    if (route.meta.requiresAuth && await router.push('/')) return
    await api('/api/auth/logout', { method: 'POST' })
    user.value = null
    showToast('已退出登录')
  } catch (error) { showToast(error.message) }
  finally { logoutBusy.value = false }
}
onMounted(() => { reportSiteEntry(); refreshSession() })
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
        <RouterLink v-if="can('admin.users')" :class="{ active: activePath.startsWith('/admin') }" to="/admin/users">管理</RouterLink>
      </nav>
      <div class="header-actions"><button class="icon-btn" aria-label="回到顶部" @click="router.push('/')">⌕</button><button v-if="!user" class="btn btn-ghost" @click="openAuth()">登录</button><template v-else><RouterLink class="btn btn-ghost user-button" to="/me">个人中心</RouterLink><button class="btn btn-ghost logout-button" :disabled="logoutBusy" @click="logout">退出</button></template><button v-if="!user || can('post.create')" class="btn btn-primary" @click="showWrite">开始创作 <span>↗</span></button></div>
    </header>

    <main>
      <section v-if="forbidden" class="page-hero personal-hero forbidden-page" role="alert">
        <span class="kicker">403 · 无访问权限</span>
        <h1>当前角色<br /><em>无法访问此页面。</em></h1>
        <p>你的角色是「{{ user.role?.name }}」，这个页面需要的权限当前角色没有开启。地址已保留，角色调整后刷新即可进入；如需开通请联系管理员。</p>
        <div class="hero-actions forbidden-actions"><RouterLink class="btn btn-primary" to="/">返回首页</RouterLink><RouterLink class="text-link" to="/me">查看个人资料 →</RouterLink></div>
      </section>
      <RouterView v-else :user="user" @toast="showToast" @write="showWrite" @user-updated="user = { ...user, ...$event }" />
    </main>

    <footer><RouterLink class="brand" to="/" aria-label="FINNS 首页"><span class="brand-mark">F</span><span>FINNS</span></RouterLink><p>为独立思考与开放创造而生。</p><div class="footer-links"><RouterLink to="/collections">专题广场</RouterLink><RouterLink to="/vps">VPS 指南</RouterLink><RouterLink to="/me">个人中心</RouterLink><RouterLink v-if="can('admin.users')" to="/admin/users">管理</RouterLink></div><span>© 2026 FINNS</span></footer>

    <div v-if="composerOpen" class="overlay" @click.self="composerOpen = false"><section class="modal modal-wide" role="dialog" aria-modal="true"><button class="modal-close" aria-label="关闭" @click="composerOpen = false">×</button><span class="kicker">CREATE</span><h2>分享新的内容</h2><ComposerForm @ai-collection="composerOpen = false; router.push('/me/collections/ai/new')" @collection="composerOpen = false; router.push('/me/collections/new')" @done="composerOpen = false; showToast('内容已发布')" @error="showToast" /></section></div>
    <div v-if="toastMessage" class="toast show" role="status">{{ toastMessage }}</div>
  </div>
</template>

<script>
import ComposerForm from './components/ComposerForm.vue'
export default { components: { ComposerForm } }
</script>
