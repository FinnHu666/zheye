// 原型入口：路由表与 frontend/src/main.js 一致，增加 0.7.0 的角色要求（meta.perm）和 /admin/* 页面。
// 页面组件直接来自 frontend/src；App/DiscoverPage/PersonalNav 由 vite.config.mjs 替换为 overlays/ 版本。
import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { installMockServer } from './mock/server'
import App from '@finns/App.vue'
import DiscoverPage from '@finns/pages/DiscoverPage.vue'
import CollectionsPage from '@finns/pages/CollectionsPage.vue'
import VpsPage from '@finns/pages/VpsPage.vue'
import LocalToolsPage from '@finns/pages/LocalToolsPage.vue'
import PersonalPage from '@finns/pages/PersonalPage.vue'
import AiSettingsPage from '@finns/pages/AiSettingsPage.vue'
import MyPostsPage from '@finns/pages/MyPostsPage.vue'
import MyCollectionsPage from '@finns/pages/MyCollectionsPage.vue'
import SelectedCollectionsPage from '@finns/pages/SelectedCollectionsPage.vue'
import CollectionDetailPage from '@finns/pages/CollectionDetailPage.vue'
import CollectionImportPage from '@finns/pages/CollectionImportPage.vue'
import CollectionNewPage from '@finns/pages/CollectionNewPage.vue'
import CollectionEditorPage from '@finns/pages/CollectionEditorPage.vue'
import TopicBuilderPage from '@finns/pages/TopicBuilderPage.vue'
import AdminUsersPage from './pages/AdminUsersPage.vue'
import AdminRolesPage from './pages/AdminRolesPage.vue'
import AdminAuditPage from './pages/AdminAuditPage.vue'
import { api } from '@finns/lib/api'
import { sessionUser } from './proto/session'
import '@finns/assets/styles.css'
import './proto.css'

installMockServer()

const own = { requiresAuth: true, perm: 'collection.manage' }
const admin = { requiresAuth: true, perm: 'admin.users' }
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: DiscoverPage },
    { path: '/collections', component: CollectionsPage },
    { path: '/collections/:id(\\d+)', component: CollectionDetailPage },
    { path: '/vps', component: VpsPage },
    { path: '/private/tools', component: LocalToolsPage },
    { path: '/me', component: PersonalPage, meta: { requiresAuth: true } },
    { path: '/me/ai-settings', component: AiSettingsPage, meta: { requiresAuth: true, perm: 'ai.use' } },
    { path: '/me/posts', component: MyPostsPage, meta: { requiresAuth: true } },
    { path: '/me/collections', component: MyCollectionsPage, meta: own },
    { path: '/me/collections/ai/new', component: TopicBuilderPage, meta: { ...own, perm: 'ai.use' } },
    { path: '/me/collections/:id(\\d+)/builder', component: TopicBuilderPage, meta: own },
    { path: '/me/collections/:id(\\d+)/builder/preview', component: TopicBuilderPage, meta: { ...own, builderPreview: true } },
    { path: '/me/collections/import', component: CollectionImportPage, meta: own },
    { path: '/me/collections/:id(\\d+)/preview', component: CollectionDetailPage, meta: { ...own, privatePreview: true } },
    { path: '/me/collections/new', component: CollectionNewPage, meta: own },
    { path: '/me/collections/:id(\\d+)/edit', component: CollectionEditorPage, meta: own },
    { path: '/my/collections', component: SelectedCollectionsPage, meta: { requiresAuth: true } },
    // 0.7.0 FINNS-11：管理页面（独立路由，可直达/刷新；后端 SpaController 需同步增加 /admin/** 回退）
    { path: '/admin', redirect: '/admin/users' },
    { path: '/admin/users', component: AdminUsersPage, meta: admin },
    { path: '/admin/roles', component: AdminRolesPage, meta: admin },
    { path: '/admin/audit', component: AdminAuditPage, meta: admin },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// 每次导航以服务端身份为准：角色被调整后，下一次导航立即按新角色展示。
router.beforeEach(async (to) => {
  try { sessionUser.value = await api('/api/auth/me') } catch { sessionUser.value = null }
  if (!to.matched.some((record) => record.meta.requiresAuth) || sessionUser.value) return true
  return { path: '/', query: { signin: '1', next: to.fullPath } }
})

document.title = `[原型] ${document.title}`
createApp(App).use(router).mount('#app')
