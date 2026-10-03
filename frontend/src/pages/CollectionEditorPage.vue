<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api } from '../lib/api'
import { createDraftSaveQueue } from '../lib/draftSaveQueue'
import { publicationIssues } from '../lib/collectionDraft'
import CollectionOutlineEditor from '../components/CollectionOutlineEditor.vue'
const route = useRoute(), router = useRouter()
const draft = ref(null), posts = ref([]), loading = ref(true), saving = ref(false), error = ref(''), saveState = ref(''), operation = ref(false)
const outlineEditor = ref(null), nameInput = ref(null), attemptedPublish = ref(false)
const issues = computed(() => attemptedPublish.value && draft.value ? publicationIssues(draft.value) : [])
const locateIssue = async issue => { await nextTick(); if (issue.field === 'name') nameInput.value?.focus(); else outlineEditor.value?.revealIssue(issue) }
let timer, hydrated = false, acknowledging = false
const statusLabel = computed(() => ({ DRAFT: '私人草稿', PUBLISHED: '已发布 · 当前编辑工作稿', ARCHIVED: '已归档' }[draft.value?.status] || ''))
const payload = () => JSON.parse(JSON.stringify({ revision: draft.value.revision, name: draft.value.name, description: draft.value.description, format: draft.value.format, audience: draft.value.audience, goal: draft.value.goal, sections: draft.value.sections }))
const queue = createDraftSaveQueue({
  snapshot: payload,
  send: document => api(`/api/me/collections/${draft.value.id}/draft`, { method: 'PUT', body: JSON.stringify(document) }),
  acknowledge: result => { acknowledging = true; draft.value.revision = result.revision; draft.value.updatedAt = result.updatedAt; acknowledging = false },
  state: message => { saveState.value = message }
})
watch(draft, () => {
  if (!hydrated || acknowledging) return
  queue.changed(); clearTimeout(timer); timer = setTimeout(() => { if (!operation.value) save() }, 1200)
}, { deep: true, flush: 'sync' })
const load = async () => {
  try {
    draft.value = await api(`/api/me/collections/${route.params.id}/draft`)
    let page = 0, more = true
    while (more) { const mine = await api(`/api/me/posts?page=${page++}&size=50`); posts.value.push(...mine.items); more = mine.hasMore }
    hydrated = true
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
onMounted(load)
const save = async () => {
  clearTimeout(timer); saving.value = true; error.value = ''
  try { await queue.flush(); return true } catch (e) { error.value = e.message; return false } finally { saving.value = false }
}
const preview = async () => { if (await save()) await router.push(`/me/collections/${draft.value.id}/preview`) }
const append = async () => { if (await save()) await router.push({ path: '/me/collections/import', query: { append: draft.value.id } }) }
const transition = async action => {
  if (action === 'publish') {
    attemptedPublish.value = true
    if (issues.value.length) { error.value = ''; return }
  }
  operation.value = true
  try {
    if (!await save()) return
    const result = await api(`/api/me/collections/${draft.value.id}/${action}`, { method: 'POST', body: JSON.stringify({ revision: draft.value.revision }) })
    acknowledging = true; draft.value.revision = result.revision; draft.value.status = result.status; draft.value.publishedAt = result.publishedAt; acknowledging = false
    saveState.value = action === 'publish' ? '已更新公开版本' : '已归档'
    attemptedPublish.value = false
  } catch (e) { error.value = e.message } finally { operation.value = false }
}
const copyContent = async () => { try { await navigator.clipboard.writeText([draft.value.name, ...draft.value.sections.flatMap(s => [s.title, ...s.entries.map(e => `${e.title || ''}\n${e.body || e.externalUrl || e.postTitle || ''}\n${e.annotation || ''}`)])].join('\n\n')); saveState.value = '已复制当前内容，可保留后再处理版本冲突' } catch { error.value = '无法访问剪贴板，请直接选择并复制编辑区内容' } }
onBeforeRouteLeave(async () => !queue.dirty || await save() || window.confirm('保存未成功。确认离开会丢失未保存的修改，请先复制内容。'))
const unload = event => { if (queue.dirty) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', unload)
onBeforeUnmount(() => { clearTimeout(timer); window.removeEventListener('beforeunload', unload) })
</script>
<template>
  <section class="workspace-shell"><div v-if="loading" class="loading">正在打开专题工作台…</div><div v-else-if="!draft" class="empty-state"><p>{{ error }}</p><RouterLink to="/me/collections">返回我的专题</RouterLink></div><template v-else>
    <header class="workspace-header"><div><RouterLink to="/me/collections" class="text-link">← 我的专题</RouterLink><span class="status-pill">{{ statusLabel }}</span></div><div class="workspace-actions"><span class="save-state" role="status">{{ saveState || '已保存' }}</span><button class="btn btn-outline" :disabled="saving || operation" @click="save">保存</button><button class="btn btn-outline" :disabled="saving || operation" @click="preview">阅读预览</button><button class="btn btn-outline" :disabled="saving || operation" @click="append">导入补充资料</button><button class="btn btn-primary" :disabled="saving || operation" @click="transition('publish')">{{ draft.status === 'PUBLISHED' ? '更新发布' : '发布专题' }}</button><button v-if="draft.status === 'PUBLISHED'" class="btn btn-ghost" :disabled="saving || operation" @click="transition('archive')">归档</button></div></header>
    <p v-if="draft.status === 'PUBLISHED'" class="workspace-notice">自动保存仅更新工作稿。点击“更新发布”后，公开版本才会改变。<RouterLink :to="`/collections/${draft.id}`">查看公开版本</RouterLink></p>
    <div v-if="issues.length" class="publish-issues" role="alert"><strong>发布前还有 {{ issues.length }} 处需要补充</strong><p>未完成内容可以先保存在草稿中。点击提示，直接前往对应位置。</p><ul><li v-for="(issue, index) in issues" :key="index"><button type="button" @click="locateIssue(issue)">{{ issue.message }} →</button></li></ul></div><p v-if="error" class="form-error workspace-error" role="alert">{{ error }} <button class="btn btn-outline" @click="copyContent">复制当前内容以保留</button></p><main class="workspace-main"><aside class="workspace-meta"><label>专题名称<input ref="nameInput" v-model="draft.name" maxlength="80" :disabled="operation" /></label><details><summary>简介与目标（可选）</summary><label>简介<textarea v-model="draft.description" maxlength="260" rows="3" :disabled="operation" /></label><label>适合谁<input v-model="draft.audience" maxlength="160" :disabled="operation" /></label><label>完成目标<textarea v-model="draft.goal" maxlength="300" rows="3" :disabled="operation" /></label><label>结构<select v-model="draft.format" :disabled="operation"><option value="GUIDE">步骤指南</option><option value="READING_PATH">阅读路线</option><option value="RESOURCE_LIST">资源清单</option><option value="CUSTOM">自由专题</option></select></label></details><p>内容没写完也可保存；发布时会提示需要补充的位置。</p></aside><fieldset class="workspace-canvas outline-fieldset" :disabled="operation"><CollectionOutlineEditor ref="outlineEditor" v-model="draft.sections" :posts="posts" /></fieldset></main>
  </template></section>
</template>
