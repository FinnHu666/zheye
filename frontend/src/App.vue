<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { api } from './lib/api'
import { reportSiteEntry } from './lib/siteVisit'
import { currentUser as user, can, refreshSession } from './lib/session'

const router = useRouter()
const route = useRoute()
const composerOpen = ref(false)
const toastMessage = ref('')
const searchInput = ref(null)
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
const searchSoon = () => showToast('搜索功能暂未开放')
const focusSearch = (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); searchInput.value?.focus() } }
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
onMounted(() => { reportSiteEntry(); refreshSession(); window.addEventListener('keydown', focusSearch) })
onBeforeUnmount(() => window.removeEventListener('keydown', focusSearch))
// 旧入口 /?create=post|topic 统一进入创作选择（普通话题已下线）
watch(() => route.query.create, (value) => { if (value === 'post' || value === 'topic') { composerOpen.value = true; router.replace({ path: route.path, query: { ...route.query, create: undefined } }) } }, { immediate: true })
const choose = (path) => { composerOpen.value = false; router.push(path) }
</script>

<template>
  <div class="app-shell">
    <div class="ambient ambient-a"></div><div class="ambient ambient-b"></div>
    <header v-if="!route.meta.bare" class="site-header">
      <RouterLink class="brand" to="/" aria-label="FINNS 首页"><span class="brand-mark"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20.24 12.24a6 6 0 0 0-8.49-8.49L5 10.5V19h8.5z"/><path d="M16 8 2 22"/><path d="M17.5 15H9"/></svg></span><span>FINNS</span></RouterLink>
      <nav class="main-nav" aria-label="主导航">
        <RouterLink :class="{ active: activePath === '/' }" to="/">发现</RouterLink>
        <RouterLink :class="{ active: activePath === '/collections' }" to="/collections">专题</RouterLink>
      </nav>
      <form class="header-search" role="search" @submit.prevent="searchSoon">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
        <input ref="searchInput" type="search" placeholder="搜索文章、专题或内容…" aria-label="搜索文章、专题或内容" autocomplete="off" />
        <kbd aria-hidden="true">Ctrl K</kbd>
      </form>
      <div class="header-actions">
        <button class="bell-btn" type="button" aria-label="消息（暂未开放）" title="消息" @click="showToast('消息功能暂未开放')"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/></svg></button>
        <button v-if="!user" class="btn btn-ghost" @click="openAuth()">登录</button>
        <template v-else>
          <RouterLink class="btn btn-ghost user-button" to="/me">个人中心</RouterLink>
          <RouterLink v-if="can('admin.users')" class="btn btn-ghost manage-button" :class="{ active: activePath.startsWith('/admin') }" to="/admin/users">管理</RouterLink>
          <button class="btn btn-ghost logout-button" :disabled="logoutBusy" @click="logout">退出</button>
        </template>
        <button v-if="!user || can('post.create')" class="btn btn-primary create-button" @click="showWrite"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20.24 12.24a6 6 0 0 0-8.49-8.49L5 10.5V19h8.5z"/><path d="M16 8 2 22"/><path d="M17.5 15H9"/></svg>开始创作</button>
      </div>
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

    <div v-if="composerOpen" class="overlay" @click.self="composerOpen = false"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="create-title"><button class="modal-close" aria-label="关闭" @click="composerOpen = false">×</button><span class="kicker">CREATE</span><h2 id="create-title">开始创作</h2><div class="ct-create-choices"><button v-if="can('post.create')" type="button" class="ct-create-choice" @click="choose('/me/posts/new')"><strong>写文章</strong><small>先存为私人草稿，准备好再发布。</small></button><button v-if="can('collection.manage')" type="button" class="ct-create-choice" @click="choose('/me/collections/new')"><strong>创建专题</strong><small>用模板或现成内容整理专题。</small></button><button v-if="can('ai.use')" type="button" class="ct-create-choice" @click="choose('/me/collections/ai/new')"><strong>AI 创建专题</strong><small>让 AI 协助起草专题结构。</small></button></div></section></div>
    <div v-if="toastMessage" class="toast show" role="status">{{ toastMessage }}</div>
  </div>
</template>
