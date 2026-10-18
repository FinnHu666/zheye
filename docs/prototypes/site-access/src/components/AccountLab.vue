<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { proto } from '../mock/server'
import { ensureAccount } from '../mock/account'
const router=useRouter(),open=ref(false),tab=ref('mail'),snapshot=ref(null)
const refresh=()=>{const d=proto.db();ensureAccount(d);snapshot.value=JSON.parse(JSON.stringify(d))}
onMounted(()=>{refresh();window.addEventListener('finns-proto-change',refresh);window.addEventListener('storage',refresh)})
onBeforeUnmount(()=>{window.removeEventListener('finns-proto-change',refresh);window.removeEventListener('storage',refresh)})
const account=computed(()=>snapshot.value?.account), current=computed(()=>snapshot.value?.users.find(u=>u.id===snapshot.value.session.userId))
const setting=(k,v)=>proto.update(d=>{ensureAccount(d)[k]=v})
const switchUser=(id)=>{proto.update(d=>{d.session.userId=id;const a=ensureAccount(d);if(id)a.devices[id].find(device=>device.current).active=true});window.dispatchEvent(new Event('finns-account-session'));router.push(id?'/me/security':'/auth/login');open.value=false}
const legacyScene=()=>{proto.update(d=>{ensureAccount(d);const u=d.users.find(u=>u.id===3);u.emailVerified=false;u.legacy=true;d.account.devices[3]?.forEach(device=>device.active=true)});switchUser(3)}
const expire=()=>{proto.update(d=>{d.session.userId=null});window.dispatchEvent(new Event('finns-account-session'))}
const advance=(n)=>{proto.advance(n*60000);refresh()}
const go=(path)=>{router.push(path);open.value=false}
const format=t=>new Date(t).toLocaleTimeString('zh-CN',{hour12:false})
</script>
<template>
  <button class="account-lab-toggle" :aria-expanded="open" @click="open=!open">✉ 账号演示 <span v-if="account?.mail.length">{{ account.mail.length }}</span></button>
  <aside v-if="open && account" class="account-lab" aria-label="账号原型演示工具"><header><div><strong>账号演示 · R2.1</strong><small>浏览器内模拟 · 不发真实邮件</small></div><button aria-label="关闭账号演示" @click="open=false">×</button></header><nav><button :class="{active:tab==='mail'}" @click="tab='mail'">演示收件箱</button><button :class="{active:tab==='scenes'}" @click="tab='scenes'">场景控制</button></nav>
    <section v-if="tab==='mail'"><p class="account-demo-note">只使用虚构账号。密码输入不保存；登录及原密码校验为模拟，不能据此证明真实认证安全。</p><p v-if="!account.mail.length" class="account-muted">还没有邮件。先注册、申请找回或在账号安全页补验证。</p><article v-for="mail in account.mail" :key="mail.id" class="account-mail"><small>{{ format(mail.at) }} · {{ mail.email }}</small><h3>{{ mail.title }}</h3><p>{{ mail.body }}</p><button v-if="mail.link" class="btn btn-primary" @click="go(mail.link)">打开模拟邮件链接 →</button></article></section>
    <section v-else><p class="account-muted">{{ current ? `${current.displayName} · ${current.email}`:'当前未登录' }}</p><div class="account-lab-actions"><button @click="switchUser(3)">切换阿远（{{ snapshot.users.find(u=>u.id===3)?.emailVerified ? '已验证':'未验证' }}）</button><button @click="legacyScene">载入旧账号未验证场景</button><button @click="switchUser(1)">站长（已验证）</button><button @click="switchUser(null)">切换游客</button><button @click="go('/auth/register')">新用户注册</button><button @click="go('/auth/forgot')">忘记密码</button><button :disabled="!current" @click="go('/me/security')">账号安全</button><button :disabled="!current" @click="go('/me/security/draft')">体验未保存保护</button></div>
    <label><input type="checkbox" :checked="account.failSend" @change="setting('failSend',$event.target.checked)"/>模拟发送失败（不生成邮件，保留旧链接）</label><label><input type="checkbox" :checked="account.wrongOld" @change="setting('wrongOld',$event.target.checked)"/>模拟原密码错误</label><label><input type="checkbox" :checked="account.failSave" @change="setting('failSave',$event.target.checked)"/>下一次密码保存失败</label><label><input type="checkbox" :checked="account.failNotice" @change="setting('failNotice',$event.target.checked)"/>模拟通知邮件失败（密码更新仍成功）</label>
    <label><input type="checkbox" :checked="account.failProfile" @change="setting('failProfile',$event.target.checked)"/>下一次资料保存失败</label>
    <div class="account-lab-actions"><button @click="advance(1)">快进1分钟（重发）</button><button @click="advance(31)">快进31分钟（重置过期）</button><button @click="advance(1441)">快进24小时（验证过期）</button><button @click="expire">让当前会话失效</button></div><p class="account-demo-note">时间快进后重新打开邮件链接，或重新进入页面。多设备状态是可见的模拟记录；没有真实会话撤销或真实并发保证。</p></section>
  </aside>
</template>
