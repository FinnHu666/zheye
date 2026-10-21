<script setup>
import { RouterLink } from 'vue-router'
import { can, sessionUser } from '../proto/session'
</script>
<template>
  <nav class="personal-nav" aria-label="管理导航">
    <RouterLink v-if="can('admin.users')" to="/admin/users" active-class="active">用户与角色</RouterLink>
    <RouterLink v-if="can('admin.roles')" to="/admin/roles" active-class="active">角色权限</RouterLink>
    <RouterLink v-if="can('admin.audit')" to="/admin/audit" active-class="active">操作记录</RouterLink>
    <RouterLink v-if="can('content.review')" to="/admin/reviews" active-class="active">内容审批</RouterLink>
    <RouterLink v-if="sessionUser?.role === 'OWNER'" to="/admin/sensitive-rules" active-class="active">敏感规则</RouterLink>
  </nav>
</template>
