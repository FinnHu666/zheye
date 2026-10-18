<script setup>
// 个人中心各页共用的版式：导航位置、容器宽度、标题块高度全部一致，切换页签时只有内容区变化，不会上下/左右跳动。
import PersonalNav from './PersonalNav.vue'
defineProps({ kicker: String, title: String, desc: String })
</script>
<template>
  <section class="section personal-section personal-shell">
    <div class="personal-container">
      <PersonalNav />
      <header class="personal-head">
        <div class="personal-head-text"><span class="kicker">{{ kicker }}</span><h1>{{ title }}</h1><p>{{ desc }}</p></div>
        <div v-if="$slots.actions" class="personal-head-actions"><slot name="actions" /></div>
      </header>
      <slot />
    </div>
  </section>
</template>
