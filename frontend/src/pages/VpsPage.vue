<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../lib/api'
const items = ref([]); const loading = ref(true)
onMounted(async () => { try { items.value = await api('/api/vps') } finally { loading.value = false } })
</script>
<template><section class="page-hero page-hero-dark"><span class="kicker light">VPS FIELD NOTES</span><h1>来自真实使用者的<br /><em>云端选择。</em></h1><p>透明的配置、价格与体验记录，让每一次部署都有据可循。</p></section><section class="section vps-page-section"><div class="section-heading"><div><span class="kicker">COMMUNITY REVIEWS</span><h2>推荐清单</h2></div><span class="page-note">按社区评分排序</span></div><div class="vps-list"><div v-if="loading" class="loading">正在加载推荐…</div><article v-for="item in items" v-else :key="item.id" class="vps-card"><div><h3>{{ item.provider }} · {{ item.planName }} <span class="score">★ {{ item.score.toFixed(1) }}</span></h3><p>{{ item.cpu }} vCPU · {{ item.memoryGb }} GB · {{ item.storageGb }} GB · {{ item.region }}</p><p>{{ item.description }}</p></div><div class="vps-price"><strong>${{ item.monthlyPrice }}</strong><span>每月</span></div></article></div></section><section class="page-cta"><h2>分享你的部署体验</h2><p>真实的延迟、稳定性和价格记录，比广告更有参考价值。</p><RouterLink class="btn btn-primary" to="/?create=vps">提交一条推荐 <span>→</span></RouterLink></section></template>
