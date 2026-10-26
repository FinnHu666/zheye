import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import DiscoverPage from './pages/DiscoverPage.vue'
import CollectionsPage from './pages/CollectionsPage.vue'
import VpsPage from './pages/VpsPage.vue'
import LocalToolsPage from './pages/LocalToolsPage.vue'
import PersonalPage from './pages/PersonalPage.vue'
import AccountFlowPage from './pages/AccountFlowPage.vue'
import AccountSecurityPage from './pages/AccountSecurityPage.vue'
import AiSettingsPage from './pages/AiSettingsPage.vue'
import MyContentPage from './pages/MyContentPage.vue'
import ArticleEditorPage from './pages/ArticleEditorPage.vue'
import ArticlePreviewPage from './pages/ArticlePreviewPage.vue'
import CollectionDetailPage from './pages/CollectionDetailPage.vue'
import CollectionImportPage from './pages/CollectionImportPage.vue'
import CollectionNewPage from './pages/CollectionNewPage.vue'
import CollectionWizardPage from './pages/CollectionWizardPage.vue'
import CollectionEditorPage from './pages/CollectionEditorPage.vue'
import TopicBuilderPage from './pages/TopicBuilderPage.vue'
import AdminUsersPage from './pages/admin/AdminUsersPage.vue'
import AdminRolesPage from './pages/admin/AdminRolesPage.vue'
import AdminAuditPage from './pages/admin/AdminAuditPage.vue'
import AdminReviewsPage from './pages/admin/AdminReviewsPage.vue'
import AdminReviewDetailPage from './pages/admin/AdminReviewDetailPage.vue'
import AdminRulesPage from './pages/admin/AdminRulesPage.vue'
import { refreshSession } from './lib/session'
import './assets/styles.css'
import './assets/account.css'
import './assets/admin.css'
import './assets/review.css'
import './assets/content.css'
import './assets/collection-wizard.css'

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
    ...['login', 'register', 'forgot', 'pending', 'verify', 'reset', 'setup'].map((mode) => ({ path: '/auth/' + mode, component: AccountFlowPage, meta: { accountMode: mode } })),
    { path: '/me', component: PersonalPage, meta: { requiresAuth: true } },
    { path: '/me/profile/edit', component: PersonalPage, meta: { requiresAuth: true, profileEdit: true } },
    { path: '/me/security', component: AccountSecurityPage, meta: { requiresAuth: true } },
    { path: '/me/ai-settings', component: AiSettingsPage, meta: { requiresAuth: true, perm: 'ai.use' } },
    { path: '/me/posts', component: MyContentPage, meta: { requiresAuth: true } },
    { path: '/me/posts/new', component: ArticleEditorPage, meta: { requiresAuth: true, perm: 'post.create' } },
    { path: '/me/posts/:id(\\d+)/edit', component: ArticleEditorPage, meta: { requiresAuth: true, perm: 'post.create' } },
    { path: '/me/posts/:id(\\d+)/preview', component: ArticlePreviewPage, meta: { requiresAuth: true } },
    { path: '/me/collections', redirect: { path: '/me/posts', query: { type: 'COLLECTION' } } },
    { path: '/me/collections/ai/new', component: TopicBuilderPage, meta: { ...own, perm: 'ai.use' } },
    { path: '/me/collections/:id(\\d+)/builder', component: TopicBuilderPage, meta: { ...own, perm: 'ai.use' } },
    { path: '/me/collections/:id(\\d+)/builder/preview', component: TopicBuilderPage, meta: { ...own, perm: 'ai.use', builderPreview: true } },
    { path: '/me/collections/import', component: CollectionImportPage, meta: own },
    { path: '/me/collections/:id(\\d+)/preview', component: CollectionDetailPage, meta: { ...own, privatePreview: true } },
    { path: '/me/collections/new', component: CollectionWizardPage, meta: { ...own, bare: true } },
    { path: '/me/collections/:id(\\d+)/compose/:step(basic|content|publish)', component: CollectionWizardPage, meta: { ...own, bare: true } },
    { path: '/me/collections/quick', component: CollectionNewPage, meta: own },
    { path: '/me/collections/:id(\\d+)/edit', redirect: to => `/me/collections/${to.params.id}/compose/content` },
    { path: '/my/collections', redirect: { path: '/me/posts', query: { type: 'SELECTED' } } },
    { path: '/admin', redirect: '/admin/users' },
    { path: '/admin/users', component: AdminUsersPage, meta: admin },
    { path: '/admin/roles', component: AdminRolesPage, meta: admin },
    { path: '/admin/reviews', component: AdminReviewsPage, meta: { requiresAuth: true, perm: 'content.review' } },
    { path: '/admin/reviews/tasks/:id(\\d+)', component: AdminReviewDetailPage, meta: { requiresAuth: true, perm: 'content.review', reviewMode: 'TASK' } },
    { path: '/admin/reviews/publications/:type(ARTICLE|COMMENT|COLLECTION)/:id(\\d+)', component: AdminReviewDetailPage, meta: { requiresAuth: true, perm: 'content.review', reviewMode: 'PUBLICATION' } },
    { path: '/admin/sensitive-rules', component: AdminRulesPage, meta: { requiresAuth: true, ownerOnly: true } },
    { path: '/me/reviews', redirect: '/me/posts' },
    { path: '/admin/audit', component: AdminAuditPage, meta: { requiresAuth: true, perm: 'admin.audit' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

// 每次导航以服务端身份为准：角色或权限被调整后，下一次导航即按新权限展示。
router.beforeEach(async (to) => {
  const user = await refreshSession()
  // 旧的弹窗登录链接 /?signin=1&next=… 改为独立登录路由
  if (to.path === '/' && to.query.signin === '1') return { path: '/auth/login', query: typeof to.query.next === 'string' ? { next: to.query.next } : {} }
  if (!to.matched.some((record) => record.meta.requiresAuth) || user) return true
  return { path: '/auth/login', query: { next: to.fullPath } }
})

createApp(App).use(router).mount('#app')
