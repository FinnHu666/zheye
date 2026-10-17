import { ref, reactive, computed } from 'vue'
import { safeStorage, uid } from './shared.js'

/* T03 租房私人专题工作台（0.8.0 · FINNS-13 · REQ-017）— 方法骨架与示例地区，不是已核实的租房/法律建议 */
const SECTIONS = [
  { id: 's1', title: '租前准备', items: ['预算与可承受月租上限', '通勤范围与时间', '证件与资料清单', '常见陷阱：中介费、服务费'] },
  { id: 's2', title: '找房', items: ['平台与渠道对比', '房源信息核对要点', '如何识别虚假房源'] },
  { id: 's3', title: '看房', items: ['看房检查清单', '水电燃气与网络', '噪音、采光与安全'] },
  { id: 's4', title: '签约与入住', items: ['合同条款逐条读', '押金与付款方式', '入住交接与拍照留证'] },
  { id: 's5', title: '退租', items: ['提前通知与违约条款', '押金退还与扣款争议'] }
]

export default {
  name: 'RentalPage',
  setup() {
    const sec = ref('s1')
    const region = ref('A 城 · B 区（示例地区，非真实调查）')
    const mobile = ref(false)
    const sel = ref(null)
    const savedAt = ref('')
    const msg = ref('')
    const defaults = () => [
      { id: 'b1', type: 'text', title: '租前准备清单', x: 16, y: 16, w: 250, h: 130, body: '预算 · 通勤 · 证件（占位，待补充实际资料）' },
      { id: 'b2', type: 'html', title: 'HTML 模块：看房时间轴（示例）', x: 290, y: 16, w: 250, h: 130, body: '沿用既有 HTML+CSS 模块，隔离显示' },
      { id: 'b3', type: 'rating', title: '评价模块：房源榜单（私人·拟议）', x: 16, y: 170, w: 524, h: 110, body: '无评分时专题仍可正常阅读；详见“评价模块”原型' }
    ]
    const blocks = ref(defaults())
    const store = safeStorage()
    const save = () => {
      if (!store) { msg.value = '此环境不支持本机存储（模拟保存不可用）。'; return }
      try { store.setItem('finns-proto-rental', JSON.stringify(blocks.value)); savedAt.value = new Date().toTimeString().slice(0, 5); msg.value = '已保存到本机浏览器（仅模拟，不是服务端持久化）。' } catch { msg.value = '本机存储写入失败（模拟）。' }
    }
    const reload = () => {
      blocks.value = defaults(); sel.value = null
      try { const raw = store && store.getItem('finns-proto-rental'); if (raw) { blocks.value = JSON.parse(raw); msg.value = '模拟“刷新页面”：已从本机恢复。' } else msg.value = '模拟“刷新页面”：本机没有已保存的稿子，恢复为初始骨架。' } catch { msg.value = '恢复失败，使用初始骨架。' }
    }
    const add = (type) => {
      const t = { text: '文字模块', html: 'HTML 模块', rating: '评价模块（拟议）' }[type]
      const id = uid('b'); blocks.value.push({ id, type, title: t, x: 24, y: 24 + (blocks.value.length % 6) * 20, w: 220, h: 90, body: '（新增）' }); sel.value = id
    }
    const del = () => { blocks.value = blocks.value.filter(b => b.id !== sel.value); sel.value = null }
    let drag = null
    const down = (e, b) => { sel.value = b.id; const r = e.currentTarget.parentElement.getBoundingClientRect(); drag = { b, dx: e.clientX - r.left - b.x, dy: e.clientY - r.top - b.y, r }; e.currentTarget.setPointerCapture?.(e.pointerId) }
    const move = (e) => { if (!drag) return; const b = drag.b; b.x = Math.max(0, Math.min(drag.r.width - b.w, Math.round(e.clientX - drag.r.left - drag.dx))); b.y = Math.max(0, Math.min(420 - b.h, Math.round(e.clientY - drag.r.top - drag.dy))) }
    const up = () => { drag = null }
    const nudge = (k, d) => { const b = blocks.value.find(x => x.id === sel.value); if (b) b[k] = Math.max(0, b[k] + d) }
    const cur = computed(() => SECTIONS.find(s => s.id === sec.value))
    const sortedBlocks = computed(() => [...blocks.value].sort((a, b) => a.y - b.y || a.x - b.x))
    const selB = computed(() => blocks.value.find(b => b.id === sel.value))
    return { sortedBlocks, SECTIONS, sec, cur, region, mobile, blocks, sel, selB, savedAt, msg, save, reload, add, del, down, move, up, nudge }
  },
  template: `
  <section class="page">
    <div class="page-head">
      <div><span class="version">0.8.0</span><span class="chip blue">FINNS-13 · 原型 T03</span><h2>租房私人专题工作台</h2></div>
      <p class="lead">方法目录与清单是骨架，缺实际资料处明确标“待补充”。首期只做私人草稿：没有公开发布、访客投稿或公开榜单入口。</p>
    </div>

    <div class="card">
      <div class="row sb">
        <div class="row"><span class="chip amber">私人草稿 · 仅本人可见</span><span class="chip">{{ region }}</span></div>
        <div class="row">
          <button class="btn sm" @click="save" data-test="rental-save">保存草稿</button>
          <button class="btn sm ghost" @click="reload" data-test="rental-reload">模拟刷新并恢复</button>
          <button class="btn sm ghost" disabled title="0.8.0 首期不提供公开发布">发布（不可用）</button>
        </div>
      </div>
      <p v-if="msg" class="small" style="margin-top:6px" role="status" data-test="rental-msg">{{ msg }}<span v-if="savedAt"> · {{ savedAt }}</span></p>
    </div>

    <div class="ws" style="margin-top:14px">
      <nav class="outline" aria-label="方法目录">
        <button v-for="s in SECTIONS" :key="s.id" :class="{on:sec===s.id}" @click="sec=s.id">{{ s.title }}</button>
        <div class="note info small">首城未选择，使用标明的示例地区；不用真实地名搭配虚构评分。</div>
      </nav>
      <div>
        <div class="card" data-test="outline-card">
          <div class="row sb"><h3>{{ cur.title }}</h3><span class="chip amber">资料待补充</span></div>
          <ul class="check"><li v-for="i in cur.items" :key="i">{{ i }} <span class="chip">待补充来源</span></li></ul>
          <p class="small muted">来源、地区、个人经验与“待核实”会分开标注；不生成已核实的法律或租房建议。</p>
        </div>
        <div class="card">
          <div class="row sb"><h3>画布与阅读顺序</h3>
            <div class="row"><div class="seg" style="border-color:var(--line)"><button :class="{on:!mobile}" :style="{background:!mobile?'var(--green)':'#fff',color:!mobile?'#fff':'var(--ink)'}" @click="mobile=false">自由画布</button><button :class="{on:mobile}" :style="{background:mobile?'var(--green)':'#fff',color:mobile?'#fff':'var(--ink)'}" @click="mobile=true" data-test="mobile-toggle">手机阅读预览</button></div></div></div>
          <div class="row" style="margin:8px 0">
            <button class="btn ghost sm" @click="add('text')">+ 文字</button><button class="btn ghost sm" @click="add('html')">+ HTML 模块</button><button class="btn ghost sm" @click="add('rating')" data-test="add-rating">+ 评价模块</button>
            <template v-if="selB"><span class="muted small">已选：{{ selB.title }}</span>
              <button class="btn ghost sm" @click="nudge('w',-20)">窄</button><button class="btn ghost sm" @click="nudge('w',20)">宽</button><button class="btn ghost sm" @click="nudge('h',-10)">矮</button><button class="btn ghost sm" @click="nudge('h',10)">高</button><button class="btn warn sm" @click="del">删除</button></template></div>
          <div v-if="!mobile" class="canvas" @pointermove="move" @pointerup="up" @pointerleave="up" data-test="canvas">
            <div v-for="b in blocks" :key="b.id" class="blk" :class="[b.type,{sel:sel===b.id}]" :style="{left:b.x+'px',top:b.y+'px',width:b.w+'px',height:b.h+'px'}" @pointerdown="down($event,b)" :data-test="'blk-'+b.id"><b>{{ b.title }}</b>{{ b.body }}</div>
          </div>
          <div v-else class="stacked" data-test="stacked"><div v-for="b in sortedBlocks" :key="b.id" class="blk" :class="b.type"><b>{{ b.title }}</b>{{ b.body }}</div></div>
          <p class="small muted" style="margin-top:6px">手机阅读按画布从上到下的顺序堆叠。拖动方块可调整位置，“宽/高”调整尺寸。保存仅写本机浏览器。</p>
        </div>
      </div>
    </div>
  </section>`
}
