<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@finns/lib/api'
import AdminLayout from '../components/AdminLayout.vue'

const route = useRoute(); const router = useRouter()
const str = (value) => typeof value === 'string' ? value : ''
const blank = { q: '', type: '', role: '', from: '', to: '' }
const fromQuery = () => ({ q: str(route.query.q), type: str(route.query.type), role: str(route.query.role), from: str(route.query.from), to: str(route.query.to) })
const filters = ref(fromQuery()); const pageIndex = ref(Number(route.query.page || 0))
const result = ref(null); const loading = ref(true); const error = ref('')
const totalPages = computed(() => result.value ? Math.max(1, Math.ceil(result.value.total / result.value.size)) : 1)
const filtered = computed(() => Object.values(fromQuery()).some(Boolean))
const time = (value) => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
let requestId = 0

const load = async () => {
  const id = ++requestId; loading.value = true; error.value = ''
  const params = new URLSearchParams({ page: pageIndex.value, size: 10 })
  for (const [key, value] of Object.entries(fromQuery())) if (value) params.set(key, value)
  try { const data = await api(`/api/admin/audit?${params}`); if (id === requestId) result.value = data }
  catch (e) { if (id === requestId) error.value = e.message }
  finally { if (id === requestId) loading.value = false }
}
// 筛选条件写入地址：可直达、刷新、前进后退。
const apply = () => router.push({ query: Object.fromEntries(Object.entries(filters.value).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v)) })
const goPage = (n) => { const query = { ...route.query }; if (n) query.page = String(n); else delete query.page; router.push({ query }) }
const reset = () => { filters.value = { ...blank }; apply() }
watch(() => route.fullPath, () => { if (route.path !== '/admin/audit') return; filters.value = fromQuery(); pageIndex.value = Number(route.query.page || 0); load() })
// 角色选项来自服务端；已删除角色的记录仍可用关键词查到
const roles = ref([])
const typeLabel = { ROLE_ASSIGN: '角色分配', ROLE_PERMISSION: '权限修改', ROLE_DEFINE: '角色定义', ACCOUNT_STATUS: '账号状态', OWNER_TRANSFER: '站长转交' }
onMounted(async () => { load(); try { roles.value = (await api('/api/admin/roles')).roles } catch { roles.value = [] } })
</script>

<template>
  <AdminLayout title="操作记录" description="查询账号状态、角色及权限变化的原因与结果。">
    <form class="admin-toolbar audit-filters" role="search" @submit.prevent="apply()">
      <label class="admin-search">关键词<input v-model="filters.q" type="search" maxlength="80" placeholder="操作者、用户、权限或备注" /></label>
      <label>类型<select v-model="filters.type"><option value="">全部类型</option><option value="ACCOUNT_STATUS">账号停用与恢复</option><option value="OWNER_TRANSFER">站长身份转交</option><option value="ROLE_ASSIGN">用户角色分配</option><option value="ROLE_PERMISSION">角色权限修改</option><option value="ROLE_DEFINE">新增/改名/删除角色</option></select></label>
      <label>涉及角色<select v-model="filters.role"><option value="">全部角色</option><option v-for="r in roles" :key="r.key" :value="r.key">{{ r.label }}</option></select></label>
      <label>开始日期<input v-model="filters.from" type="date" :max="filters.to || undefined" /></label>
      <label>结束日期<input v-model="filters.to" type="date" :min="filters.from || undefined" /></label>
      <div class="audit-filter-actions"><button class="btn btn-primary">查询</button><button type="button" class="btn btn-outline" :disabled="!filtered" @click="reset">清除筛选</button></div>
    </form>
    <p v-if="result" class="page-note audit-count" aria-live="polite">{{ filtered ? `符合条件 ${result.total} 条` : `共 ${result.total} 条记录` }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }} <button type="button" class="btn btn-outline" @click="load">重试</button></p>
    <div v-if="loading && !result" class="loading">正在加载操作记录…</div>
    <div v-else-if="result && !result.items.length" class="empty-state">{{ filtered ? '没有符合条件的记录，试试放宽筛选条件。' : '还没有操作记录。' }}<br v-if="filtered" /><button v-if="filtered" type="button" class="btn btn-outline" @click="reset">清除筛选</button></div>
    <div v-else-if="result" class="personal-list" :aria-busy="loading">
      <article v-for="item in result.items" :key="item.id" class="personal-row audit-row">
        <div>
          <span class="post-tag">{{ time(item.at) }} · {{ item.operatorName }} · {{ typeLabel[item.type] }}</span>
          <h3 v-if="item.type === 'ROLE_PERMISSION'">「{{ item.roleLabel }}」角色权限</h3>
          <template v-else-if="item.type === 'ROLE_DEFINE'">
            <h3 v-if="item.action === 'CREATE'">新增角色「{{ item.roleLabel }}」</h3>
            <h3 v-else-if="item.action === 'UPDATE'">{{ item.beforeLabel === item.afterLabel ? `修改角色「${item.afterLabel}」的说明` : `角色改名：${item.beforeLabel} → ${item.afterLabel}` }}</h3>
            <h3 v-else>删除角色「{{ item.roleLabel }}」</h3>
            <p v-if="item.action === 'CREATE'">{{ item.copyFromLabel ? `权限复制自「${item.copyFromLabel}」` : '从基本权限开始' }}</p>
            <p v-if="item.action === 'DELETE'">{{ item.movedCount ? `${item.movedCount} 位用户改为「${item.moveToLabel}」：${item.movedNames.join('、')}${item.movedCount > item.movedNames.length ? ' 等' : ''}` : '该角色下没有用户' }}</p>
          </template>
          <template v-else-if="item.type === 'OWNER_TRANSFER'"><h3>站长转交：{{ item.ownerBeforeName }} → {{ item.ownerAfterName }}</h3><p>{{ item.outcome === 'SUCCESS' ? '原站长成为管理员，接任人成为唯一站长。' : '双方身份未因本次请求改变。' }}</p></template>
          <h3 v-else>{{ item.targetName }}：{{ item.beforeLabel }} → {{ item.afterLabel }}</h3>
          <p v-if="item.type === 'ROLE_PERMISSION'" class="audit-perm-diff"><span v-for="label in item.addedLabels" :key="`a${label}`" class="perm-added">+ {{ label }}</span><span v-for="label in item.removedLabels" :key="`r${label}`" class="perm-removed">− {{ label }}</span></p>
          <p v-if="item.note">{{ item.type === 'ACCOUNT_STATUS' ? '原因：' : '' }}{{ item.note }}</p>
          <p v-if="['ACCOUNT_STATUS', 'OWNER_TRANSFER'].includes(item.type)" class="ua-audit-result">{{ {SUCCESS:'成功',FAILED:'失败',REJECTED:'已拒绝'}[item.outcome] }} · {{ item.resultMessage }}</p>
        </div>
      </article>
    </div>
    <div v-if="result && totalPages > 1" class="pagination-control"><button class="btn btn-outline" :disabled="loading || pageIndex === 0" @click="goPage(pageIndex - 1)">上一页</button><span>第 {{ pageIndex + 1 }} / {{ totalPages }} 页</span><button class="btn btn-outline" :disabled="loading || pageIndex + 1 >= totalPages" @click="goPage(pageIndex + 1)">下一页</button></div>
  </AdminLayout>
</template>
