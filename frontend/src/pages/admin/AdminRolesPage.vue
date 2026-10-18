<script setup>
import { computed, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { api } from '../../lib/api'
import { can, currentUser, refreshSession } from '../../lib/session'
import AdminLayout from '../../components/AdminLayout.vue'
import AdminDialog from '../../components/AdminDialog.vue'

const emit = defineEmits(['toast'])
const state = ref(null); const loading = ref(true); const error = ref('')
const editing = ref(false); const draft = ref({}); const note = ref(''); const saving = ref(false); const saveError = ref(''); const conflict = ref(false); const lastResult = ref('')
const canEdit = computed(() => can('admin.roles'))

const load = async () => {
  loading.value = true; error.value = ''
  try { state.value = await api('/api/admin/roles') } catch (e) { error.value = e.message } finally { loading.value = false }
}
onMounted(load)

// 锁定项：所有角色必须有浏览与个人资料；管理员角色必须保留三项管理权限；站长角色全部锁定（FINNS-16）；
// 非站长不能调整受保护角色（站长/管理员/含“管理角色”权限）也不能授予任何管理权限——服务端同样校验。
const isOwner = computed(() => currentUser.value?.role?.code === 'OWNER')
const protectedRole = (r) => r.systemKind === 'OWNER' || r.systemKind === 'ADMIN' || r.permissions.includes('admin.roles')
const locked = (role, perm) => perm.lockAll || role.systemKind === 'OWNER' || (perm.lockAdmin && role.systemKind === 'ADMIN') || (!isOwner.value && (protectedRole(role) || perm.code.startsWith('admin.')))
const has = (role, perm) => (editing.value ? draft.value[role.code] : role.permissions).includes(perm.code)
const original = (role, perm) => role.permissions.includes(perm.code)
const startEdit = () => { draft.value = Object.fromEntries(state.value.roles.map((r) => [r.code, [...r.permissions]])); note.value = ''; saveError.value = ''; conflict.value = false; lastResult.value = ''; editing.value = true }
const toggle = (role, perm) => {
  if (locked(role, perm)) return
  const list = draft.value[role.code]
  draft.value[role.code] = list.includes(perm.code) ? list.filter((k) => k !== perm.code) : [...list, perm.code]
}
const changes = computed(() => !editing.value || !state.value ? [] : state.value.roles.map((role) => ({
  role,
  added: state.value.catalog.filter((p) => draft.value[role.code].includes(p.code) && !role.permissions.includes(p.code)),
  removed: state.value.catalog.filter((p) => !draft.value[role.code].includes(p.code) && role.permissions.includes(p.code)),
})).filter((c) => c.added.length || c.removed.length))
const affectedUsers = computed(() => changes.value.reduce((sum, c) => sum + c.role.userCount, 0))
const cancel = () => { if (!changes.value.length || confirm('放弃未保存的权限修改？')) { editing.value = false; saveError.value = ''; conflict.value = false } }

const save = async () => {
  if (saving.value || !changes.value.length) return
  saving.value = true; saveError.value = ''; conflict.value = false
  const summary = changes.value.map((c) => `「${c.role.name}」${c.added.map((p) => ` +${p.label}`).join('')}${c.removed.map((p) => ` −${p.label}`).join('')}`).join('；')
  const affected = affectedUsers.value
  const permissions = Object.fromEntries(changes.value.map((c) => [c.role.code, draft.value[c.role.code]]))
  try {
    state.value = await api('/api/admin/roles', { method: 'PUT', body: JSON.stringify({ version: state.value.version, permissions, note: note.value }) })
    editing.value = false
    lastResult.value = `已保存：${summary}。影响 ${affected} 位用户，他们的下一次请求即按新权限处理。`
    emit('toast', '角色权限已保存')
    await refreshSession()
  } catch (e) {
    saveError.value = e.message // 未保存的勾选和备注保留
    conflict.value = /其他管理员/.test(e.message)
  } finally { saving.value = false }
}
const reloadLatest = async () => { editing.value = false; conflict.value = false; saveError.value = ''; await load() }

// 新增 / 编辑 / 删除角色
const dialog = ref(null) // { mode: 'create'|'edit'|'delete', role?, name, description, copyFrom, moveTo, note }
const dialogBusy = ref(false); const dialogError = ref('')
const atMax = computed(() => state.value && state.value.roles.length >= state.value.maxRoles)
const openCreate = () => { dialog.value = { mode: 'create', name: '', description: '', copyFrom: 'MEMBER', note: '' }; dialogError.value = ''; lastResult.value = '' }
const openEditRole = (role) => { dialog.value = { mode: 'edit', role, name: role.name, description: role.description || '', note: '' }; dialogError.value = ''; lastResult.value = '' }
const openDelete = (role) => { dialog.value = { mode: 'delete', role, moveTo: '', note: '' }; dialogError.value = ''; lastResult.value = '' }
const moveTargets = computed(() => dialog.value?.role ? state.value.roles.filter((r) => r.code !== dialog.value.role.code && r.assignable) : [])
const submitDialog = async () => {
  if (dialogBusy.value || !dialog.value) return
  const d = dialog.value; dialogBusy.value = true; dialogError.value = ''
  const base = { version: state.value.version, note: d.note }
  try {
    if (d.mode === 'create') {
      const result = await api('/api/admin/roles/definitions', { method: 'POST', body: JSON.stringify({ ...base, name: d.name, description: d.description, copyFrom: d.copyFrom }) })
      state.value = result
      lastResult.value = `已新增角色「${d.name.trim()}」${d.copyFrom ? `，权限复制自「${result.roles.find((r) => r.code === d.copyFrom)?.name}」` : '，只含基本权限'}。可在下方“修改权限”中调整，再到“用户与角色”给用户分配。`
    } else if (d.mode === 'edit') {
      state.value = await api(`/api/admin/roles/definitions/${d.role.code}`, { method: 'PATCH', body: JSON.stringify({ ...base, name: d.name, description: d.description }) })
      lastResult.value = d.name.trim() === d.role.name ? `已更新「${d.role.name}」的说明。` : `已将角色「${d.role.name}」改名为「${d.name.trim()}」，该角色的 ${d.role.userCount} 位用户同步显示新名称。`
    } else {
      state.value = await api(`/api/admin/roles/definitions/${d.role.code}`, { method: 'DELETE', body: JSON.stringify({ ...base, moveTo: d.moveTo }) })
      lastResult.value = `已删除角色「${d.role.name}」${d.role.userCount ? `，${d.role.userCount} 位用户已改为「${state.value.roles.find((r) => r.code === d.moveTo)?.name}」，下一次请求即按新角色处理` : ''}。`
    }
    dialog.value = null
    emit('toast', '角色已更新')
    await refreshSession()
  } catch (e) {
    dialogError.value = e.message // 填写内容保留
    if (/其他管理员/.test(e.message)) await load()
  } finally { dialogBusy.value = false }
}

onBeforeRouteLeave(() => !changes.value.length || confirm('权限修改尚未保存，确定离开？'))
</script>

<template>
  <AdminLayout title="角色权限" description="管理角色与可用权限，保存后在后续请求中生效。">
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="btn btn-outline" @click="load">重试</button></p>
    <div v-if="loading && !state" class="loading">正在加载角色权限…</div>
    <template v-else-if="state">
      <div class="section-heading roles-heading">
        <div><span class="kicker">ROLES</span><h2>角色</h2></div>
        <button v-if="canEdit" class="btn btn-outline" :disabled="editing || atMax" :title="atMax ? `最多 ${state.maxRoles} 个角色` : ''" @click="openCreate">＋ 新增角色</button>
      </div>
      <p v-if="lastResult && !editing" class="admin-result" role="status">{{ lastResult }}</p>
      <div class="role-cards">
        <article v-for="r in state.roles" :key="r.code" class="role-card">
          <span class="role-chip" :class="`role-${r.code.toLowerCase()}`">{{ r.name }}</span>
          <p v-if="r.description" class="role-desc">{{ r.description }}</p>
          <p>{{ r.userCount }} 位用户 · {{ (editing ? draft[r.code] : r.permissions).length }} 项权限<template v-if="r.systemKind === 'OWNER'"> · 唯一站长，使用专门入口转交</template><template v-else-if="r.systemKind === 'ADMIN'"> · 管理角色，不可删除</template><template v-else-if="r.systemKind === 'DEFAULT'"> · 注册默认，不可删除</template></p>
          <div v-if="canEdit && r.manageable" class="role-card-actions"><button type="button" class="text-link" :disabled="editing" @click="openEditRole(r)">编辑</button><button v-if="!r.systemKind" type="button" class="text-link danger-link" :disabled="editing" @click="openDelete(r)">删除</button></div>
        </article>
      </div>

      <div class="section-heading roles-heading">
        <div><span class="kicker">PERMISSIONS</span><h2>权限设置</h2></div>
        <button v-if="canEdit && !editing" class="btn btn-primary" @click="startEdit">修改权限</button>
        <span v-else-if="!canEdit" class="page-note">当前角色只能查看</span>
      </div>
      <p v-if="editing" class="page-note matrix-note">点击格子开启或关闭权限。带 🔒 的格子不可修改：保护管理入口不被锁死，并保留登录、退出和个人资料等基本能力。游客列只读。</p>

      <div class="matrix-wrap" tabindex="0" aria-label="角色权限矩阵，可横向滚动">
        <table class="role-matrix" :class="{ editing }">
          <thead><tr><th scope="col">权限</th><th scope="col">游客</th><th v-for="r in state.roles" :key="r.code" scope="col">{{ r.name }}</th></tr></thead>
          <tbody>
            <tr v-for="perm in state.catalog" :key="perm.code">
              <th scope="row"><small>{{ perm.group }}</small>{{ perm.label }}<em>{{ perm.detail }}</em></th>
              <td :class="state.guest.includes(perm.code) ? 'yes' : 'no'">{{ state.guest.includes(perm.code) ? '✓' : '—' }}</td>
              <td v-for="r in state.roles" :key="r.code" :class="[has(r, perm) ? 'yes' : 'no', { changed: editing && has(r, perm) !== original(r, perm) }]">
                <label v-if="editing" class="perm-cell" :class="{ locked: locked(r, perm) }">
                  <input type="checkbox" :checked="has(r, perm)" :disabled="locked(r, perm)" :aria-label="`${r.name}：${perm.label}`" @change="toggle(r, perm)" />
                  <span v-if="locked(r, perm)" aria-hidden="true">🔒</span>
                </label>
                <template v-else>{{ has(r, perm) ? '✓' : '—' }}</template>
              </td>
            </tr>
            <tr class="fixed-row"><th scope="row"><small>固定</small>他人私人草稿、完整 AI Key、本地工具<em>任何角色都不开放；私人数据另按归属检查，本地工具使用独立私人密钥</em></th><td v-for="n in state.roles.length + 1" :key="n" class="no">—</td></tr>
          </tbody>
        </table>
      </div>

      <form v-if="editing" class="roles-save" @submit.prevent="save">
        <div v-if="changes.length" class="roles-diff" aria-live="polite">
          <strong>待保存的修改（影响 {{ affectedUsers }} 位用户）</strong>
          <p v-for="c in changes" :key="c.role.code"><span class="role-chip" :class="`role-${c.role.code.toLowerCase()}`">{{ c.role.name }}</span><span v-for="p in c.added" :key="`a${p.code}`" class="perm-added">+ {{ p.label }}</span><span v-for="p in c.removed" :key="`r${p.code}`" class="perm-removed">− {{ p.label }}</span></p>
        </div>
        <p v-else class="page-note">还没有修改。</p>
        <label>备注（可选，写入操作记录）<textarea v-model="note" rows="2" maxlength="200" placeholder="例如：受限成员也可以收藏自选"></textarea></label>
        <p class="form-error" role="alert">{{ saveError }} <button v-if="conflict" type="button" class="btn btn-outline" @click="reloadLatest">载入最新设置（放弃我的修改）</button></p>
        <div class="admin-dialog-actions"><button type="button" class="btn" :disabled="saving" @click="cancel">放弃修改</button><button class="btn btn-primary" :disabled="saving || !changes.length">{{ saving ? '保存中…' : '保存权限' }}</button></div>
      </form>
    </template>
  </AdminLayout>

  <AdminDialog v-if="dialog" labelledby="role-def-title" :busy="dialogBusy" wide @close="dialog = null">
      <span class="kicker">{{ { create: 'NEW ROLE', edit: 'EDIT ROLE', delete: 'DELETE ROLE' }[dialog.mode] }}</span>
      <h2 id="role-def-title">{{ dialog.mode === 'create' ? '新增角色' : dialog.mode === 'edit' ? `编辑「${dialog.role.name}」` : `删除「${dialog.role.name}」` }}</h2>
      <form @submit.prevent="submitDialog">
        <template v-if="dialog.mode !== 'delete'">
          <label>角色名称<input v-model="dialog.name" maxlength="20" required placeholder="例如：内容审核员" /></label>
          <label>说明（可选）<input v-model="dialog.description" maxlength="60" placeholder="一句话说明这个角色的用途" /></label>
          <label v-if="dialog.mode === 'create'">权限起点<select v-model="dialog.copyFrom"><option v-for="r in state.roles.filter((r) => r.manageable)" :key="r.code" :value="r.code">复制「{{ r.name }}」的权限</option><option value="">只含基本权限（浏览公开内容、个人资料）</option></select></label>
          <p v-if="dialog.mode === 'edit' && dialog.name.trim() !== dialog.role.name && dialog.role.userCount" class="page-note">改名后，该角色的 {{ dialog.role.userCount }} 位用户会显示新名称；权限不变。</p>
        </template>
        <template v-else>
          <p v-if="dialog.role.userCount">这个角色下有 <strong>{{ dialog.role.userCount }} 位用户</strong>。删除前需要把他们转到另一个角色，转移后下一次请求即按新角色处理。</p>
          <p v-else>这个角色下没有用户，可以直接删除。</p>
          <label v-if="dialog.role.userCount">这些用户改为<select v-model="dialog.moveTo" required><option value="" disabled>选择角色…</option><option v-for="r in moveTargets" :key="r.code" :value="r.code">{{ r.name }}</option></select></label>
          <p class="admin-warning">删除后不能恢复；以前的操作记录保留原角色名称。</p>
        </template>
        <label>备注（可选，写入操作记录）<textarea v-model="dialog.note" rows="2" maxlength="200"></textarea></label>
        <p class="form-error" role="alert">{{ dialogError }}</p>
        <div class="admin-dialog-actions"><button type="button" class="btn" :disabled="dialogBusy" @click="dialog = null">取消</button><button class="btn" :class="dialog.mode === 'delete' ? 'btn-danger' : 'btn-primary'" :disabled="dialogBusy || (dialog.mode !== 'delete' && !dialog.name.trim()) || (dialog.mode === 'delete' && !!dialog.role.userCount && !dialog.moveTo)">{{ dialogBusy ? '处理中…' : dialog.mode === 'create' ? '新增角色' : dialog.mode === 'edit' ? '保存' : '删除角色' }}</button></div>
      </form>
  </AdminDialog>
</template>
