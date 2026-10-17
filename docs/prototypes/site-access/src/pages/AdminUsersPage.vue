<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, dateLabel } from '@finns/lib/api'
import AdminNav from '../components/AdminNav.vue'
import { sessionUser } from '../proto/session'

const emit = defineEmits(['toast'])
const route = useRoute(); const router = useRouter()
const query = ref(typeof route.query.q === 'string' ? route.query.q : '')
const roleFilter = ref(typeof route.query.role === 'string' ? route.query.role : '')
const pageIndex = ref(Number(route.query.page || 0))
const result = ref(null); const loading = ref(true); const error = ref('')
const editing = ref(null); const form = ref({ role: '', note: '' }); const saving = ref(false); const saveError = ref(''); const lastResult = ref('')
const totalPages = computed(() => result.value ? Math.max(1, Math.ceil(result.value.total / result.value.size)) : 1)
let requestId = 0

const load = async () => {
  const id = ++requestId
  loading.value = true; error.value = ''
  const params = new URLSearchParams({ page: pageIndex.value, size: 10 })
  if (query.value.trim()) params.set('q', query.value.trim())
  if (roleFilter.value) params.set('role', roleFilter.value)
  try { const data = await api(`/api/admin/users?${params}`); if (id === requestId) result.value = data }
  catch (e) { if (id === requestId) error.value = e.message }
  finally { if (id === requestId) loading.value = false }
}
const syncUrl = () => router.replace({ query: { ...(query.value.trim() ? { q: query.value.trim() } : {}), ...(roleFilter.value ? { role: roleFilter.value } : {}), ...(pageIndex.value ? { page: pageIndex.value } : {}) } })
const search = () => { pageIndex.value = 0; syncUrl(); load() }
const go = (offset) => { pageIndex.value += offset; syncUrl(); load() }
watch(roleFilter, search)
onMounted(() => { load(); loadRoles() })

// 角色清单与说明来自服务端（管理员可能已新增、改名、删除角色或修改权限）
const roles = ref([]); const catalog = ref([])
const loadRoles = async () => { try { const data = await api('/api/admin/roles'); roles.value = data.roles; catalog.value = data.catalog } catch { /* 列表仍可用；对话框会提示读取失败 */ } }
const roleName = (key) => roles.value.find((r) => r.key === key)?.label || key
const roleSummary = (r) => catalog.value.filter((p) => r.permissions.includes(p.key)).map((p) => p.label).join('、')
const openEdit = async (user) => {
  editing.value = user; form.value = { role: user.role, note: '' }; saveError.value = ''
  await loadRoles()
}
const isSelf = computed(() => editing.value && editing.value.id === sessionUser.value?.id)
const targetCanManage = computed(() => roles.value.find((r) => r.key === form.value.role)?.permissions.includes('admin.roles'))
const lastAdmin = computed(() => editing.value?.canManage && result.value?.managerCount <= 1)
const save = async () => {
  if (saving.value || !editing.value) return
  saving.value = true; saveError.value = ''
  const target = editing.value; const before = target.role
  try {
    const updated = await api(`/api/admin/users/${target.id}/role`, { method: 'PATCH', body: JSON.stringify({ role: form.value.role, expectedRole: before, note: form.value.note }) })
    lastResult.value = before === updated.role ? `「${updated.displayName}」角色未变化。` : `已将「${updated.displayName}」从「${roleName(before)}」调整为「${updated.roleLabel}」。对方下一次请求即按新角色处理，无需重新登录。`
    emit('toast', '角色已更新')
    editing.value = null
    if (updated.id === sessionUser.value?.id) sessionUser.value = await api('/api/auth/me')
    await load()
  } catch (e) {
    saveError.value = e.message // 保存失败保留已选角色和备注
    if (/已被修改/.test(e.message)) {
      await load()
      const fresh = result.value?.items.find((item) => item.id === target.id)
      if (fresh) editing.value = fresh // 显示最新当前角色，保留已选目标与备注
    }
  } finally { saving.value = false }
}
</script>

<template>
  <section class="page-hero personal-hero"><span class="kicker">ADMIN · 0.7.0 原型</span><h1>用户与<br /><em>角色管理。</em></h1><p>为用户分配角色，角色决定可以访问的页面、内容和操作。</p></section>
  <section class="section personal-section admin-section">
    <AdminNav />
    <div class="section-heading"><div><span class="kicker">USERS</span><h2>全部用户</h2></div><span v-if="result" class="page-note">共 {{ result.total }} 人 · 能管理角色的 {{ result.managerCount }} 人</span></div>
    <form class="admin-toolbar" role="search" @submit.prevent="search">
      <label class="admin-search">搜索用户<input v-model="query" type="search" maxlength="80" placeholder="昵称或邮箱" /></label>
      <label>角色<select v-model="roleFilter"><option value="">全部角色</option><option v-for="r in roles" :key="r.key" :value="r.key">{{ r.label }}</option></select></label>
      <button class="btn btn-outline">搜索</button>
    </form>
    <p v-if="lastResult" class="admin-result" role="status">{{ lastResult }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="btn btn-outline" @click="load">重试</button></p>
    <div v-if="loading && !result" class="loading">正在加载用户…</div>
    <div v-else-if="result && !result.items.length" class="empty-state">没有符合条件的用户。</div>
    <div v-else-if="result" class="personal-list" :aria-busy="loading">
      <article v-for="item in result.items" :key="item.id" class="personal-row admin-user-row">
        <div class="admin-user-main"><i class="avatar">{{ item.displayName.slice(0, 1) }}</i><div><h3>{{ item.displayName }} <small v-if="item.id === sessionUser?.id">（你）</small></h3><p>{{ item.email }} · {{ dateLabel(item.createdAt) }} 加入</p></div></div>
        <span class="role-chip" :class="`role-${item.role.toLowerCase()}`">{{ item.roleLabel }}</span>
        <button class="btn btn-outline" @click="openEdit(item)">调整角色</button>
      </article>
    </div>
    <div v-if="result && totalPages > 1" class="pagination-control"><button class="btn btn-outline" :disabled="loading || pageIndex === 0" @click="go(-1)">上一页</button><span>第 {{ pageIndex + 1 }} / {{ totalPages }} 页</span><button class="btn btn-outline" :disabled="loading || pageIndex + 1 >= totalPages" @click="go(1)">下一页</button></div>
  </section>

  <div v-if="editing" class="overlay" @click.self="editing = null">
    <section class="modal modal-wide" role="dialog" aria-modal="true" aria-labelledby="role-dialog-title">
      <button class="modal-close" aria-label="关闭" @click="editing = null">×</button>
      <span class="kicker">CHANGE ROLE</span>
      <h2 id="role-dialog-title">调整「{{ editing.displayName }}」的角色</h2>
      <p>当前角色：<strong>{{ editing.roleLabel }}</strong>。保存后对方下一次请求即生效，不会删除其内容或私人草稿。</p>
      <form @submit.prevent="save">
        <fieldset class="role-options"><legend>目标角色</legend>
          <p v-if="!roles.length" class="form-error">角色列表读取失败，请关闭后重试。</p>
          <label v-for="r in roles" :key="r.key" class="role-option" :class="{ selected: form.role === r.key }"><input v-model="form.role" type="radio" name="role" :value="r.key" /><span><strong>{{ r.label }}<em v-if="r.key === editing.role"> · 当前</em></strong><small>{{ r.description ? `${r.description}。` : '' }}可以：{{ roleSummary(r) }}</small></span></label>
        </fieldset>
        <p v-if="form.role !== editing.role" class="role-diff">{{ editing.roleLabel }} → <strong>{{ roleName(form.role) }}</strong></p>
        <p v-if="lastAdmin && !targetCanManage" class="admin-warning">这是最后一位能管理角色的用户，改为该角色会被拒绝。请先指定另一位管理员。</p>
        <p v-else-if="isSelf && !targetCanManage" class="admin-warning">你正在降低自己的权限，保存后将立即失去管理页面。</p>
        <label>备注（可选，写入操作记录）<textarea v-model="form.note" rows="2" maxlength="200" placeholder="例如：负责运维值班"></textarea></label>
        <p class="form-error" role="alert">{{ saveError }}</p>
        <div class="admin-dialog-actions"><button type="button" class="btn" :disabled="saving" @click="editing = null">取消</button><button class="btn btn-primary" :disabled="saving || form.role === editing.role">{{ saving ? '保存中…' : '保存角色' }}</button></div>
      </form>
    </section>
  </div>
</template>
