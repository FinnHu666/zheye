<script setup>
// FINNS-18：私人预览（读取已保存的工作稿）；可切换到“读者现在看到”，说明二者可能不同。与公开阅读共用 MarkdownView（Vditor 渲染）。
import { ref, computed, onMounted } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PersonalLayout from '../components/PersonalLayout.vue'
import MarkdownView from '../components/MarkdownView.vue'
import { articles, errorText } from '../lib/articles'

const route = useRoute()
const doc = ref(null), loading = ref(true), error = ref(''), mode = ref('draft')
const reader = computed(() => doc.value?.published || null)

async function load() {
  loading.value = true; error.value = ''
  try { doc.value = await articles.get(route.params.id) }
  catch (e) { doc.value = null; error.value = e.status === 404 ? '没有找到这篇文章，可能已被删除。' : errorText(e) }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <PersonalLayout kicker="MY CONTENT" title="私人预览" desc="预览读取已保存的工作稿，与发布后的阅读使用同一套安全渲染。">
    <template #actions><div class="heading-actions"><RouterLink class="btn btn-ghost" to="/me/posts">← 返回列表</RouterLink><RouterLink v-if="doc" class="btn btn-outline" :to="`/me/posts/${doc.id}/edit`">返回编辑</RouterLink></div></template>
    <div v-if="loading" class="rv-empty" role="status"><span class="rv-spinner"></span><h2>正在读取预览</h2></div>
    <p v-else-if="error" class="rv-notice danger" role="alert">{{ error }} <button type="button" class="text-link" @click="load">重试</button></p>
    <template v-else-if="doc">
      <div class="ct-preview-bar" role="tablist" aria-label="预览视角">
        <button role="tab" :aria-selected="mode === 'draft'" :class="{ on: mode === 'draft' }" @click="mode = 'draft'">私人预览 · 工作稿 v{{ doc.version }}</button>
        <button role="tab" :aria-selected="mode === 'reader'" :class="{ on: mode === 'reader' }" @click="mode = 'reader'">读者现在看到</button>
      </div>
      <article v-if="mode === 'draft'" class="rr-content ct-article">
        <div class="rr-content-label"><span>文章 · 仅你自己可见</span><small>这是已保存的工作稿，尚未公开；预览与发布后的阅读使用同一套安全渲染。</small></div>
        <h1 class="ct-title">{{ doc.title || '（未填写标题）' }}</h1>
        <p v-if="doc.summary" class="rr-abstract">{{ doc.summary }}</p>
        <MarkdownView :source="doc.body" />
      </article>
      <article v-else class="rr-content ct-article">
        <template v-if="reader">
          <div class="rr-content-label"><span>文章 · 公开版 v{{ reader.version }}</span><small>这是读者现在看到的内容，与工作稿可能不同。</small></div>
          <h1 class="ct-title">{{ reader.title }}</h1><p v-if="reader.summary" class="rr-abstract">{{ reader.summary }}</p><MarkdownView :source="reader.body" />
        </template>
        <div v-else class="rv-empty"><h2>读者看不到这篇文章</h2><p>未公开、已撤回或已被下架。</p></div>
      </article>
    </template>
  </PersonalLayout>
</template>
