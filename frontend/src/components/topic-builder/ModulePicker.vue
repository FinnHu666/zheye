<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { api } from '../../lib/api'
const emit = defineEmits(['close', 'insert'])
const dialog = ref(null), tab = ref('basic'), items = ref([]), blocks = ref([]), selected = ref(null)
const page = ref(0), hasNext = ref(false), busy = ref(false), error = ref('')
const names = { text: '文字说明', steps: '步骤', question: '问题解答', html: 'HTML + CSS' }
let run = 0, controller
async function load(next = 0) {
  const current = ++run; controller?.abort(); controller = new AbortController()
  busy.value = true; error.value = ''; blocks.value = []; selected.value = null; items.value = []
  try {
    const path = tab.value === 'topics' ? '/api/me/collections?editorType=BUILDER' : '/api/me/topic-builder/module-favorites?'
    const result = await api(`${path}&page=${next}&size=12`, { signal: controller.signal })
    if (run !== current) return
    items.value = result.items; page.value = next; hasNext.value = result.hasNext ?? result.hasMore
  } catch (e) { if (current === run && e.name !== 'AbortError') error.value = e.message }
  finally { if (current === run) busy.value = false }
}
function chooseTab(value) {
  ++run; controller?.abort(); tab.value = value; page.value = 0; error.value = ''; selected.value = null; blocks.value = []; items.value = []; busy.value = false
  if (value === 'topics' || value === 'favorites') load()
}
async function openTopic(topic) {
  const current = ++run; controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''; blocks.value = []; selected.value = topic
  try {
    const result = await api(`/api/me/collections/${topic.id}/builder-document`, { signal: controller.signal })
    if (current === run) blocks.value = result.document.blocks
  } catch (e) { if (current === run && e.name !== 'AbortError') error.value = e.message }
  finally { if (current === run) busy.value = false }
}
async function remove(id) {
  busy.value = true; error.value = ''
  try { await api(`/api/me/topic-builder/module-favorites/${id}`, { method: 'DELETE' }); await load(page.value) }
  catch (e) { error.value = e.message; busy.value = false }
}
onMounted(() => dialog.value.showModal())
onBeforeUnmount(() => { ++run; controller?.abort() })
</script>
<template>
  <dialog ref="dialog" class="module-picker" aria-labelledby="module-picker-title" @cancel.prevent="emit('close')">
    <header><div><span class="picker-kicker">让内容成为积木</span><h2 id="module-picker-title">添加内容区块</h2><p>挑选一个模块，放进你的专题。插入后可独立修改。</p></div><button type="button" class="picker-close" aria-label="关闭模块选择" @click="emit('close')">×</button></header>
    <nav aria-label="模块来源"><button v-for="item in [{id:'basic',name:'基础模块'},{id:'topics',name:'本人专题'},{id:'favorites',name:'我的收藏'},{id:'market',name:'模块市场'}]" :key="item.id" type="button" :aria-pressed="tab === item.id" @click="chooseTab(item.id)">{{ item.name }}</button></nav>
    <p v-if="error" class="picker-error" role="alert">{{ error }}</p>
    <div class="picker-content">
      <div v-if="tab === 'basic'" class="picker-grid"><button v-for="kind in ['text','steps','question']" :key="kind" class="picker-card" type="button" @click="emit('insert', {kind,title:names[kind],body:''})"><strong>{{ names[kind] }}</strong><span>{{ {text:'记录说明与经验',steps:'把行动拆成清晰步骤',question:'整理问题与回答'}[kind] }}</span><small>＋ 添加模块</small></button></div>
      <template v-else-if="tab === 'topics'">
        <template v-if="selected"><button type="button" class="back-button" @click="load(page)">← 返回专题列表</button><h3>{{ selected.name }}</h3><div class="picker-grid"><button v-for="block in blocks" :key="block.id" type="button" class="picker-card" @click="emit('insert', block)"><small>{{ names[block.kind] }}</small><strong>{{ block.title || '未命名内容' }}</strong><span>{{ block.kind === 'html' ? '自定义 HTML + CSS 模块' : block.body.slice(0,90) }}</span><small>＋ 复制到当前页面</small></button></div><p v-if="!busy && !blocks.length">这个专题还没有模块。</p></template>
        <template v-else><div class="picker-grid"><button v-for="topic in items" :key="topic.id" type="button" class="picker-card" @click="openTopic(topic)"><strong>{{ topic.name }}</strong><span>{{ topic.itemCount }} 个模块</span><small>选择专题 →</small></button></div><p v-if="!busy && !items.length">还没有已保存的 AI 专题。先保存工作稿，即可复用其中的模块。</p></template>
      </template>
      <template v-else-if="tab === 'favorites'"><div class="picker-grid"><article v-for="item in items" :key="item.id" class="picker-card"><small>{{ names[item.block.kind] }}</small><strong>{{ item.block.title || '未命名内容' }}</strong><span>{{ item.block.kind === 'html' ? '自定义 HTML + CSS 模块' : item.block.body.slice(0,90) }}</span><div class="card-actions"><button type="button" :disabled="busy" @click="emit('insert', item.block)">插入模块</button><button type="button" :disabled="busy" @click="remove(item.id)">取消收藏</button></div></article></div><p v-if="!busy && !items.length">还没有收藏。在工作台选中一个模块，点击“收藏此模块”。</p></template>
      <div v-else class="picker-grid"><article v-for="name in ['时间轴','经验清单','知识问答']" :key="name" class="picker-card market-example"><small>示例／暂未开放</small><strong>{{ name }}</strong><span>模块市场将在后续迭代开放。</span></article></div>
      <p v-if="busy" role="status">正在读取…</p>
    </div>
    <footer v-if="['topics','favorites'].includes(tab) && !selected"><button type="button" :disabled="busy || page === 0" @click="load(page-1)">上一页</button><span>第 {{ page+1 }} 页</span><button type="button" :disabled="busy || !hasNext" @click="load(page+1)">下一页</button></footer>
  </dialog>
</template>
<style scoped>
.module-picker{width:min(900px,calc(100% - 32px));max-height:85dvh;padding:0;border:1px solid #dce2d7;border-radius:22px;background:#fcfcf8;color:#203c32;box-shadow:0 24px 80px #142c3140}.module-picker::backdrop{background:#172d3566}.module-picker header{display:flex;justify-content:space-between;gap:16px;padding:28px 30px 20px}.module-picker h2{margin:8px 0;font-size:28px}.module-picker p{font-size:14px;line-height:1.8}.picker-kicker{font-size:12px;letter-spacing:2px;color:#57715c}.module-picker button{cursor:pointer;font:inherit;color:inherit}.picker-close{align-self:flex-start;background:transparent;border:0;font-size:28px!important}.module-picker nav{display:flex;gap:8px;padding:0 30px 20px;flex-wrap:wrap;border-bottom:1px solid #dce2d7}.module-picker nav button,.card-actions button,.module-picker footer button,.back-button{border:1px solid #c9d6c6;background:white;border-radius:9px;padding:10px 14px}.module-picker nav button[aria-pressed=true]{background:#245d49;color:white;border-color:#245d49}.picker-content{padding:24px 30px;min-height:230px}.picker-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.picker-card{display:flex;flex-direction:column;gap:12px;text-align:left;background:white;border:1px solid #dce2d7;border-radius:14px;padding:20px;min-width:0;overflow-wrap:anywhere}.picker-card span{font-size:13px;line-height:1.7;color:#68746b;white-space:pre-wrap}.picker-card small{font-size:12px;color:#5b785b}.picker-card strong{font-size:16px}.card-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:auto}.card-actions button{font-size:12px;padding:8px}.market-example{background:#f1f3ed}.picker-error{margin:16px 30px;color:#a34530}.module-picker footer{padding:16px 30px;display:flex;align-items:center;justify-content:center;gap:20px;border-top:1px solid #dce2d7}.module-picker button:disabled{opacity:.45;cursor:default}@media(max-width:760px){.module-picker{width:calc(100% - 20px);max-height:90dvh}.module-picker header,.picker-content{padding:20px}.module-picker nav{padding:0 20px 16px;gap:6px}.module-picker nav button{padding:9px 10px;font-size:13px}.picker-grid{grid-template-columns:1fr}.module-picker h2{font-size:24px}}
</style>
