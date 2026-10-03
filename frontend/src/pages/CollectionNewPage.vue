<script setup>
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { api } from '../lib/api'
const router = useRouter()
const busy = ref(false), loading = ref(true), error = ref(''), templates = ref([])
const templateKey = ref('BLANK'), name = ref(''), initialContent = ref(''), created = ref(false)
const selected = computed(() => templates.value.find(t => t.key === templateKey.value))
const hints = { BLANK: '从一段内容开始', RENTING: '预算、看房与入住', TUTORIAL: '准备、步骤与常见问题', EXERCISE: '目标、安排与复盘', RETROSPECTIVE: '做法、结果与改进' }
const loadTemplates = async () => {
  loading.value = true; error.value = ''
  try { templates.value = (await api('/api/me/collection-templates')).sort((a, b) => Number(b.key === 'BLANK') - Number(a.key === 'BLANK')) } catch (e) { error.value = e.message } finally { loading.value = false }
}
onMounted(loadTemplates)
const create = async () => {
  if (busy.value) return
  if (!name.value.trim()) { error.value = '先给专题起个名字。'; return }
  busy.value = true; error.value = ''
  try {
    const draft = await api('/api/me/collections/drafts', { method: 'POST', body: JSON.stringify({ name: name.value.trim(), description: '', format: 'CUSTOM', templateKey: templateKey.value, initialContent: initialContent.value }) })
    created.value = true; await router.push(`/me/collections/${draft.id}/edit`)
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
onBeforeRouteLeave(() => created.value || (!name.value && !initialContent.value) || window.confirm('还没有创建草稿，离开会清除填写内容。确定离开吗？'))
</script>
<template>
  <section class="collection-create-page">
    <header class="collection-compact-heading"><RouterLink class="text-link" to="/me/collections">← 我的专题</RouterLink><h1>创建专题</h1><p>起个名字，写下经验。内容会先保存为私人草稿。</p></header>
    <nav class="creation-methods" aria-label="专题创建方式"><span aria-current="page">直接写 / 用模板</span><RouterLink to="/me/collections/import">导入现成内容 →</RouterLink><RouterLink to="/me/collections">复用我的专题 →</RouterLink></nav>
    <form class="quick-create-form" @submit.prevent="create">
      <fieldset :disabled="busy">
        <label class="create-name">专题名称<input v-model="name" required maxlength="80" placeholder="例如：第一次独立租房经验" autocomplete="off" /></label>
        <div class="create-template-heading"><h2>选择起点</h2><span>所有章节都能修改</span></div>
        <p v-if="loading" role="status">正在加载模板…</p>
        <div class="quick-template-grid"><label v-for="item in templates" :key="item.key" class="quick-template-card" :class="{ selected: templateKey === item.key }"><input v-model="templateKey" type="radio" :value="item.key" name="template" /><strong>{{ item.title }}</strong><small>{{ hints[item.key] }}</small></label></div>
        <details v-if="selected" class="template-preview"><summary>查看「{{ selected.title }}」的章节安排</summary><div class="chapter-chips"><span v-for="title in selected.sections" :key="title">{{ title }}</span></div><p>{{ selected.example }}</p></details>
        <label>先写一段内容 <span class="field-optional">（可选，也可以创建后再写）</span><textarea v-model="initialContent" maxlength="4000" rows="4" placeholder="你想记录什么？直接写正文，不必先设计目录或小标题。" /><small class="field-counter">{{ initialContent.length }} / 4000</small></label>
      </fieldset>
      <p v-if="error" class="form-error" role="alert">{{ error }} <button v-if="!templates.length && !loading" class="text-link" type="button" @click="loadTemplates">重新加载模板</button></p>
      <div class="create-submit-row"><p>仅自己可见。公开分享可以稍后决定。</p><button class="btn btn-primary" :disabled="busy || loading || !templates.length || !name.trim()">{{ busy ? '正在创建…' : '创建并继续编辑 →' }}</button></div>
    </form>
  </section>
</template>
