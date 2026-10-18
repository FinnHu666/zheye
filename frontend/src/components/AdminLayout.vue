<script setup>
import AdminNav from './AdminNav.vue'
defineProps({ title: String, description: String, kicker: { type: String, default: 'COMMUNITY · MANAGEMENT' } })
</script>
<template>
  <section class="section personal-section admin-section admin-layout">
    <AdminNav />
    <header class="admin-page-heading"><div><span class="kicker">{{ kicker }}</span><h1>{{ title }}</h1><p>{{ description }}</p></div></header>
    <slot />
  </section>
</template>
