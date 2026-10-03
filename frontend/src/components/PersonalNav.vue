<script setup>
import { RouterLink } from 'vue-router'
</script>
<template>
  <nav class="personal-nav" aria-label="个人中心导航">
    <RouterLink to="/me" exact-active-class="active">个人资料</RouterLink>
    <RouterLink to="/me/posts" active-class="active">我的内容</RouterLink>
    <RouterLink to="/me/collections" active-class="active">我创建的专题</RouterLink>
    <RouterLink to="/my/collections" active-class="active">自选专题</RouterLink>
  </nav>
</template>
