<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { can, currentUser } from '../lib/session'

// 用户管理与内容管理是两类工作：一级切换分区，二级只列当前分区的页面。
const route = useRoute()
const isOwner = computed(() => currentUser.value?.role?.code === 'OWNER')
const groups = computed(() => [
  { key: 'users', label: '用户管理', items: [
    { to: '/admin/users', label: '用户与角色', show: can('admin.users') },
    { to: '/admin/roles', label: '角色权限', show: can('admin.users') },
    { to: '/admin/audit', label: '操作记录', show: can('admin.audit') }
  ] },
  { key: 'content', label: '内容管理', items: [
    { to: '/admin/reviews', label: '内容审批', show: can('content.review') },
    { to: '/admin/sensitive-rules', label: '敏感规则', show: isOwner.value }
  ] }
].map((group) => ({ ...group, items: group.items.filter((item) => item.show) })).filter((group) => group.items.length))
const activeKey = computed(() => (route.path.startsWith('/admin/reviews') || route.path.startsWith('/admin/sensitive-rules')) ? 'content' : 'users')
const current = computed(() => groups.value.find((group) => group.key === activeKey.value) || groups.value[0])
</script>
<template>
  <nav v-if="groups.length > 1" class="personal-nav nav-primary" aria-label="管理分区">
    <RouterLink v-for="group in groups" :key="group.key" :to="group.items[0].to" custom v-slot="{ href, navigate }">
      <a :href="href" :class="{ active: group.key === current?.key }" :aria-current="group.key === current?.key ? 'page' : undefined" @click="navigate">{{ group.label }}</a>
    </RouterLink>
  </nav>
  <nav v-if="current" class="personal-nav nav-sub" :aria-label="current.label + '页面'">
    <RouterLink v-for="item in current.items" :key="item.to" :to="item.to" active-class="active">{{ item.label }}</RouterLink>
  </nav>
</template>
