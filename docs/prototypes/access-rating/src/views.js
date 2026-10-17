import { ref, reactive, computed } from 'vue'
import { params } from './shared.js'

/* T01 首页全站累计浏览量（0.7.0 · FINNS-10 · REQ-015） */
export default {
  name: 'ViewsPage',
  setup() {
    const status = ref('normal')           // normal | loading | unknown | failed
    const total = ref(12846)
    const startedAt = '启用当日（演示值，实际以启用日为准）'
    const clock = ref(0)                   // 模拟时钟（分钟）
    const reportFail = ref(false)
    const counted = reactive({})
    const log = ref([])
    const reading = ref(null)
    const showRules = ref(false)
    const articles = [
      { id: 'a1', title: '第一次租房前要核对的十件事（示例）' },
      { id: 'a2', title: '合同里的押金条款怎么读（示例）' }
    ]
    const push = (kind, msg) => { log.value.unshift({ t: clock.value, kind, msg }); log.value = log.value.slice(0, 14) }
    const count = (key, label) => {
      if (reportFail.value) { push('fail', `${label}：计数上报失败。阅读不受影响，本次不补计。`); return }
      const last = counted[key]
      if (last !== undefined && clock.value - last < params.windowMin) {
        push('skip', `${label}：${params.windowMin} 分钟内同会话重复，去重，不计。`); return
      }
      counted[key] = clock.value
      total.value += 1
      push('count', `${label}：计 1 次。`)
    }
    const read = (a, via = 'page') => {
      reading.value = a
      if (via === 'modal' && !params.modalCounts) { push('skip', `首页弹层阅读「${a.title}」：当前口径不计（待确认项）。`); return }
      count(a.id, `${via === 'modal' ? '弹层' : '页面'}阅读「${a.title}」`)
    }
    const ignored = (label, why) => push('skip', `${label}：${why}，不计。`)
    const advance = (m) => { clock.value += m; push('info', `模拟时间前进 ${m} 分钟（当前 +${clock.value} 分钟）。`) }
    const resetZero = () => { total.value = 0; Object.keys(counted).forEach(k => delete counted[k]); status.value = 'normal'; push('info', '已重置为“真实零”：统计已启用，暂无有效阅读。') }
    const retry = () => { status.value = 'loading'; setTimeout(() => { status.value = 'normal' }, 700) }
    const display = computed(() => status.value === 'normal' ? total.value.toLocaleString('en-US') : '')
    return { status, total, startedAt, clock, reportFail, log, reading, showRules, articles, params, read, ignored, advance, resetZero, retry, display }
  },
  template: `
  <section class="page">
    <div class="page-head">
      <div><span class="version">0.7.0</span><span class="chip blue">FINNS-10 · 原型 T01</span><h2>首页全站累计浏览量</h2></div>
      <p class="lead">累计的是“有效公开阅读次数”，不是独立访客数或注册人数。下方所有数字与事件都是模拟，不连接服务端。</p>
    </div>

    <div class="hero-stat" aria-live="polite">
      <span class="chip green">FINNS · 首页（模拟）</span>
      <h1>写下、整理并分享你的经验方案</h1>
      <div class="stat-row">
        <div class="stat"><b>3</b><span>创作者</span></div>
        <div class="stat"><b>12</b><span>公开内容</span></div>
        <div class="stat">
          <b>
            <span v-if="status==='normal'" class="mono" data-test="views-number">{{ display }}</span>
            <span v-else-if="status==='loading'" class="num-loading" data-test="views-loading"></span>
            <span v-else-if="status==='unknown'" class="num-unknown" data-test="views-unknown">—</span>
            <span v-else class="num-failed" data-test="views-failed">暂时无法获取</span>
          </b>
          <span>
            累计浏览（次）
            <template v-if="status==='normal' && total===0"> · 统计已启用，暂无阅读</template>
            <template v-if="status==='unknown'"> · 暂无法确认，不代表为 0</template>
          </span>
        </div>
      </div>
      <p class="small muted" style="margin-top:14px">统计起点：{{ startedAt }} · <a href="#" @click.prevent="showRules=true">口径说明</a></p>
      <div v-if="status==='failed'" class="note bad" style="margin-top:12px;text-align:left">累计值读取失败。页面其余内容与阅读不受影响，且不会显示成 0。
        <button class="btn sm ghost" @click="retry">重试</button></div>
    </div>

    <div class="grid2" style="margin-top:14px">
      <div class="card">
        <h3>1 · 切换显示状态</h3>
        <div class="row">
          <button class="btn ghost sm" @click="status='normal'">正常</button>
          <button class="btn ghost sm" @click="status='loading'">加载中</button>
          <button class="btn ghost sm" @click="resetZero">真实零</button>
          <button class="btn ghost sm" @click="status='unknown'">未知</button>
          <button class="btn ghost sm" @click="status='failed'">读取失败</button>
        </div>
        <p class="small muted" style="margin-top:8px">“真实零”与“未知”“失败”外观不同：只有真的统计到 0 才显示 0。</p>
        <label class="row small" style="margin-top:10px"><input type="checkbox" v-model="reportFail"> 模拟“计数上报失败”（阅读不被阻断，也不补造计数）</label>
      </div>

      <div class="card">
        <h3>2 · 模拟事件：什么算、什么不算</h3>
        <div class="stack">
          <div class="row">
            <button v-for="a in articles" :key="a.id" class="btn sm" @click="read(a)">打开阅读「{{ a.title.slice(0,9) }}…」</button>
            <button class="btn sm" @click="read(articles[0],'modal')">在首页弹层阅读第 1 篇</button>
          </div>
          <div class="row">
            <button class="btn ghost sm" @click="ignored('编辑页访问','编辑不是阅读')">编辑页访问</button>
            <button class="btn ghost sm" @click="ignored('后台页访问','后台不是阅读')">后台页访问</button>
            <button class="btn ghost sm" @click="ignored('列表曝光/预加载','未打开正文')">列表曝光</button>
            <button class="btn ghost sm" @click="ignored('轮询/资源请求','非用户阅读')">轮询请求</button>
          </div>
          <div class="row"><button class="btn ghost sm" @click="advance(31)">模拟时间前进 31 分钟</button><span class="small muted">当前模拟时钟 +{{ clock }} 分钟</span></div>
        </div>
      </div>
    </div>

    <div class="grid2" style="margin-top:14px">
      <div class="card">
        <h3>3 · 待确认的口径参数（演示值）</h3>
        <div class="stack">
          <label class="f">同会话 / 同对象去重窗口
            <select v-model.number="params.windowMin"><option :value="15">15 分钟</option><option :value="30">30 分钟（工作建议）</option><option :value="60">60 分钟</option></select></label>
          <label class="row small"><input type="checkbox" v-model="params.modalCounts"> 首页文章弹层阅读也计入累计浏览</label>
          <p class="small muted">统计起点：从实际启用起累计，不估算补造旧历史。这三项在 G2 确认页逐项核对。</p>
        </div>
      </div>
      <div class="card">
        <h3>事件记录（模拟）</h3>
        <div v-if="!log.length" class="empty">还没有事件，点击左侧按钮。</div>
        <div class="log" data-test="views-log">
          <div v-for="(e,i) in log" :key="i"><time>+{{ e.t }}m</time>
            <span class="chip" :class="{green:e.kind==='count',amber:e.kind==='skip',red:e.kind==='fail',blue:e.kind==='info'}">{{ {count:'计数',skip:'不计',fail:'失败',info:'说明'}[e.kind] }}</span>
            <span>{{ e.msg }}</span></div>
        </div>
      </div>
    </div>

    <div v-if="reading" class="card" style="margin-top:14px">
      <div class="row sb"><h3>阅读区（模拟）：{{ reading.title }}</h3><button class="btn ghost sm" @click="reading=null">关闭</button></div>
      <p class="muted">计数失败或去重都不会影响这里的阅读。正文为占位示例。</p>
    </div>

    <div v-if="showRules" class="overlay" @click.self="showRules=false">
      <div class="modal" role="dialog" aria-label="统计口径">
        <h3>统计口径说明（原型草案）</h3>
        <dl class="kv">
          <dt>统计对象</dt><dd>有效公开阅读：打开文章/专题正文一次</dd>
          <dt>不计入</dt><dd>列表曝光、预加载、资源与轮询请求、编辑页、后台页</dd>
          <dt>去重</dt><dd>同会话同对象 {{ params.windowMin }} 分钟内只计 1 次；不同对象分别计</dd>
          <dt>首页弹层</dt><dd>{{ params.modalCounts ? '计入（当前演示值）' : '不计入（当前演示值，待确认）' }}</dd>
          <dt>统计起点</dt><dd>启用当天；不补造旧历史</dd>
          <dt>含义</dt><dd>浏览次数，不是独立访客数、注册人数或活跃人数</dd>
          <dt>不做</dt><dd>UV、逐篇排行、完整埋点、设备指纹、完全防机器人</dd>
        </dl>
        <p class="note" style="margin-top:12px">真实并发准确性、重启恢复和持久化在 G6 用后端测试验证，本原型无法证明。</p>
        <div class="row" style="margin-top:12px;justify-content:flex-end"><button class="btn" @click="showRules=false">知道了</button></div>
      </div>
    </div>
  </section>`
}
