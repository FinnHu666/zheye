<!-- 原型覆盖：frontend/src/components/PersonalNav.vue（0.7.0 按角色隐藏无权限入口）。结构与真实站点一致：我的内容是唯一入口，专题与自选专题在其页签内。 -->
<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { can } from '../proto/session'
const route = useRoute()
const creating = computed(() => route.path.startsWith('/me/posts'))
</script>
<template>
  <nav class="personal-nav nav-primary" aria-label="个人中心导航">
    <RouterLink to="/me" exact-active-class="active">个人资料</RouterLink>
    <RouterLink to="/me/security" active-class="active">账号安全</RouterLink>
    <RouterLink v-if="can('ai.use')" to="/me/ai-settings" active-class="active">AI 配置</RouterLink>
    <RouterLink to="/me/posts" custom v-slot="{ href, navigate }"><a :href="href" :class="{ active: creating }" :aria-current="creating ? 'page' : undefined" @click="navigate">我的内容</a></RouterLink>
  </nav>
</template>
