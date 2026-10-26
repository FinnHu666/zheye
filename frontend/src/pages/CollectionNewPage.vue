<script setup>
// 创建专题：目前只支持“直接写 / 用模板”（AI 创建在“我的内容”里的另一入口）；起点用下拉框选择。
import { onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { api } from '../lib/api'
const router = useRouter()
const busy = ref(false), loading = ref(true), error = ref(''), templates = ref([])
const templateKey = ref('BLANK'), name = ref(''), created = ref(false)
const loadTemplates = async () => {
  loading.value = true; error.value = ''
  try { templates.value = (await api('/api/me/collection-templates')).sort((a, b) => Number(b.key === 'BLANK') - Number(a.key === 'BLANK')) } catch (e) { error.value = e.message } finally { loading.value = false }
}
onMounted(loadTemplates)
const create = async () => {
  if (busy.value) return
  if (!name.value.trim()) { error.value = '请填写专题名称。'; return }
  busy.value = true; error.value = ''
  try {
    const draft = await api('/api/me/collections/drafts', { method: 'POST', body: JSON.stringify({ name: name.value.trim(), description: '', format: 'CUSTOM', templateKey: templateKey.value, initialContent: '' }) })
    created.value = true; await router.push(`/me/collections/${draft.id}/edit`)
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
onBeforeRouteLeave(() => created.value || !name.value || window.confirm('还没有创建草稿，离开会清除填写内容。确定离开吗？'))
</script>
<template>
  <section class="collection-create-page">
    <header class="collection-compact-heading"><RouterLink class="text-link" to="/me/posts?type=COLLECTION">← 我的专题</RouterLink><h1>创建专题</h1></header>
    <form class="quick-create-form" @submit.prevent="create">
      <fieldset :disabled="busy">
        <label class="create-name">专题名称<input v-model="name" required maxlength="80" autocomplete="off" /></label>
        <label class="create-template">起点（可选）<select v-model="templateKey" :disabled="loading || !templates.length"><option v-for="t in templates" :key="t.key" :value="t.key">{{ t.title }}</option></select></label>
      </fieldset>
      <p v-if="error" class="form-error" role="alert">{{ error }} <button v-if="!templates.length && !loading" class="text-link" type="button" @click="loadTemplates">重新加载</button></p>
      <div class="create-submit-row"><span></span><button class="btn btn-primary" :disabled="busy || loading || !templates.length || !name.trim()">{{ busy ? '正在创建…' : '创建并继续编辑 →' }}</button></div>
    </form>
  </section>
</template>
