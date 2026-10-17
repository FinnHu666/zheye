import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import DiscoverPage from './pages/DiscoverPage.vue'
import CollectionsPage from './pages/CollectionsPage.vue'
import VpsPage from './pages/VpsPage.vue'
import LocalToolsPage from './pages/LocalToolsPage.vue'
import PersonalPage from './pages/PersonalPage.vue'
import AiSettingsPage from './pages/AiSettingsPage.vue'
import MyPostsPage from './pages/MyPostsPage.vue'
import MyCollectionsPage from './pages/MyCollectionsPage.vue'
import SelectedCollectionsPage from './pages/SelectedCollectionsPage.vue'
import CollectionDetailPage from './pages/CollectionDetailPage.vue'
import CollectionImportPage from './pages/CollectionImportPage.vue'
import CollectionNewPage from './pages/CollectionNewPage.vue'
import CollectionEditorPage from './pages/CollectionEditorPage.vue'
import TopicBuilderPage from './pages/TopicBuilderPage.vue'
import AdminUsersPage from './pages/admin/AdminUsersPage.vue'
import AdminRolesPage from './pages/admin/AdminRolesPage.vue'
import AdminAuditPage from './pages/admin/AdminAuditPage.vue'
import { refreshSession } from './lib/session'
import './assets/styles.css'

// meta.perm：页面所需权限（FINNS-11）。无权限时 App 保留地址显示 403；接口另由服务端检查。
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
    { path: '/me/collections/:id(\\d+)/builder', component: TopicBuilderPage, meta: { ...own, perm: 'ai.use' } },
    { path: '/me/collections/:id(\\d+)/builder/preview', component: TopicBuilderPage, meta: { ...own, perm: 'ai.use', builderPreview: true } },
    { path: '/me/collections/import', component: CollectionImportPage, meta: own },
    { path: '/me/collections/:id(\\d+)/preview', component: CollectionDetailPage, meta: { ...own, privatePreview: true } },
    { path: '/me/collections/new', component: CollectionNewPage, meta: own },
    { path: '/me/collections/:id(\\d+)/edit', component: CollectionEditorPage, meta: own },
    { path: '/my/collections', component: SelectedCollectionsPage, meta: { requiresAuth: true, perm: 'selection.manage' } },
    { path: '/admin', redirect: '/admin/users' },
    { path: '/admin/users', component: AdminUsersPage, meta: admin },
    { path: '/admin/roles', component: AdminRolesPage, meta: admin },
    { path: '/admin/audit', component: AdminAuditPage, meta: { requiresAuth: true, perm: 'admin.audit' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// 每次导航以服务端身份为准：角色或权限被调整后，下一次导航即按新权限展示。
router.beforeEach(async (to) => {
  const user = await refreshSession()
  if (!to.matched.some((record) => record.meta.requiresAuth) || user) return true
  return { path: '/', query: { signin: '1', next: to.fullPath } }
})

createApp(App).use(router).mount('#app')
