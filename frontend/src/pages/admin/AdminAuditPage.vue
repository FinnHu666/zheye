<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../../lib/api'
import AdminNav from '../../components/AdminNav.vue'

const route = useRoute(); const router = useRouter()
const str = (value) => typeof value === 'string' ? value : ''
const blank = { q: '', type: '', role: '', from: '', to: '' }
const fromQuery = () => ({ q: str(route.query.q), type: str(route.query.type), role: str(route.query.role), from: str(route.query.from), to: str(route.query.to) })
const filters = ref(fromQuery()); const pageIndex = ref(Number(route.query.page || 0))
const result = ref(null); const loading = ref(true); const error = ref('')
const roles = ref([])
const typeLabel = { ROLE_ASSIGN: '角色分配', ROLE_PERMISSION: '权限修改', ROLE_DEFINE: '角色定义' }
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
onMounted(async () => { load(); try { roles.value = (await api('/api/admin/roles')).roles } catch { roles.value = [] } })
</script>

<template>
  <section class="page-hero personal-hero"><span class="kicker">ADMIN</span><h1>角色<br /><em>操作记录。</em></h1><p>记录用户角色分配、角色权限修改与角色新增、改名、删除：操作者、对象、时间、前后变化和备注。</p></section>
  <section class="section personal-section admin-section">
    <AdminNav />
    <form class="admin-toolbar audit-filters" role="search" @submit.prevent="apply">
      <label class="admin-search">关键词<input v-model="filters.q" type="search" maxlength="80" placeholder="操作者、用户、权限或备注" /></label>
      <label>类型<select v-model="filters.type"><option value="">全部类型</option><option value="ROLE_ASSIGN">用户角色分配</option><option value="ROLE_PERMISSION">角色权限修改</option><option value="ROLE_DEFINE">新增/改名/删除角色</option></select></label>
      <label>涉及角色<select v-model="filters.role"><option value="">全部角色</option><option v-for="r in roles" :key="r.code" :value="r.code">{{ r.name }}</option></select></label>
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
          <span class="post-tag">{{ time(item.createdAt) }} · {{ item.operatorName }} · {{ typeLabel[item.type] }}</span>
          <template v-if="item.type === 'ROLE_PERMISSION'">
            <h3>「{{ item.roleName }}」角色权限</h3>
            <p class="audit-perm-diff"><span v-for="label in item.addedPermissions" :key="`a${label}`" class="perm-added">+ {{ label }}</span><span v-for="label in item.removedPermissions" :key="`r${label}`" class="perm-removed">− {{ label }}</span></p>
          </template>
          <template v-else-if="item.type === 'ROLE_DEFINE'">
            <h3 v-if="item.action === 'CREATE'">新增角色「{{ item.roleName }}」</h3>
            <h3 v-else-if="item.action === 'UPDATE'">{{ item.beforeRoleName === item.afterRoleName ? `修改角色「${item.afterRoleName}」的说明` : `角色改名：${item.beforeRoleName} → ${item.afterRoleName}` }}</h3>
            <h3 v-else>删除角色「{{ item.roleName }}」</h3>
            <p v-if="item.action === 'CREATE'">{{ item.copyFromName ? `权限复制自「${item.copyFromName}」` : '从基本权限开始' }}</p>
            <p v-if="item.action === 'DELETE'">{{ item.movedCount ? `${item.movedCount} 位用户改为「${item.moveToName}」：${item.movedNames.join('、')}${item.movedCount > item.movedNames.length ? ' 等' : ''}` : '该角色下没有用户' }}</p>
          </template>
          <h3 v-else>{{ item.targetUserName }}：{{ item.beforeRoleName || '无' }} → {{ item.afterRoleName }}</h3>
          <p v-if="item.note">{{ item.note }}</p>
        </div>
      </article>
    </div>
    <div v-if="result && totalPages > 1" class="pagination-control"><button class="btn btn-outline" :disabled="loading || pageIndex === 0" @click="goPage(pageIndex - 1)">上一页</button><span>第 {{ pageIndex + 1 }} / {{ totalPages }} 页</span><button class="btn btn-outline" :disabled="loading || pageIndex + 1 >= totalPages" @click="goPage(pageIndex + 1)">下一页</button></div>
  </section>
</template>
