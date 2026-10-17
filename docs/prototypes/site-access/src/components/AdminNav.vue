<script setup>
import { RouterLink } from 'vue-router'
</script>
<template>
  <nav class="personal-nav" aria-label="管理导航">
    <RouterLink to="/admin/users" active-class="active">用户与角色</RouterLink>
    <RouterLink to="/admin/roles" active-class="active">角色权限</RouterLink>
    <RouterLink to="/admin/audit" active-class="active">操作记录</RouterLink>
  </nav>
</template>
