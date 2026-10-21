<script setup>
import { ref } from 'vue'
import { api } from '../lib/api'
import { useSessionGuard } from '../lib/sessionGuard'
import ReauthPanel from './ReauthPanel.vue'
import { submitNotice, outcomeNotice } from '../lib/reviewText'
const guard = useSessionGuard()
const emit = defineEmits(['done', 'error', 'notice', 'collection', 'aiCollection'])
const newKey = () => (globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`)
let draftKey = newKey()
const type = ref('ARTICLE')
const form = ref({ title: '', summary: '', body: '' })
const busy = ref(false)
const outcome = ref('') // 送审/自动不通过的提示：留在表单里，直到再次提交；详细流转在“我的发布与流转”
const submit = async () => {
  if (busy.value || !(await guard.beforeSubmit())) return
  busy.value = true; outcome.value = ''
  try {
    const result = await api('/api/posts', { method: 'POST', body: JSON.stringify({ ...form.value, type: type.value, draftKey, requestId: newKey() }) })
    form.value = { title: '', summary: '', body: '' }; draftKey = newKey()
    if (result?.outcome === 'PENDING' || result?.outcome === 'AUTO_REJECTED') { outcome.value = outcomeNotice(result); emit('notice', outcome.value) } else emit('done')
  } catch (error) { if (!guard.handle(error)) emit('error', submitNotice(error)) } finally { busy.value = false }
}
</script>
<template><div class="composer-collection-entry"><div><strong>想把经验整理成专题？</strong><p>用模板或现成内容创建，可先保存再发布。</p></div><button type="button" class="btn btn-primary" @click="emit('aiCollection')">AI 创建专题 →</button><button type="button" class="btn btn-outline" @click="emit('collection')">创建专题 →</button></div><form class="composer-form" @submit.prevent="submit"><div class="segmented"><button type="button" :class="{ selected: type === 'ARTICLE' }" @click="type = 'ARTICLE'">文章</button><button type="button" :class="{ selected: type === 'TOPIC' }" @click="type = 'TOPIC'">话题</button></div><label>标题<input v-model="form.title" required maxlength="120" placeholder="一个清晰、有吸引力的标题" /></label><label>摘要<textarea v-model="form.summary" required maxlength="280" rows="2" placeholder="用一两句话说明这篇内容"></textarea></label><label>正文<textarea v-model="form.body" required maxlength="20000" rows="8" placeholder="支持 Markdown，从这里开始…"></textarea></label><p v-if="outcome" class="form-error composer-blocked" role="status">{{ outcome }} 可在「个人中心 › 我的发布与流转」查看进度。</p><button class="btn btn-primary btn-block" :disabled="busy || guard.expired.value">{{ busy ? '发布中…' : '发布内容' }}</button></form><ReauthPanel :guard="guard" /></template>
