<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../lib/api'
import { timeLabel, levelLabel, levelClass } from '../../lib/reviewText'
import { useSessionGuard } from '../../lib/sessionGuard'
import AdminLayout from '../../components/AdminLayout.vue'
import AdminDialog from '../../components/AdminDialog.vue'
import PageNav from '../../components/PageNav.vue'
import ReauthPanel from '../../components/ReauthPanel.vue'

const route = useRoute(), router = useRouter(), guard = useSessionGuard()
const data = ref(null), history = ref([]), loading = ref(true), error = ref(''), message = ref(''), q = ref(''), status = ref(''), level = ref(''), tag = ref('')
const editing = ref(null), deleting = ref(null), busy = ref(false), saveError = ref('')
const levelHint = { HIGH: '自动不通过，并转人工复核', MEDIUM: '直接送审，通过才发布', LOW: '放行并留痕，不拦截' }
let request = 0

async function load() {
  if (route.path !== '/admin/sensitive-rules') return
  const id = ++request; loading.value = true; error.value = ''
  q.value = String(route.query.q || ''); status.value = String(route.query.status || ''); level.value = String(route.query.level || ''); tag.value = String(route.query.tag || '')
  const params = new URLSearchParams({ size: 8 })
  for (const key of ['q', 'status', 'level', 'tag', 'page']) if (route.query[key]) params.set(key, String(route.query[key]))
  try {
    const result = await api('/api/review/rules?' + params)
    const logs = await api('/api/review/rules/history?size=10')
    if (id === request) { data.value = result; history.value = logs.items }
  } catch (e) { if (id === request) error.value = e.message }
  finally { if (id === request) loading.value = false }
}
function search() {
  const query = { ...(q.value.trim() ? { q: q.value.trim() } : {}), ...(status.value ? { status: status.value } : {}), ...(level.value ? { level: level.value } : {}), ...(tag.value ? { tag: tag.value } : {}) }
  if (JSON.stringify(query) === JSON.stringify(route.query)) load(); else router.push({ query })
}
function reset() { q.value = ''; status.value = ''; level.value = ''; tag.value = ''; if (!Object.keys(route.query).length) load(); else router.push({ query: {} }) }
function edit(rule) { editing.value = { id: rule?.id, phrase: rule?.phrase || '', note: rule?.note || '', enabled: rule ? rule.enabled : true, level: rule?.level || 'MEDIUM', tag: rule?.tag || '', matchMode: rule?.matchMode || 'SUBSTRING', expectedVersion: rule?.version }; saveError.value = '' }
function remove(rule) { deleting.value = { ...rule }; saveError.value = '' }

async function guarded(run) {
  if (busy.value || !(await guard.beforeSubmit())) return false
  busy.value = true; saveError.value = ''
  try { await run(); return true }
  catch (e) { if (!guard.handle(e)) saveError.value = e.message; return false }
  finally { busy.value = false }
}
async function save() {
  const item = editing.value
  const ok = await guarded(async () => {
    const body = JSON.stringify({ phrase: item.phrase, note: item.note, enabled: item.enabled, level: item.level, tag: item.tag, matchMode: item.matchMode, expectedVersion: item.expectedVersion })
    await api(item.id ? `/api/review/rules/${item.id}` : '/api/review/rules', { method: item.id ? 'PUT' : 'POST', body })
    message.value = '规则已保存，仅影响后续提交；未扫描或下架旧内容。'
  })
  if (ok) { editing.value = null; await load() }
}
async function toggle(rule) {
  const ok = await guarded(async () => {
    await api(`/api/review/rules/${rule.id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled: !rule.enabled, expectedVersion: rule.version }) })
    message.value = `规则已${rule.enabled ? '停用' : '启用'}，仅影响后续提交。`
  })
  if (!ok && saveError.value) error.value = saveError.value
  if (ok) await load()
}
async function confirmDelete() {
  const ok = await guarded(async () => {
    const result = await api(`/api/review/rules/${deleting.value.id}`, { method: 'DELETE', body: JSON.stringify({ expectedVersion: deleting.value.version }) })
    message.value = result.message
  })
  if (ok) { deleting.value = null; await load() }
}
async function batch(tagName, enabled) {
  const ok = await guarded(async () => { message.value = (await api('/api/review/rules/batch', { method: 'POST', body: JSON.stringify({ tag: tagName, enabled }) })).message })
  if (!ok && saveError.value) error.value = saveError.value
  if (ok) await load()
}

// ---- 词库导入：站长上传一个或多个文本文件（文件名=标签、每行一词）或 CSV（词语,标签）；预览不写库，提交按标签批量落库
const importing = ref(null), importFiles = ref([]), groupSetup = ref([]), includeSuspect = ref(false), importQuery = ref(''), importTag = ref('')
const importShown = computed(() => (importing.value?.items || []).filter((i) => (!importTag.value || i.tag === importTag.value) && (!importQuery.value.trim() || i.phrase.toLowerCase().includes(importQuery.value.trim().toLowerCase()))))
const importCount = computed(() => !importing.value ? 0 : groupSetup.value.filter((g) => g.on).reduce((n, g) => n + importing.value.items.filter((i) => i.tag === g.tag && !i.duplicate && (includeSuspect.value || !i.suspect)).length, 0))
const groupInfo = (tagName) => importing.value.groups.find((g) => g.tag === tagName)
function formOf(files) { const form = new FormData(); for (const f of files) form.append('files', f); return form }
async function chooseFiles(event) {
  const files = [...event.target.files]; event.target.value = ''
  if (!files.length) return
  busy.value = true; error.value = ''
  try {
    importing.value = await api('/api/review/rules/import-preview', { method: 'POST', body: formOf(files) })
    importFiles.value = files; includeSuspect.value = false; importQuery.value = ''; importTag.value = ''; saveError.value = ''
    groupSetup.value = importing.value.groups.map((g) => ({ tag: g.tag, level: g.level, enabled: g.enabled, on: true }))
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function commitImport() {
  const ok = await guarded(async () => {
    const form = formOf(importFiles.value)
    form.append('config', JSON.stringify({ groups: groupSetup.value.filter((g) => g.on).map((g) => ({ tag: g.tag, level: g.level, enabled: g.enabled })), includeSuspect: includeSuspect.value }))
    message.value = (await api('/api/review/rules/import', { method: 'POST', body: form })).message
  })
  if (ok) { importing.value = null; await load() }
}
watch(() => route.fullPath, load)
onMounted(load)
</script>

<template>
  <AdminLayout title="敏感规则" description="维护发布检测使用的词语、等级、标签与启用状态；一次提交命中多条时取最高等级。" kicker="CONTENT · SENSITIVE RULES">
    <ReauthPanel :guard="guard" />
    <div class="sr-summary"><span>启用 <strong>{{ data?.enabledCount ?? '—' }}</strong> 条</span><span>停用 <strong>{{ data?.disabledCount ?? '—' }}</strong> 条</span><p>仅站长可管理；变更不回扫历史发布内容。</p></div>
    <div class="sr3-levels" role="group" aria-label="三级处理规则"><div v-for="key in ['HIGH', 'MEDIUM', 'LOW']" :key="key" :class="'sr3-level ' + key"><span class="rv-badge" :class="levelClass(key)">{{ levelLabel(key) }}敏感</span><strong>{{ data?.levels?.[key] ?? '—' }}</strong><small>{{ levelHint[key] }}</small></div></div>
    <form class="rv-filters sr-toolbar" @submit.prevent="search">
      <label class="rv-search">搜索规则<input v-model="q" placeholder="输入词语、短语或备注" /></label>
      <label>等级<select v-model="level"><option value="">全部等级</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label>
      <label v-if="data?.tags?.length">标签<select v-model="tag"><option value="">全部标签</option><option v-for="t in data.tags" :key="t.tag" :value="t.tag">{{ t.tag }}（{{ t.total }}）</option></select></label>
      <label>规则状态<select v-model="status"><option value="">全部状态</option><option value="ENABLED">已启用</option><option value="DISABLED">已停用</option></select></label>
      <button class="btn btn-primary" type="submit">查询</button><button class="btn btn-ghost" type="button" @click="reset">重置</button>
      <label class="btn btn-ghost sr3-upload" :class="{ disabled: busy }">导入词库…<input type="file" multiple accept=".txt,.csv,text/plain,text/csv" :disabled="busy" @change="chooseFiles" /></label>
      <button class="btn btn-primary" type="button" @click="edit()">＋ 新增规则</button>
    </form>
    <div v-if="data?.tags?.length" class="sr3-batch" role="group" aria-label="按标签批量启停"><strong>按标签批量启停</strong><span v-for="t in data.tags" :key="t.tag" class="sr3-batch-item"><b>{{ t.tag }}</b> 启用 {{ t.enabled }} / {{ t.total }}<button :disabled="busy || t.enabled === t.total" @click="batch(t.tag, true)">全部启用</button><button :disabled="busy || !t.enabled" @click="batch(t.tag, false)">全部停用</button></span></div>
    <p v-if="message" class="rv-notice success" role="status">{{ message }}</p>
    <div v-if="loading" class="rv-empty"><h2>正在读取规则</h2></div>
    <div v-else-if="error" class="rv-empty" role="alert"><h2>暂时无法读取</h2><p>{{ error }}</p><button class="btn btn-primary" @click="load">重试</button></div>
    <template v-else>
      <div class="sr-table-caption"><h2>规则列表</h2><span>共 {{ data?.total || 0 }} 条符合条件的规则</span></div>
      <div v-if="!data?.items.length" class="rv-empty"><h2>没有符合条件的规则</h2><p>{{ q || status || level || tag ? '调整搜索词或筛选后再试。' : '可以新增第一条规则或导入词库；未启用任何规则时新内容不会直接发布。' }}</p><button class="btn btn-ghost" @click="reset">清空筛选</button></div>
      <table v-else class="sr-table sr3-table">
        <caption class="sr-only">敏感规则列表，可编辑、启停和删除</caption>
        <thead><tr><th>词语或短语</th><th>等级</th><th>状态</th><th>标签</th><th>备注</th><th>操作</th></tr></thead>
        <tbody><tr v-for="rule in data.items" :key="rule.id">
          <td class="sr-phrase"><strong>{{ rule.phrase }}</strong><small v-if="rule.updatedAt">更新于 {{ timeLabel(rule.updatedAt) }} · {{ rule.matchMode === 'WHOLE_WORD' ? '整词' : '子串' }}</small></td>
          <td data-label="等级"><span class="rv-badge" :class="levelClass(rule.level)">{{ levelLabel(rule.level) }}</span></td>
          <td data-label="状态"><span class="rv-badge" :class="rule.enabled ? 'green' : 'neutral'">{{ rule.enabled ? '已启用' : '已停用' }}</span></td>
          <td data-label="标签">{{ rule.tag || '—' }}</td>
          <td class="sr-note" data-label="备注">{{ rule.note || '—' }}</td>
          <td class="sr-actions"><button :disabled="busy" @click="edit(rule)">编辑</button><button :disabled="busy" @click="toggle(rule)">{{ rule.enabled ? '停用' : '启用' }}</button><button class="danger" :disabled="busy" @click="remove(rule)">删除</button></td>
        </tr></tbody>
      </table>
      <PageNav v-if="data?.total" :page="data.page" :size="data.size" :total="data.total" @go="n => router.push({ query: { ...route.query, page: n } })" />
    </template>
    <p class="rv-footnote">匹配前统一全半角、大小写，并去掉空白、标点和零宽字符；不识别同义词、谐音、拼音，也不看图片。中文单字或两字的短词容易误伤正常内容，导入时默认不导入。启用规则上限 20,000 条。</p>
    <details v-if="history.length" class="rv-history"><summary>规则操作记录（{{ history.length }}）</summary><p v-for="entry in history" :key="entry.id"><strong>{{ entry.action }} · {{ entry.phrase }}</strong><small>{{ entry.operator }} · {{ timeLabel(entry.at) }}</small></p></details>
    <AdminDialog v-if="editing" labelledby="sr-edit-title" :busy="busy" @close="editing = null">
      <span class="kicker">SENSITIVE RULE</span><h2 id="sr-edit-title">{{ editing.id ? '编辑敏感规则' : '新增敏感规则' }}</h2>
      <form @submit.prevent="save">
        <label class="rv-field">词语或短语<input v-model="editing.phrase" required maxlength="40" placeholder="输入需要匹配的文字" /></label>
        <fieldset class="sr3-level-pick"><legend>敏感等级</legend><label v-for="key in ['LOW', 'MEDIUM', 'HIGH']" :key="key" :class="{ on: editing.level === key }"><input v-model="editing.level" type="radio" name="level" :value="key" /><span class="rv-badge" :class="levelClass(key)">{{ levelLabel(key) }}</span><small>{{ levelHint[key] }}</small></label></fieldset>
        <label class="rv-field">匹配方式<select v-model="editing.matchMode"><option value="SUBSTRING">子串（默认）：文本中出现即命中</option><option value="WHOLE_WORD">整词：英文单词两端必须是独立分隔，中文端不受影响</option></select></label>
        <label class="rv-field">标签（可选）<input v-model="editing.tag" maxlength="30" placeholder="用于批量启停，例如：广告" /></label>
        <label class="rv-field">备注<textarea v-model="editing.note" rows="3" maxlength="200" placeholder="说明用途，便于日后维护" /></label>
        <label class="rv-check"><input v-model="editing.enabled" type="checkbox" />启用此规则</label>
        <p class="rv-notice">保存后仅影响后续提交，不自动下架已有内容。</p>
        <p v-if="saveError" class="rv-notice danger" role="alert">{{ saveError }}</p>
        <div class="rv-dialog-actions"><button class="btn btn-ghost" type="button" :disabled="busy" @click="editing = null">取消</button><button class="btn btn-primary" :disabled="busy || guard.expired.value">{{ busy ? '保存中…' : '保存规则' }}</button></div>
      </form>
    </AdminDialog>
    <AdminDialog v-if="deleting" labelledby="sr-delete-title" :busy="busy" @close="deleting = null">
      <span class="kicker">DELETE RULE</span><h2 id="sr-delete-title">删除这条规则？</h2><p class="sr-delete-phrase">{{ deleting.phrase }}</p>
      <p>删除后，该规则会从列表移除，不再参与后续发布检测。已有发布内容保持不变，操作记录会保留。</p>
      <p v-if="saveError" class="rv-notice danger" role="alert">{{ saveError }}</p>
      <div class="rv-dialog-actions"><button class="btn btn-ghost" :disabled="busy" @click="deleting = null">取消</button><button class="btn btn-primary" :disabled="busy || guard.expired.value" @click="confirmDelete">{{ busy ? '删除中…' : '确认删除' }}</button></div>
    </AdminDialog>
    <AdminDialog v-if="importing" wide labelledby="sr-import-title" :busy="busy" @close="importing = null">
      <span class="kicker">IMPORT PREVIEW</span><h2 id="sr-import-title">按标签批量导入词库</h2>
      <p class="rv-notice amber">文件名即标签、每行一个词（也支持 CSV：词语,标签）。预览不会写入数据库；来源与许可请自行核对。共 {{ importing.totalWords }} 个不重复词，{{ importing.rejected }} 行被忽略（空行、过长或只含标点）{{ importing.truncated ? '；样例检索只显示前 2000 条' : '' }}。</p>
      <ul class="sr3-import-rules"><li>每个标签给出建议等级和建议状态，可改；无需逐词审批。</li><li>疑似误报词（长度≤2 个字符）默认不导入，需要时勾选“包含疑似误报词”。</li><li>同一个词出现在多个类别时，保留最高等级对应的类别。</li></ul>
      <table class="sr-table sr3-groups"><caption class="sr-only">按标签设置导入方式</caption><thead><tr><th>导入</th><th>标签</th><th>词数</th><th>等级</th><th>导入后</th></tr></thead>
        <tbody><tr v-for="g in groupSetup" :key="g.tag"><td><input type="checkbox" v-model="g.on" :aria-label="'导入标签 ' + g.tag" /></td><td><strong>{{ g.tag }}</strong><small v-if="groupInfo(g.tag).suspect"> · 含 {{ groupInfo(g.tag).suspect }} 个疑似误报</small><small v-if="groupInfo(g.tag).duplicate"> · {{ groupInfo(g.tag).duplicate }} 个已存在</small></td><td>{{ groupInfo(g.tag).count }}</td><td><select v-model="g.level" :aria-label="g.tag + ' 的等级'"><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></td><td><select v-model="g.enabled" :aria-label="g.tag + ' 导入后状态'"><option :value="false">停用（稍后批量启用）</option><option :value="true">立即启用</option></select></td></tr></tbody></table>
      <label class="rv-check"><input type="checkbox" v-model="includeSuspect" />包含疑似误报词（启用后可能误伤正常内容）</label>
      <div class="sr3-import-search"><h3>样例检索（仅查询）</h3>
        <div class="rv-filters"><label class="rv-search">搜索词语<input v-model="importQuery" placeholder="例如：微信" /></label><label>标签<select v-model="importTag"><option value="">全部</option><option v-for="g in importing.groups" :key="g.tag" :value="g.tag">{{ g.tag }}</option></select></label></div>
        <table class="sr-table sr3-import"><thead><tr><th>词语</th><th>标签</th><th>提示</th></tr></thead><tbody><tr v-for="item in importShown" :key="item.phrase + item.tag" :class="{ suspect: item.suspect }"><td><strong>{{ item.phrase }}</strong></td><td>{{ item.tag }}<small v-if="item.alsoIn?.length"> · 另见 {{ item.alsoIn.join('、') }}</small></td><td><span v-if="item.duplicate" class="rv-badge neutral">已存在</span><span v-else-if="item.suspect" class="rv-badge amber">疑似误报 · 过短</span><span v-else class="rv-badge green">可用</span></td></tr><tr v-if="!importShown.length"><td colspan="3">没有符合条件的词。</td></tr></tbody></table></div>
      <p v-if="saveError" class="rv-notice danger" role="alert">{{ saveError }}</p>
      <div class="rv-dialog-actions"><button class="btn btn-ghost" :disabled="busy" @click="importing = null">取消</button><button class="btn btn-primary" :disabled="busy || !importCount || guard.expired.value" @click="commitImport">{{ busy ? '导入中…' : `按设置导入 ${importCount} 条` }}</button></div>
    </AdminDialog>
  </AdminLayout>
</template>
