<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { can } from '../lib/session'

// “我的内容”一个入口：文章、创建的专题、自选专题及各自的审核流转都在同一页。
const route = useRoute()
const creating = computed(() => route.path === '/me/posts')
</script>
<template>
  <nav class="personal-nav nav-primary" aria-label="个人中心导航">
    <RouterLink to="/me" exact-active-class="active">个人资料</RouterLink>
    <RouterLink to="/me/security" active-class="active">账号安全</RouterLink>
    <RouterLink v-if="can('ai.use')" to="/me/ai-settings" active-class="active">AI 配置</RouterLink>
    <RouterLink to="/me/posts" custom v-slot="{ href, navigate }"><a :href="href" :class="{ active: creating }" :aria-current="creating ? 'page' : undefined" @click="navigate">我的内容</a></RouterLink>
  </nav>
</template>
