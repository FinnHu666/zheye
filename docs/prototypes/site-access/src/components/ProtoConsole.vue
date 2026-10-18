<!-- 原型控制台：只用于体验与评审，不是 FINNS 产品功能。 -->
<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { proto } from '../mock/server'
import { resetDb } from '../mock/db'
import { roleLabel as fallbackLabel } from '../mock/roles'
import { siteVisit } from '../proto/session'

const open = ref(false); const tab = ref('identity'); const snapshot = ref(proto.db()); const visitorId = ref(proto.visitorId()); const probe = ref('')
const refresh = () => { snapshot.value = JSON.parse(JSON.stringify(proto.db())); visitorId.value = proto.visitorId() }
const onStorage = (event) => { if (!event.key || event.key.startsWith('finns-proto')) refresh() }
onMounted(() => { window.addEventListener('finns-proto-change', refresh); window.addEventListener('storage', onStorage) })
onBeforeUnmount(() => { window.removeEventListener('finns-proto-change', refresh); window.removeEventListener('storage', onStorage) })

const roles = computed(() => snapshot.value.roles || [])
const roleLabel = (key) => roles.value.find((r) => r.key === key)?.label || fallbackLabel(key)
const current = computed(() => snapshot.value.users.find((u) => u.id === snapshot.value.session.userId) || null)
const quickUsers = computed(() => ['ADMIN', 'TRUSTED', 'MEMBER', 'RESTRICTED'].map((role) => snapshot.value.users.find((u) => u.role === role)).filter(Boolean))
const settings = computed(() => snapshot.value.settings)
const visitorState = computed(() => snapshot.value.visits.visitors[visitorId.value] || null)
const minutesSince = computed(() => visitorState.value?.lastSeen ? Math.round((Date.now() + snapshot.value.clockOffset - visitorState.value.lastSeen) / 60000) : null)
const clockLabel = computed(() => { const m = Math.round(snapshot.value.clockOffset / 60000); return m ? `模拟时钟已快进 ${m >= 1440 ? `${Math.floor(m / 1440)} 天 ` : ''}${m % 1440} 分钟` : '模拟时钟 = 实际时间' })

const switchTo = (id) => { proto.update((d) => { d.session.userId = id }); location.reload() }
const set = (key, value) => proto.update((d) => { d.settings[key] = value })
const advance = (minutes) => proto.advance(minutes * 60000)
const reloadPage = () => location.reload()
const newDevice = () => { proto.clearVisitor(); location.reload() }
const zero = () => { proto.update((d) => { const t = new Date(); t.setHours(0, 0, 0, 0); d.visits.total = 0; d.visits.since = t.toISOString(); d.visits.visitors = {} }); location.reload() }
const reset = () => { if (confirm('重置全部演示数据（用户、角色、访问量、日志）？')) { resetDb(); proto.clearVisitor(); location.reload() } }
const demoteCurrent = (role) => proto.update((d) => {
  const target = d.users.find((u) => u.id === d.session.userId); if (!target || target.role === role) return
  d.audit.unshift({ id: Date.now(), type: 'ROLE_ASSIGN', at: new Date().toISOString(), operatorId: d.users.find((u) => u.role === 'ADMIN' && u.id !== target.id)?.id || 1, targetId: target.id, before: target.role, after: role, note: '原型演示：另一位管理员在别处调整' })
  target.role = role
})
const csrf = () => document.cookie.split('; ').find((p) => p.startsWith('XSRF-TOKEN='))?.slice(11) || ''
const callAdminApi = async () => {
  const res = await fetch('/api/admin/users?page=0&size=10'); const body = await res.json().catch(() => ({}))
  probe.value = `GET /api/admin/users → ${res.status}${res.ok ? `（返回 ${body.items?.length} 个用户）` : `：${body.message}`}`
}
const callRoleApi = async () => {
  const res = await fetch('/api/admin/users/3/role', { method: 'PATCH', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf()) }, body: JSON.stringify({ role: 'ADMIN' }) })
  const body = await res.json().catch(() => ({}))
  probe.value = `PATCH /api/admin/users/3/role {role:ADMIN} → ${res.status}${res.ok ? '（已修改）' : `：${body.message}`}`
}
const registerWithRole = async () => {
  await fetch('/api/auth/csrf')
  const email = `test${Date.now() % 100000}@finns.test`
  const res = await fetch('/api/auth/register', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf()) }, body: JSON.stringify({ email, password: 'demo-only', confirmPassword: 'demo-only', displayName: '提权测试', role: 'ADMIN' }) })
  const body = await res.json().catch(() => ({}))
  const created = proto.db().users.find((u) => u.email === email)
  probe.value = `注册时提交 role=ADMIN → ${res.status}，实际角色：${created ? roleLabel(created.role) : body.message}；未提供已验证邮箱凭据时不能直接注册或提权。`
}

const decisions = [
  ['V1', '一次“网站访问”', '同一浏览器 30 分钟无活动后再进入才算新访问（可在“浏览量”页切换 60 分钟 / 自然日对比）'],
  ['V2', '不重复计数', '刷新、新标签页、站内切换、打开文章/专题/弹层都不另计；换浏览器/清除 Cookie 记为新访问'],
  ['V3', '管理员自己的访问', '照常计入（简单、可解释）；也可选不计入'],
  ['V4', '统计起点与显示', '从功能上线当天起累计、不补历史；首页只显示第 4 个数字“累计访问”（R2 按要求删除下方说明文字，起点仅在悬停提示中）'],
  ['V5', '失败表现', '读取失败显示“—”和提示，不显示 0；上报失败静默，不影响浏览'],
  ['R1', '角色清单', '受限成员 / 普通成员 / 受信成员 / 管理员；每人一个角色；注册默认普通成员'],
  ['R2', '角色可配置性', '已确认：管理员可修改各角色权限（R2）。R3 新增：可新增（可复制已有角色权限）、改名/改说明、删除角色；删除前须把该角色的用户转到其他角色；管理员与注册默认角色不能删除；最多 12 个角色'],
  ['R3', '内容范围', '专题新增“公开/受限”可见范围；受限专题仅受信成员和管理员可读，对其他人在列表、总数、自选和统计中都不出现'],
  ['R4', '无权限直达', '已登录：保留地址显示 403 页；未登录：先登录；接口一律返回 401/403'],
  ['R5', '管理员保护', '最后一位管理员不能降级；首个管理员由部署配置（邮箱）指定，不在页面创建'],
  ['R6', '操作记录', '记录成功的角色分配和权限修改；支持关键词、类型、涉及角色、日期范围筛选与分页；被拒绝的尝试只提示不入库'],
  ['R7', '受限成员能力', '可浏览公开内容、个人资料、我的内容、自选；不能发布、评论、点赞、建专题、用 AI'],
]
</script>

<template>
  <button class="proto-fab" type="button" :aria-expanded="open" @click="open = !open; refresh()">原型控制台<small>{{ current ? roleLabel(current.role) : '游客' }}</small></button>
  <aside v-if="open" class="proto-console" aria-label="原型控制台（非产品功能）">
    <header><div><strong>原型控制台</strong><small>0.7.0 · FINNS-10/11 · 模拟数据，不是产品功能</small></div><button type="button" aria-label="关闭控制台" @click="open = false">×</button></header>
    <nav role="tablist"><button v-for="[key, label] in [['identity','身份'],['visits','浏览量'],['security','权限演练'],['log','请求日志'],['decide','待确认']]" :key="key" role="tab" :aria-selected="tab === key" :class="{ active: tab === key }" @click="tab = key; refresh()">{{ label }}</button></nav>

    <section v-if="tab === 'identity'">
      <p class="pc-muted">切换身份相当于换成另一个人的浏览器登录（页面会刷新）。也可以用顶部“登录”输入下列邮箱，密码任意。</p>
      <button class="pc-item" :class="{ on: !current }" @click="switchTo(null)"><strong>游客</strong><small>未登录</small></button>
      <button v-for="u in quickUsers" :key="u.id" class="pc-item" :class="{ on: current?.id === u.id }" @click="switchTo(u.id)"><strong>{{ u.displayName }} · {{ roleLabel(u.role) }}</strong><small>{{ u.email }}</small></button>
      <p class="pc-muted">建议路径：受限成员打开 /me/collections 或“内部：运维值班手册”；管理员到“管理”调整角色后再切回对方。</p>
    </section>

    <section v-if="tab === 'visits'">
      <dl class="pc-facts"><dt>累计访问</dt><dd>{{ snapshot.visits.total.toLocaleString('zh-CN') }}</dd><dt>本次进入</dt><dd>{{ siteVisit.counted === null ? '上报失败或未完成' : siteVisit.counted ? '已计 +1' : '未计' }}<small>{{ siteVisit.reason }}</small></dd><dt>浏览器标识</dt><dd>{{ visitorId || '（尚未下发）' }}<small>模拟服务端第一方 Cookie</small></dd><dt>距上次活动</dt><dd>{{ minutesSince === null ? '—' : `${minutesSince} 分钟` }}<small>{{ clockLabel }}</small></dd></dl>
      <label class="pc-field">一次访问的判定<select :value="settings.rule" @change="set('rule', $event.target.value)"><option value="idle30">30 分钟无活动后算新访问（推荐）</option><option value="idle60">60 分钟无活动后算新访问</option><option value="day">每个浏览器每天一次</option></select></label>
      <div class="pc-actions"><button @click="reloadPage">刷新页面</button><a class="pc-btn" href="/" target="_blank" rel="opener">打开新标签页</a><button @click="advance(31); reloadPage()">31 分钟后再进入</button><button @click="advance(1440); reloadPage()">次日再进入</button><button @click="newDevice">换浏览器 / 清除 Cookie</button></div>
      <label class="pc-check"><input type="checkbox" :checked="settings.excludeAdmin" @change="set('excludeAdmin', $event.target.checked)" />管理员访问不计入</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.slowStats" @change="set('slowStats', $event.target.checked)" />读取访问量较慢（看加载状态）</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.failVisitRead" @change="set('failVisitRead', $event.target.checked)" />读取访问量失败</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.failVisitWrite" @change="set('failVisitWrite', $event.target.checked)" />上报访问失败（网站照常可用）</label>
      <div class="pc-actions"><button @click="zero">模拟刚启用（计数为 0）</button></div>
      <p class="pc-muted">勾选后点“刷新页面”查看首页效果。原型不证明并发计数、持久化与重启恢复，这些在实现阶段测试。</p>
    </section>

    <section v-if="tab === 'security'">
      <p class="pc-muted">当前：{{ current ? `${current.displayName} · ${roleLabel(current.role)}` : '游客' }}</p>
      <div class="pc-actions"><button @click="callAdminApi">直接请求管理接口</button><button @click="callRoleApi">直接给阿远提权为管理员</button><button @click="registerWithRole">注册时提交 role=ADMIN</button></div>
      <p v-if="probe" class="pc-probe" role="status">{{ probe }}</p>
      <label class="pc-field">另一位管理员把我改为<select @change="demoteCurrent($event.target.value); $event.target.value = ''" :disabled="!current"><option value="">选择角色…</option><option v-for="r in roles" :key="r.key" :value="r.key">{{ r.label }}</option></select></label>
      <p class="pc-muted">改完后不刷新，直接点导航或按钮，下一次请求即按新角色处理。</p>
      <label class="pc-check"><input type="checkbox" :checked="settings.dropCsrfOnce" @change="set('dropCsrfOnce', $event.target.checked)" />下一次写请求缺少 CSRF 令牌</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.failRoleSaveOnce" @change="set('failRoleSaveOnce', $event.target.checked)" />下一次保存角色失败</label>
      <div class="pc-actions"><button @click="proto.update((d) => { d.session.userId = null })">让当前会话过期</button><button class="danger" @click="reset">重置演示数据</button></div>
    </section>

    <section v-if="tab === 'log'">
      <p class="pc-muted">模拟后端收到的请求（最近 60 条）：看前端隐藏按钮之外，接口本身是否拒绝。</p>
      <ol class="pc-log"><li v-for="(entry, i) in snapshot.log" :key="i" :class="entry.status >= 400 ? 'bad' : ''"><code>{{ entry.at }} {{ entry.method }} {{ entry.path }}</code><b>{{ entry.status }}</b><small>{{ entry.role }}{{ entry.note ? ` · ${entry.note}` : '' }}</small></li></ol>
    </section>

    <section v-if="tab === 'decide'">
      <p class="pc-muted">以下是原型采用的草案参数，需要你在聊天里确认或修改；这里不提供“确认”按钮。</p>
      <ol class="pc-decisions"><li v-for="[id, title, value] in decisions" :key="id"><b>{{ id }}</b><div><strong>{{ title }}</strong><p>{{ value }}</p></div></li></ol>
    </section>
  </aside>
</template>
