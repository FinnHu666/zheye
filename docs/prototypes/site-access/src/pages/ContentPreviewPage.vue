<script setup>
// FINNS-18 R1：私人预览（读取已保存的工作稿）。与“读者现在看到”并排切换，说明二者可能不同。
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api } from '@finns/lib/api'
import VditorView from '../components/VditorView.vue'
import ContentShell from '../components/ContentShell.vue'
const route = useRoute(), doc = ref(null), loading = ref(true), error = ref(''), mode = ref('draft'), reader = ref(null), readerError = ref('')
const label = computed(() => '文章')
async function load() {
  loading.value = true; error.value = ''
  try { doc.value = await api('/api/content/mine/' + route.params.id) } catch (e) { error.value = e.status === 401 ? '登录已失效，请重新登录。' : e.message; doc.value = null; loading.value = false; return }
  reader.value = null; readerError.value = ''
  try { reader.value = await api('/api/content/public/' + route.params.id) } catch (e) { readerError.value = '读者现在看不到这条内容（未公开、已撤回或已下架）。' }
  loading.value = false
}
onMounted(() => { load(); window.addEventListener('finns-content-scene', load); window.addEventListener('finns-account-session', load) })
onBeforeUnmount(() => { window.removeEventListener('finns-content-scene', load); window.removeEventListener('finns-account-session', load) })
</script>
<template>
  <ContentShell kicker="MY CONTENT" title="私人预览" desc="预览读取已保存的工作稿，与发布后的阅读使用同一套安全渲染。">
    <template #actions><RouterLink class="btn btn-ghost" to="/me/posts">← 返回列表</RouterLink><RouterLink v-if="doc" class="btn btn-outline" :to="`/me/posts/${doc.id}/edit`">返回编辑</RouterLink></template>
    <p v-if="error" class="rv-notice danger" role="alert">{{ error }}</p>
    <div v-if="loading" class="rv-empty" role="status"><span class="rv-spinner"></span><h2>正在读取预览</h2></div>
    <template v-else-if="doc">
      <div class="ct-preview-bar" role="tablist" aria-label="预览视角">
        <button role="tab" :aria-selected="mode === 'draft'" :class="{ on: mode === 'draft' }" @click="mode = 'draft'">私人预览 · 工作稿 v{{ doc.draft.version }}</button>
        <button role="tab" :aria-selected="mode === 'reader'" :class="{ on: mode === 'reader' }" @click="mode = 'reader'">读者现在看到</button>
      </div>
      <article v-if="mode === 'draft'" class="rr-content ct-article">
        <div class="rr-content-label"><span>{{ label }} · 仅你自己可见</span><small>这是已保存的工作稿，尚未公开；预览与发布后的阅读使用同一套安全渲染。</small></div>
        <h1 class="ct-title">{{ doc.draft.title || '（未填写标题）' }}</h1>
        <p v-if="doc.draft.summary" class="rr-abstract">{{ doc.draft.summary }}</p>
        <VditorView :source="doc.draft.body" />
      </article>
      <article v-else class="rr-content ct-article">
        <template v-if="reader">
          <div class="rr-content-label"><span>{{ label }} · 公开版 v{{ reader.version }}</span><small>这是读者现在看到的内容，与工作稿可能不同。</small></div>
          <h1 class="ct-title">{{ reader.title }}</h1><p v-if="reader.summary" class="rr-abstract">{{ reader.summary }}</p><VditorView :source="reader.body" />
        </template>
        <div v-else class="rv-empty"><h2>读者看不到这条内容</h2><p>{{ readerError }}</p></div>
      </article>
    </template>
  </ContentShell>
</template>
