<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { proto } from '../mock/server'
import { ensureUserAdmin, simulateSignIn, changeAccountStatus } from '../mock/user-admin'
const route = useRoute(), router = useRouter(), open = ref(false), snapshot = ref(null), message = ref(''), captured = ref(null)
const refresh = () => { const d = proto.db(); ensureUserAdmin(d); snapshot.value = JSON.parse(JSON.stringify(d)) }
const settings = computed(() => snapshot.value?.userAdmin || {})
const target = computed(() => snapshot.value?.users.find(u => u.id === 3))
const probe = computed(() => !captured.value ? '尚未记录' : target.value?.accountStatus === 'DISABLED' || target.value?.sessionEpoch !== captured.value.epoch ? '拒绝 · 旧会话已失效' : '允许 · 当前演示会话有效')
const emit = () => { window.dispatchEvent(new Event('finns-account-session')); window.dispatchEvent(new Event('finns-user-admin-scene')) }
const set = (key, value) => proto.update(d => { ensureUserAdmin(d)[key] = value })
function sample(lastManager = false) {
  proto.update(d => {
    ensureUserAdmin(d)
    if (lastManager && !d.roles.some(r => r.key === 'USER_OPERATOR')) d.roles.push({ key: 'USER_OPERATOR', label: '用户管理员', description: '可管理用户与查看记录，无管理角色权限' })
    d.permissions.USER_OPERATOR = ['content.public', 'profile.manage', 'admin.users', 'admin.audit']
    for (const id of [1, 2, 3, 4]) { const u = d.users.find(u => u.id === id); u.accountStatus = 'ACTIVE'; u.statusVersion += 1; u.sessionEpoch += 1 }
    for (const u of d.users) if (u.role === 'OWNER') u.role = 'ADMIN'
    d.siteOwnerId = 1
    const ownerVersion = (d.userAdmin.ownerVersion || 1) + 1
    d.users.find(u => u.id === 1).role = 'OWNER'
    d.users.find(u => u.id === 2).role = 'ADMIN'
    d.users.find(u => u.id === 3).role = 'MEMBER'
    d.users.find(u => u.id === 4).role = 'ADMIN'
    d.userAdmin = { ownerModelVersion: 1, ownerVersion, failSaveOnce: false, failLoadOnce: false, slowList: false, conflictOnce: false }
    simulateSignIn(lastManager ? 2 : 1)
  })
  message.value = lastManager ? '当前是管理员周以宁：可管理普通成员，不能管理站长和其他管理员。' : '站长为唯一所有者，可在页面转交；周以宁、陈默为管理员；阿远为普通成员。'
  captured.value = null; emit(); router.push('/admin/users')
}
function switchTo(id, path) {
  proto.update(() => simulateSignIn(id)); emit(); router.push(path); open.value = false
}
function editDraft() {
  if (target.value?.accountStatus === 'DISABLED') { message.value = '请先恢复阿远，再进入演示稿。'; return }
  switchTo(3, '/me/security/draft')
}
function changeElsewhere(status) {
  try {
    proto.update(d => {
      const old = { ...d.session }; simulateSignIn(d.siteOwnerId)
      try { changeAccountStatus(3, { status, expectedVersion: d.users.find(u => u.id === 3).statusVersion, note: '原型场景：另一位管理者在别处操作' }) }
      finally { d.session = old }
    })
    message.value = status === 'DISABLED' ? '阿远已在别处被停用；演示稿应保留，旧会话被拒绝。' : '阿远已恢复；旧会话仍失效，需要原账号重新登录。'
    emit()
  } catch (e) { message.value = e.message }
}
function capture() { captured.value = { epoch: target.value.sessionEpoch }; message.value = '已记录阿远两台模拟设备的当前会话版本。' }
const visibleCurrent = computed(() => snapshot.value?.users.find(u => u.id === snapshot.value.session.userId))
onMounted(() => { refresh(); window.addEventListener('finns-proto-change', refresh) })
onBeforeUnmount(() => window.removeEventListener('finns-proto-change', refresh))
</script>
<template>
  <button class="ua-lab-toggle" :class="{ 'ua-on-admin': route.path.startsWith('/admin') }" :aria-expanded="open" @click="open=!open">◇ 用户管理演示 · R3</button>
  <aside v-if="open && snapshot" class="proto-console ua-lab" :class="{ 'ua-on-admin-panel': route.path.startsWith('/admin') }" aria-label="用户管理原型演示工具">
    <header><div><strong>用户管理演示 · R3</strong><small>仅浏览器模拟，不修改真实账号</small></div><button aria-label="关闭用户管理演示" @click="open=false">×</button></header>
    <section>
      <p class="pc-muted">当前演示身份：{{ visibleCurrent?.displayName || '游客' }}。场景按钮是原型工具，不是产品管理入口。</p>
      <div class="pc-actions"><button @click="sample(false)">载入站长与管理员样例</button><button @click="sample(true)">切换管理员体验边界</button></div>
      <div class="pc-actions"><button @click="switchTo(snapshot.siteOwnerId,'/admin/users')">切换当前站长</button><button @click="switchTo(3,'/admin/users')">普通用户直达管理页</button><button @click="switchTo(null,'/admin/users')">游客直达管理页</button></div>
      <label class="pc-check"><input type="checkbox" :checked="settings.failLoadOnce" @change="set('failLoadOnce',$event.target.checked)"/>下一次列表读取失败</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.slowList" @change="set('slowList',$event.target.checked)"/>列表慢加载</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.failSaveOnce" @change="set('failSaveOnce',$event.target.checked)"/>下一次状态保存或转交失败</label>
      <label class="pc-check"><input type="checkbox" :checked="settings.conflictOnce" @change="set('conflictOnce',$event.target.checked)"/>下一次提交发生版本冲突</label>
      <label class="pc-check"><input type="checkbox" :checked="snapshot.settings.dropCsrfOnce" @change="proto.update(d=>d.settings.dropCsrfOnce=$event.target.checked)"/>下一次写请求缺少有效CSRF</label>
      <details><summary>会话与未提交内容</summary><p class="pc-muted">模拟设备探针不是独立真实登录。阿远当前：{{ target?.accountStatus === 'DISABLED' ? '停用' : '正常' }}。</p>
        <div class="pc-actions"><button @click="capture">记录阿远两台旧会话</button><button @click="editDraft">以阿远打开演示稿</button><button @click="changeElsewhere('DISABLED')">在别处停用阿远</button><button @click="changeElsewhere('ACTIVE')">在别处恢复阿远</button></div>
        <div class="ua-probe-list"><span>模拟电脑：{{ probe }}</span><span>模拟手机：{{ probe }}</span></div>
      </details>
      <details><summary>登录与密码找回</summary><p class="pc-muted">密码校验为模拟，不保存输入。开启“密码错误”后，停用账号也只显示统一登录失败。</p><label class="pc-check"><input type="checkbox" :checked="snapshot.account?.wrongLogin" @change="proto.update(d=>d.account.wrongLogin=$event.target.checked)"/>模拟密码错误</label><div class="pc-actions"><button @click="router.push('/auth/login');open=false">打开登录</button><button @click="router.push('/auth/forgot');open=false">打开密码找回</button><button @click="router.push('/collections/102');open=false">查看阿远已发布专题</button></div><p class="pc-muted">邮件用右下角“账号演示”的模拟收件箱打开。重置后停用状态不变。</p></details>
      <p v-if="message" class="pc-probe" role="status">{{ message }}</p>
    </section>
  </aside>
</template>
