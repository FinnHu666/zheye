<script setup>
import { ref } from 'vue'
import { api } from '../lib/api'
const emit = defineEmits(['done', 'error', 'collection'])
const type = ref('ARTICLE')
const form = ref({ title: '', summary: '', body: '' })
const busy = ref(false)
const submit = async () => { busy.value = true; try { await api('/api/posts', { method: 'POST', body: JSON.stringify({ ...form.value, type: type.value }) }); form.value = { title: '', summary: '', body: '' }; emit('done') } catch (error) { emit('error', error.message) } finally { busy.value = false } }
</script>
<template><div class="composer-collection-entry"><div><strong>想把经验整理成专题？</strong><p>用模板或现成内容创建，可先保存再发布。</p></div><button type="button" class="btn btn-outline" @click="emit('collection')">创建专题 →</button></div><form class="composer-form" @submit.prevent="submit"><div class="segmented"><button type="button" :class="{ selected: type === 'ARTICLE' }" @click="type = 'ARTICLE'">文章</button><button type="button" :class="{ selected: type === 'TOPIC' }" @click="type = 'TOPIC'">话题</button></div><label>标题<input v-model="form.title" required maxlength="120" placeholder="一个清晰、有吸引力的标题" /></label><label>摘要<textarea v-model="form.summary" required maxlength="280" rows="2" placeholder="用一两句话说明这篇内容"></textarea></label><label>正文<textarea v-model="form.body" required maxlength="20000" rows="8" placeholder="支持 Markdown，从这里开始…"></textarea></label><button class="btn btn-primary btn-block" :disabled="busy">{{ busy ? '发布中…' : '发布内容' }}</button></form></template>
