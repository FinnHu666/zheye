<script setup>
// 个人中心各页共用的版式：左侧导航 + 面包屑 + 白色卡片（图标、标题、说明、操作）。切换页面时导航与卡片位置不变，只有内容变化。
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { can } from '../lib/session'
defineProps({ kicker: String, title: String, desc: String })
const route = useRoute()
const ICONS = {
  profile: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  security: '<path d="M12 3 4 6v6c0 5 3.4 8.2 8 9 4.6-.8 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
  ai: '<rect x="4" y="8" width="16" height="12" rx="3"/><path d="M12 4v4M9 13h.01M15 13h.01M9 17h6"/>',
  content: '<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h7M9 17h7"/>',
  bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>',
}
const items = computed(() => [
  { key: 'profile', label: '个人资料', to: '/me', active: route.path === '/me' || route.path === '/me/profile/edit' },
  { key: 'security', label: '账号安全', to: '/me/security', active: route.path.startsWith('/me/security') },
  ...(can('ai.use') ? [{ key: 'ai', label: 'AI 配置', to: '/me/ai-settings', active: route.path.startsWith('/me/ai-settings') }] : []),
  { key: 'content', label: '我的内容', to: '/me/posts', active: route.path === '/me/posts' || route.path.startsWith('/me/posts/') },
])
const current = computed(() => items.value.find((i) => i.active)?.key || 'content')
</script>
<template>
  <section class="pc-page">
    <aside class="pc-side" aria-label="个人中心导航">
      <strong class="pc-side-title">个人中心</strong>
      <nav class="pc-nav">
        <RouterLink v-for="item in items" :key="item.key" :to="item.to" :class="{ active: item.active }" :aria-current="item.active ? 'page' : undefined">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" v-html="ICONS[item.key]"></svg>{{ item.label }}
        </RouterLink>
        <span class="pc-soon" aria-disabled="true" title="暂未开放"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" v-html="ICONS.bell"></svg>通知设置<small>即将开放</small></span>
      </nav>
    </aside>
    <div class="pc-main">
      <nav class="pc-crumb" aria-label="面包屑"><RouterLink to="/">首页</RouterLink><span aria-hidden="true">›</span><RouterLink to="/me">个人中心</RouterLink><span aria-hidden="true">›</span><span aria-current="page">{{ title }}</span></nav>
      <div class="pc-card">
        <header class="pc-head">
          <span class="pc-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" v-html="ICONS[current]"></svg></span>
          <div class="pc-head-text"><h1>{{ title }}</h1><p>{{ desc }}</p></div>
          <div v-if="$slots.actions" class="pc-head-actions"><slot name="actions" /></div>
        </header>
        <slot />
      </div>
    </div>
  </section>
</template>
