<script setup>
// 原型演示工具（不是产品功能）：载入样例、切换身份、制造异常。只改浏览器里的虚构数据。
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { proto } from '../mock/server'
import { ensureReview } from '../mock/review'
import { ensurePublishing, loadContentScene } from '../mock/publishing'
import { ensureCollections } from '../mock/collections'
import { simulateSignIn } from '../mock/user-admin'
const open = ref(false), settings = ref({}), identity = ref(''), router = useRouter(), route = useRoute()
function refresh() { const d = proto.db(); settings.value = { ...ensureReview(d).settings }; identity.value = d.users.find(u => u.id === d.session.userId)?.displayName || '游客' }
const emit = () => { window.dispatchEvent(new Event('finns-account-session')); window.dispatchEvent(new Event('finns-content-scene')) }
function go(id, path) { proto.update(() => simulateSignIn(id)); emit(); router.push(path); open.value = false }
function seed(path = '/me/posts') { proto.update(() => { loadContentScene(); ensureCollections(); simulateSignIn(3) }); emit(); router.push(path); open.value = false }
function set(key, value) { proto.update(d => { ensureReview(d).settings[key] = value }); refresh() }
const currentId = () => Number(route.params.id) || null
function otherWindow() {
  const id = currentId(); if (!id) return
  proto.update(d => { ensurePublishing(); const doc = d.review.documents.find(x => x.id === id); if (doc) doc.draft = { ...doc.draft, body: doc.draft.body + '\n（另一个窗口追加的一行）', version: doc.draft.version + 1 } }); emit()
}
function takeDown() {
  const id = currentId(); if (!id) return
  proto.update(d => { const doc = d.review.documents.find(x => x.id === id); if (doc?.published) doc.down = true }); emit()
}
function breakRef() { proto.update(d => { ensurePublishing(); const a = d.review.documents.find(x => x.id === 1803); if (a) a.withdrawn = true }); emit() }
function fixRef() { proto.update(d => { const a = d.review.documents.find(x => x.id === 1803); if (a) a.withdrawn = false }); emit() }
const retry = () => window.dispatchEvent(new Event('finns-content-retry'))
function stopAuthor() { proto.update(d => { d.users.find(u => u.id === 3).accountStatus = 'DISABLED' }); emit() }
onMounted(() => { refresh(); window.addEventListener('finns-proto-change', refresh) })
onBeforeUnmount(() => window.removeEventListener('finns-proto-change', refresh))
</script>
<template>
  <button class="rv-lab-toggle" :aria-expanded="open" @click="open = !open">◇ 创作演示 · R1</button>
  <aside v-if="open" class="proto-console rv-lab" aria-label="文章创作原型演示工具">
    <header><div><strong>创作演示 · R1</strong><small>浏览器虚构数据，不修改真实账号</small></div><button aria-label="关闭创作演示" @click="open = false">×</button></header>
    <section>
      <p class="pc-muted">当前：{{ identity }}。先载入样例，再以作者「阿远」体验；“别处”类按钮会作用于当前打开的那条内容。</p>
      <div class="pc-actions">
        <button @click="seed()">载入创作样例（阿远）</button>
        <button @click="seed('/me/posts?type=COLLECTION')">专题页签</button>
        <button @click="seed('/me/posts?type=SELECTED')">自选专题页签</button>
        <button @click="seed('/me/collections/1901/edit')">专题 · 三版本并存样例</button>
        <button @click="seed('/me/collections/1902/edit')">专题 · 未完成草稿（含失效引用）</button>
        <button @click="seed('/me/collections/1907/edit')">专题 · 高敏感自动不通过</button>
        <button @click="seed('/me/collections/1905/edit')">专题 · 已撤回</button>
        <button @click="seed('/me/collections/1906/edit')">专题 · 已被下架</button>
        <button @click="seed('/collections/1908')">专题 · 公开页（引用已失效占位）</button>
        <button @click="seed('/me/collections/new')">新建专题（模板）</button>
        <button @click="go(2, '/me/posts?type=SELECTED')">读者（周以宁）· 自选专题</button>
        <button @click="breakRef">让《我的博客写作工具》被撤回（制造失效引用）</button>
        <button @click="fixRef">恢复该文章公开</button>
        <button @click="seed('/me/posts/1801/edit')">三版本并存样例</button>
        <button @click="seed('/me/posts/1806/edit')">高敏感自动不通过样例</button>
        <button @click="seed('/me/posts/1805/edit')">已被下架样例</button>
        <button @click="seed('/me/posts/1804/edit')">已撤回样例</button>
        <button @click="seed('/me/posts/new')">新建文章</button>
        <button @click="go(1, '/admin/reviews')">站长 · 审批队列 / 恢复下架</button>
        <button @click="go(4, '/me/posts')">陈默（无创作权限）</button>
        <button @click="go(null, '/me/posts')">游客</button>
      </div>
      <details open>
        <summary>异常与并发场景</summary>
        <label class="pc-check"><input type="checkbox" :checked="settings.detectFailure" @change="set('detectFailure', $event.target.checked)" />敏感检测不可用</label>
        <label class="pc-check"><input type="checkbox" :checked="settings.failWriteOnce" @change="set('failWriteOnce', $event.target.checked)" />下一次保存/发布失败</label>
        <div class="pc-actions">
          <button @click="otherWindow">在另一个窗口更新当前草稿</button>
          <button @click="takeDown">在别处下架当前内容</button>
          <button @click="go(null, route.fullPath)">会话过期（退出）</button>
          <button @click="go(3, route.fullPath)">重新登录阿远</button>
          <button @click="go(4, route.fullPath)">换成其他账号</button>
          <button @click="stopAuthor">在别处停用阿远</button>
          <button @click="retry">重试上一次发布（同一提交标识）</button>
        </div>
      </details>
      <p class="pc-muted">正文试填：「演示低敏词」放行并留痕、「演示拦截词」送审、「演示高敏词」自动不通过并转人工复核；也可输入 &lt;script&gt;、![图](https://x/y.png)、[链接](javascript:alert(1)) 观察安全渲染。</p>
    </section>
  </aside>
</template>
