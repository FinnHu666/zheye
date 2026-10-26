<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../lib/api'
import CollectionReader from '../components/CollectionReader.vue'
const route=useRoute(),outline=ref(null),loading=ref(true),error=ref('')
const load=async()=>{ loading.value=true;error.value='';outline.value=null;document.title='FINNS';try{outline.value=await api(route.meta.privatePreview?`/api/me/collections/${route.params.id}/preview`:`/api/collections/${route.params.id}/outline`);document.title=(outline.value.metadata?.seoTitle||outline.value.collection.name)+' · FINNS'}catch(e){error.value=e.message}finally{loading.value=false} }
onMounted(load);watch(()=>route.fullPath,load);onUnmounted(()=>{document.title='FINNS'})
</script>
<template>
  <div v-if="loading" class="loading standalone-loading">正在加载专题…</div>
  <template v-else-if="outline"><p v-if="route.meta.privatePreview" class="preview-banner">私人阅读预览 · 仅自己可见 <RouterLink :to="`/me/collections/${route.params.id}/compose/content`">继续编辑 →</RouterLink></p><CollectionReader :outline="outline" /></template>
  <section v-else class="section"><div class="empty-state"><p>{{ error }}</p><RouterLink to="/collections">返回专题广场 →</RouterLink></div></section>
</template>
