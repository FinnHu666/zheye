<!-- 原型覆盖：frontend/src/components/PersonalNav.vue（0.7.0 按角色隐藏无权限入口） -->
<script setup>
import { RouterLink } from 'vue-router'
import { can } from '../proto/session'
</script>
<template>
  <nav class="personal-nav" aria-label="个人中心导航">
    <RouterLink to="/me" exact-active-class="active">个人资料</RouterLink>
    <RouterLink v-if="can('ai.use')" to="/me/ai-settings" active-class="active">AI 配置</RouterLink>
    <RouterLink to="/me/posts" active-class="active">我的内容</RouterLink>
    <RouterLink v-if="can('collection.manage')" to="/me/collections" active-class="active">我创建的专题</RouterLink>
    <RouterLink to="/my/collections" active-class="active">自选专题</RouterLink>
  </nav>
</template>
