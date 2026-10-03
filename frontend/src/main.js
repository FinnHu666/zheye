import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import DiscoverPage from './pages/DiscoverPage.vue'
import CollectionsPage from './pages/CollectionsPage.vue'
import VpsPage from './pages/VpsPage.vue'
import LocalToolsPage from './pages/LocalToolsPage.vue'
import PersonalPage from './pages/PersonalPage.vue'
import MyPostsPage from './pages/MyPostsPage.vue'
import MyCollectionsPage from './pages/MyCollectionsPage.vue'
import SelectedCollectionsPage from './pages/SelectedCollectionsPage.vue'
import CollectionDetailPage from './pages/CollectionDetailPage.vue'
import CollectionImportPage from './pages/CollectionImportPage.vue'
import CollectionNewPage from './pages/CollectionNewPage.vue'
import CollectionEditorPage from './pages/CollectionEditorPage.vue'
import { api } from './lib/api'
import './assets/styles.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: DiscoverPage },
    { path: '/collections', component: CollectionsPage },
    { path: '/collections/:id(\\d+)', component: CollectionDetailPage },
    { path: '/vps', component: VpsPage },
    { path: '/private/tools', component: LocalToolsPage },
    { path: '/me', component: PersonalPage, meta: { requiresAuth: true } },
    { path: '/me/posts', component: MyPostsPage, meta: { requiresAuth: true } },
    { path: '/me/collections', component: MyCollectionsPage, meta: { requiresAuth: true } },
    { path: '/me/collections/import', component: CollectionImportPage, meta: { requiresAuth: true } },
    { path: '/me/collections/:id(\\d+)/preview', component: CollectionDetailPage, meta: { requiresAuth: true, privatePreview: true } },
    { path: '/me/collections/new', component: CollectionNewPage, meta: { requiresAuth: true } },
    { path: '/me/collections/:id(\\d+)/edit', component: CollectionEditorPage, meta: { requiresAuth: true } },
    { path: '/my/collections', component: SelectedCollectionsPage, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (!to.matched.some((record) => record.meta.requiresAuth)) return true
  try { await api('/api/auth/me'); return true }
  catch { return { path: '/', query: { signin: '1', next: to.fullPath } } }
})

createApp(App).use(router).mount('#app')
