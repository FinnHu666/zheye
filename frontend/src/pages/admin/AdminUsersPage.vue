<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, dateLabel } from '../../lib/api'
import { currentUser, refreshSession } from '../../lib/session'
import AdminLayout from '../../components/AdminLayout.vue'
import AdminDialog from '../../components/AdminDialog.vue'

const emit = defineEmits(['toast'])
const route = useRoute(); const router = useRouter()
const str = (value) => typeof value === 'string' ? value : ''
const query = ref(str(route.query.q)); const roleFilter = ref(str(route.query.role)); const statusFilter = ref(str(route.query.status))
const pageIndex = ref(Math.max(0, Number(route.query.page) || 0))
const result = ref(null); const loading = ref(true); const error = ref(''); const lastResult = ref('')
const roles = ref([]); const catalog = ref([])
const totalPages = computed(() => result.value ? Math.max(1, Math.ceil(result.value.total / result.value.size)) : 1)
const isOwner = computed(() => currentUser.value?.role?.code === 'OWNER')
let requestId = 0

const load = async () => {
  const id = ++requestId
  loading.value = true; error.value = ''
  const params = new URLSearchParams({ page: pageIndex.value, size: 10 })
  if (query.value.trim()) params.set('q', query.value.trim())
  if (roleFilter.value) params.set('role', roleFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  try { const data = await api(`/api/admin/users?${params}`); if (id === requestId) result.value = data }
  catch (e) { if (id === requestId) error.value = e.message }
  finally { if (id === requestId) loading.value = false }
}
// 筛选与页码写入地址：可直达、刷新、前进后退
const syncUrl = () => {
  const next = { ...(query.value.trim() ? { q: query.value.trim() } : {}), ...(roleFilter.value ? { role: roleFilter.value } : {}), ...(statusFilter.value ? { status: statusFilter.value } : {}), ...(pageIndex.value ? { page: String(pageIndex.value) } : {}) }
  if (JSON.stringify(next) === JSON.stringify(route.query)) load()
  else router.push({ query: next })
}
const search = () => { pageIndex.value = 0; syncUrl() }
const go = (offset) => { pageIndex.value += offset; syncUrl() }
const clearFilters = () => { query.value = ''; roleFilter.value = ''; statusFilter.value = ''; pageIndex.value = 0; syncUrl() }
watch(() => route.fullPath, () => {
  if (route.path !== '/admin/users') return
  query.value = str(route.query.q); roleFilter.value = str(route.query.role); statusFilter.value = str(route.query.status)
  pageIndex.value = Math.max(0, Number(route.query.page) || 0); load()
})
// 角色清单与说明来自服务端（管理员可能已新增、改名、删除角色或修改权限）
const loadRoles = async () => { try { const data = await api('/api/admin/roles'); roles.value = data.roles; catalog.value = data.catalog } catch { /* 列表仍可用；对话框会提示读取失败 */ } }
const roleName = (code) => roles.value.find((r) => r.code === code)?.name || code
const roleSummary = (r) => catalog.value.filter((p) => r.permissions.includes(p.code)).map((p) => p.label).join('、')
onMounted(() => { load(); loadRoles() })

// —— 停用 / 恢复 ——
const statusEditing = ref(null), targetStatus = ref(''), reason = ref(''), phase = ref('reason'), statusError = ref(''), statusBusy = ref(false), statusDialog = ref(null)
const wordCount = computed(() => [...reason.value.trim()].length)
const statusText = (value) => value === 'DISABLED' ? '停用' : '正常'
const actionText = computed(() => targetStatus.value === 'DISABLED' ? '停用' : '恢复')
const statusBlocked = computed(() => {
  const u = statusEditing.value
  if (!u) return ''
  if (!u.canChangeStatus) return u.statusBlockReason
  if (targetStatus.value !== 'DISABLED') return ''
  if (u.accountStatus === 'ACTIVE' && u.canManage && result.value?.managerCount <= 1) return '这是最后一位正常管理者，请先安排另一位正常且能管理角色的用户。'
  return ''
})
const alreadyChanged = computed(() => statusEditing.value?.accountStatus === targetStatus.value)
async function openStatus(u) {
  statusEditing.value = { ...u }; targetStatus.value = u.accountStatus === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
  reason.value = ''; statusError.value = ''; phase.value = 'reason'
  await nextTick(); statusDialog.value?.focus()
}
function closeStatus() { if (!statusBusy.value) statusEditing.value = null }
async function reviewStatus() {
  statusError.value = ''
  if (statusBlocked.value || alreadyChanged.value) return
  if (wordCount.value < 1 || wordCount.value > 200) { statusError.value = '请填写1至200字的操作原因。'; return }
  phase.value = 'confirm'; await nextTick(); statusDialog.value?.focus()
}
async function saveStatus() {
  if (statusBusy.value || statusBlocked.value || alreadyChanged.value) return
  statusBusy.value = true; statusError.value = ''
  const u = statusEditing.value
  try {
    await api(`/api/admin/users/${u.id}/status`, { method: 'PATCH', body: JSON.stringify({ status: targetStatus.value, expectedVersion: u.statusVersion, note: reason.value.trim() }) })
    lastResult.value = targetStatus.value === 'DISABLED' ? `已停用「${u.displayName}」。旧会话已失效，已发布作品保持原可见范围。` : `已恢复「${u.displayName}」。用户需重新登录，旧会话不会恢复。`
    statusEditing.value = null; emit('toast', '账号状态已更新'); await load(); await loadRoles()
  } catch (e) {
    statusError.value = e.message; phase.value = 'reason' // 失败保留原因，回到可修改的一步
    if (e.status === 409 || e.status === 403) { // 他人已改变状态或权限：重读该用户，保留原因，要求核对后再确认
      try {
        const data = await api(`/api/admin/users?q=${encodeURIComponent(u.email)}&size=10`)
        const fresh = data.items.find((item) => item.id === u.id)
        if (fresh) statusEditing.value = fresh
        await load()
      } catch { /* 保留原上下文 */ }
    }
  } finally { statusBusy.value = false }
}

// —— 调整角色 ——
const editing = ref(null); const form = ref({ roleCode: '', note: '' }); const saving = ref(false); const saveError = ref('')
const openEdit = async (user) => { editing.value = user; form.value = { roleCode: user.role.code, note: '' }; saveError.value = ''; await loadRoles() }
const isSelf = computed(() => editing.value && editing.value.id === currentUser.value?.id)
const targetCanManage = computed(() => roles.value.find((r) => r.code === form.value.roleCode)?.permissions.includes('admin.roles'))
const lastManager = computed(() => editing.value?.accountStatus !== 'DISABLED' && editing.value?.canManage && result.value?.managerCount <= 1)
const save = async () => {
  if (saving.value || !editing.value) return
  saving.value = true; saveError.value = ''
  const target = editing.value; const before = target.role
  try {
    const updated = await api(`/api/admin/users/${target.id}/role`, { method: 'PATCH', body: JSON.stringify({ roleCode: form.value.roleCode, expectedRoleCode: before.code, note: form.value.note }) })
    lastResult.value = before.code === updated.role.code ? `「${updated.displayName}」角色未变化。` : `已将「${updated.displayName}」从「${before.name}」调整为「${updated.role.name}」。对方下一次请求即按新角色处理，无需重新登录。`
    emit('toast', '角色已更新')
    editing.value = null
    if (updated.id === currentUser.value?.id) await refreshSession()
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

// —— 转交站长 ——
const transferDialog = ref(null), transferOpen = ref(false), transferData = ref(null), transferLoading = ref(false), transferBusy = ref(false), transferError = ref(''), transferPhase = ref('choose')
const transferTarget = ref(''), transferNote = ref(''), transferEmail = ref('')
const recipient = computed(() => transferData.value?.candidates.find((u) => String(u.id) === transferTarget.value))
const transferWords = computed(() => [...transferNote.value.trim()].length)
async function loadTransfer() {
  transferLoading.value = true
  try { transferData.value = await api('/api/admin/ownership') }
  catch (e) { transferData.value = null; transferError.value = e.message }
  finally { transferLoading.value = false }
}
async function openTransfer() {
  transferTarget.value = ''; transferNote.value = ''; transferEmail.value = ''; transferError.value = ''; transferPhase.value = 'choose'; transferData.value = null
  transferOpen.value = true; await loadTransfer()
}
async function reviewTransfer() {
  transferError.value = ''
  if (!recipient.value) { transferError.value = '请选择一位正常管理员。'; return }
  if (transferWords.value < 1 || transferWords.value > 200) { transferError.value = '请填写1至200字的转交原因。'; return }
  transferEmail.value = ''; transferPhase.value = 'confirm'; await nextTick(); transferDialog.value?.focus()
}
async function saveTransfer() {
  if (transferBusy.value || !recipient.value || transferEmail.value.trim().toLowerCase() !== recipient.value.email.toLowerCase()) return
  transferBusy.value = true; transferError.value = ''
  try {
    const data = await api('/api/admin/ownership', { method: 'POST', body: JSON.stringify({ targetId: recipient.value.id, expectedOwnerId: transferData.value.owner.id, expectedVersion: transferData.value.version, note: transferNote.value.trim(), confirmEmail: transferEmail.value.trim() }) })
    lastResult.value = `站长已转交给「${data.owner.displayName}」。你现为管理员；接任人需重新登录。双方其他旧会话已失效，内容保持原样。`
    transferOpen.value = false; await refreshSession(); await loadRoles(); await load(); emit('toast', '站长身份已转交')
  } catch (e) {
    transferError.value = e.message // 失败保留原因；信息过期则重读候选人后回到第一步
    if ([409, 422].includes(e.status)) { transferPhase.value = 'choose'; transferEmail.value = ''; await loadTransfer() }
  } finally { transferBusy.value = false }
}
</script>

<template>
  <AdminLayout title="用户管理" description="管理账号使用资格，保留每一次决定的来由。">
    <div class="ua-overview"><div><span>当前筛选结果</span><strong>{{ result ? result.total : '—' }}<small>人</small></strong></div><div><span>正常管理者</span><strong>{{ result ? result.managerCount : '—' }}<small>人</small></strong></div><p><b>账号停用与作品处置分开</b><span>停用不会隐藏已发布作品；恢复账号后需要重新登录。</span></p></div>
    <div v-if="isOwner" class="ua-owner-strip"><div><strong>站长身份</strong><p>全站仅一位站长。交接管理职责时，可将身份转交给正常管理员。</p></div><button type="button" class="btn btn-outline" @click="openTransfer">转交站长</button></div>
    <form class="admin-toolbar" role="search" @submit.prevent="search">
      <label class="admin-search">搜索用户<input v-model="query" type="search" maxlength="80" placeholder="昵称或邮箱" /></label>
      <label>账号状态<select v-model="statusFilter"><option value="">全部状态</option><option value="ACTIVE">正常</option><option value="DISABLED">停用</option></select></label>
      <label>角色<select v-model="roleFilter"><option value="">全部角色</option><option v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}</option></select></label>
      <div class="ua-filter-actions"><button class="btn btn-primary">查询</button><button type="button" class="btn btn-ghost" @click="clearFilters">重置</button></div>
    </form>
    <p v-if="lastResult" class="admin-result" role="status">{{ lastResult }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="btn btn-outline" @click="load">重试</button></p>
    <div v-if="loading && !result" class="loading">正在加载用户…</div>
    <div v-else-if="result && !result.items.length" class="empty-state">没有符合条件的用户。<button type="button" class="btn btn-outline" @click="clearFilters">清除筛选</button></div>
    <div v-else-if="result" class="personal-list" :aria-busy="loading">
      <article v-for="item in result.items" :key="item.id" class="personal-row admin-user-row">
        <div class="admin-user-main"><i class="avatar">{{ item.displayName.slice(0, 1) }}</i><div><h3>{{ item.displayName }} <small v-if="item.id === currentUser?.id">（你）</small></h3><p>{{ item.email }} · {{ dateLabel(item.createdAt) }} 加入</p></div></div>
        <div class="ua-badges"><span class="ua-status" :class="item.accountStatus === 'DISABLED' ? 'is-disabled' : 'is-active'"><i></i>{{ statusText(item.accountStatus) }}</span><span class="role-chip" :class="`role-${item.role.code.toLowerCase()}`">{{ item.role.name }}</span></div>
        <div class="ua-row-actions">
          <button type="button" class="btn btn-outline" :disabled="!item.canAssignRole" :title="item.roleBlockReason || '调整角色'" @click="openEdit(item)">调整角色</button>
          <button type="button" class="btn" :class="item.accountStatus === 'DISABLED' ? 'ua-restore' : 'ua-disable'" :disabled="!item.canChangeStatus" :title="item.statusBlockReason || ''" @click="openStatus(item)">{{ item.accountStatus === 'DISABLED' ? '恢复账号' : '停用账号' }}</button>
          <small v-if="item.siteOwner" class="ua-protection-note">唯一站长 · 需专门转交</small>
          <small v-else-if="!item.canChangeStatus && item.id !== currentUser?.id" class="ua-protection-note">仅站长可管理</small>
        </div>
      </article>
    </div>
    <div v-if="result && totalPages > 1" class="pagination-control"><button type="button" class="btn btn-outline" :disabled="loading || pageIndex === 0" @click="go(-1)">上一页</button><span>第 {{ pageIndex + 1 }} / {{ totalPages }} 页</span><button type="button" class="btn btn-outline" :disabled="loading || pageIndex + 1 >= totalPages" @click="go(1)">下一页</button></div>
  </AdminLayout>

  <AdminDialog v-if="transferOpen" ref="transferDialog" labelledby="transfer-title" :busy="transferBusy" @close="transferOpen = false">
    <span class="kicker">SITE OWNERSHIP</span>
    <h2 id="transfer-title">{{ transferPhase === 'choose' ? '转交站长身份' : '确认站长交接' }}</h2>
    <p>站长拥有最高管理权限，交接后全站仍只保留一位站长。</p>
    <p v-if="transferLoading" class="loading" role="status">正在读取可接任的管理员…</p>
    <template v-else-if="transferData">
      <form v-if="transferPhase === 'choose'" @submit.prevent="reviewTransfer">
        <label>接任管理员<select v-model="transferTarget"><option value="">请选择正常管理员</option><option v-for="u in transferData.candidates" :key="u.id" :value="String(u.id)">{{ u.displayName }} · {{ u.email }}</option></select></label>
        <p v-if="!transferData.candidates.length" class="admin-warning">暂无可接任的正常管理员。请先恢复管理员账号，或将另一位成员调整为管理员。</p>
        <div class="ua-transfer-effects"><p><strong>你：</strong>站长 → 管理员，失去站长专属权限。</p><p><strong>接任人：</strong>管理员 → 唯一站长，需重新登录。</p><p>双方其他旧会话失效；资料、作品和私人内容保持原样。后续转交须由新站长发起。</p></div>
        <label class="ua-reason-label">转交原因 <span>必填 · 仅操作记录可见</span><textarea v-model="transferNote" rows="3" maxlength="220" placeholder="说明这次管理职责交接的原因"></textarea><small :class="{ 'ua-over-limit': transferWords > 200 }">{{ transferWords }}/200</small></label>
        <p class="form-error" role="alert">{{ transferError }}</p>
        <div class="admin-dialog-actions"><button type="button" class="btn btn-outline" @click="transferOpen = false">取消</button><button class="btn btn-primary" :disabled="!recipient || !transferWords || transferWords > 200">下一步，核对交接</button></div>
      </form>
      <form v-else @submit.prevent="saveTransfer">
        <div class="ua-transfer-recipient"><small>即将接任的唯一站长</small><strong>{{ recipient?.displayName }}</strong><span>{{ recipient?.email }}</span></div>
        <p class="admin-warning">确认后，你将成为管理员，不能再管理其他管理员或新站长。</p>
        <div class="ua-reason-review"><small>转交原因</small><p>{{ transferNote.trim() }}</p></div>
        <label>输入接任人的完整邮箱以确认<input v-model="transferEmail" type="text" inputmode="email" autocomplete="off" placeholder="手动输入上方接任人邮箱" :disabled="transferBusy" /></label>
        <p class="form-error" role="alert">{{ transferError }}</p>
        <div class="admin-dialog-actions"><button type="button" class="btn btn-outline" :disabled="transferBusy" @click="transferPhase = 'choose'">返回修改</button><button class="btn btn-primary" :disabled="transferBusy || !recipient || transferEmail.trim().toLowerCase() !== recipient.email.toLowerCase()">{{ transferBusy ? '正在交接…' : '确认转交站长' }}</button></div>
      </form>
    </template>
    <template v-else><p class="form-error" role="alert">{{ transferError }}</p><button type="button" class="btn btn-outline" @click="loadTransfer">重新读取</button></template>
  </AdminDialog>

  <AdminDialog v-if="statusEditing" ref="statusDialog" labelledby="status-title" describedby="status-description" :busy="statusBusy" @close="closeStatus">
    <span class="kicker">{{ phase === 'confirm' ? 'CONFIRM CHANGE' : 'ACCOUNT STATUS' }}</span>
    <h2 id="status-title">{{ phase === 'confirm' ? '确认' : '' }}{{ actionText }}「{{ statusEditing.displayName }}」{{ phase === 'confirm' ? '？' : '' }}</h2>
    <p id="status-description" class="ua-dialog-account">{{ statusEditing.email }} · {{ statusEditing.role.name }}</p>
    <div class="ua-state-transition"><span>{{ statusText(statusEditing.accountStatus) }}</span><i>→</i><strong>{{ statusText(targetStatus) }}</strong></div>
    <ul v-if="targetStatus === 'DISABLED'" class="ua-effects"><li>无法登录或继续受保护操作，所有旧会话失效。</li><li>资料、角色和私人内容保留，已发布作品保持原可见范围。</li><li>仍可找回密码，但重置密码不会解除停用。</li></ul>
    <ul v-else class="ua-effects"><li>恢复账号使用资格，保留现有资料和角色。</li><li>需要重新登录，停用前的旧会话不会恢复。</li><li>不撤销其他独立的作品处置。</li></ul>
    <p v-if="statusBlocked" class="admin-warning" role="alert">{{ statusBlocked }}</p>
    <p v-if="alreadyChanged" class="admin-warning" role="status">最新状态已是目标状态，无需再次操作。原因仍保留，可关闭后核对记录。</p>
    <form v-if="phase === 'reason'" novalidate @submit.prevent="reviewStatus">
      <label class="ua-reason-label">{{ actionText }}原因 <span>必填 · 仅管理记录可见</span><textarea v-model="reason" rows="3" maxlength="220" :aria-invalid="!!statusError" aria-describedby="status-error" placeholder="说明本次操作的原因，便于后续追溯"></textarea><small :class="{ 'ua-over-limit': wordCount > 200 }">{{ wordCount }}/200</small></label>
      <p id="status-error" class="form-error" role="alert">{{ statusError }}</p>
      <div class="admin-dialog-actions"><button type="button" class="btn btn-outline" @click="closeStatus">取消</button><button class="btn btn-primary" :disabled="!!statusBlocked || alreadyChanged">下一步，核对影响</button></div>
    </form>
    <template v-else>
      <div class="ua-reason-review"><small>操作原因 · 仅管理记录可见</small><p>{{ reason.trim() }}</p></div>
      <p class="ua-review-note">确认后写入操作记录，可在“操作记录”中查询。</p>
      <p class="form-error" role="alert">{{ statusError }}</p>
      <div class="admin-dialog-actions"><button type="button" class="btn btn-outline" :disabled="statusBusy" @click="phase = 'reason'">返回修改</button><button type="button" class="btn" :class="targetStatus === 'DISABLED' ? 'ua-confirm-disable' : 'btn-primary'" :disabled="statusBusy" @click="saveStatus">{{ statusBusy ? '保存中…' : `确认${actionText}账号` }}</button></div>
    </template>
  </AdminDialog>

  <AdminDialog v-if="editing" labelledby="role-dialog-title" :busy="saving" wide @close="editing = null">
    <span class="kicker">CHANGE ROLE</span>
    <h2 id="role-dialog-title">调整「{{ editing.displayName }}」的角色</h2>
    <p>当前角色：<strong>{{ editing.role.name }}</strong>。保存后对方下一次请求即生效，不会删除其内容或私人草稿。</p>
    <form @submit.prevent="save">
      <fieldset class="role-options"><legend>目标角色</legend>
        <p v-if="!roles.length" class="form-error">角色列表读取失败，请关闭后重试。</p>
        <label v-for="r in roles.filter((r) => r.assignable)" :key="r.code" class="role-option" :class="{ selected: form.roleCode === r.code }"><input v-model="form.roleCode" type="radio" name="role" :value="r.code" /><span><strong>{{ r.name }}<em v-if="r.code === editing.role.code"> · 当前</em></strong><small>{{ r.description ? `${r.description}。` : '' }}可以：{{ roleSummary(r) }}</small></span></label>
      </fieldset>
      <p v-if="form.roleCode !== editing.role.code" class="role-diff">{{ editing.role.name }} → <strong>{{ roleName(form.roleCode) }}</strong></p>
      <p v-if="lastManager && !targetCanManage" class="admin-warning">这是最后一位能管理角色的用户，改为该角色会被拒绝。请先指定另一位管理员。</p>
      <p v-else-if="isSelf && !targetCanManage" class="admin-warning">你正在降低自己的权限，保存后将立即失去管理页面。</p>
      <label>备注（可选，写入操作记录）<textarea v-model="form.note" rows="2" maxlength="200" placeholder="例如：负责运维值班"></textarea></label>
      <p class="form-error" role="alert">{{ saveError }}</p>
      <div class="admin-dialog-actions"><button type="button" class="btn" :disabled="saving" @click="editing = null">取消</button><button class="btn btn-primary" :disabled="saving || form.roleCode === editing.role.code">{{ saving ? '保存中…' : '保存角色' }}</button></div>
    </form>
  </AdminDialog>
</template>
