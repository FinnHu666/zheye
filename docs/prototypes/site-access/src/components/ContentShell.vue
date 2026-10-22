<script setup>
// 原型：与真实 PersonalLayout 同一套版式（个人中心导航 + 标题块），编辑/预览页可加宽以放下“三个版本”侧栏。
import PersonalNav from '@finns/components/PersonalNav.vue'
defineProps({ kicker: String, title: String, desc: String, wide: Boolean })
</script>
<template>
  <section class="section personal-section personal-shell">
    <div class="personal-container" :class="{ 'ct-wide': wide }">
      <PersonalNav />
      <header class="personal-head">
        <div class="personal-head-text"><span class="kicker">{{ kicker }}</span><h1>{{ title }}</h1><p>{{ desc }}</p></div>
        <div v-if="$slots.actions" class="personal-head-actions"><slot name="actions" /></div>
      </header>
      <slot />
    </div>
  </section>
</template>
