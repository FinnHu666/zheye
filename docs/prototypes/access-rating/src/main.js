import { createApp, ref, computed } from 'vue'
import './styles.css'
import Views from './views.js'
import Access from './access.js'
import Rental from './rental.js'
import Rating from './rating.js'
import Checklist from './checklist.js'

const TABS = [
  { id: 'views', t: '首页浏览量', s: '0.7.0 · FINNS-10', c: Views },
  { id: 'access', t: '管理员与受限访问', s: '0.7.0 · FINNS-11', c: Access },
  { id: 'rental', t: '租房私人专题', s: '0.8.0 · FINNS-13', c: Rental },
  { id: 'rating', t: '评价模块与榜单', s: '0.8.0 · FINNS-12', c: Rating },
  { id: 'check', t: '确认清单', s: 'G2', c: Checklist }
]

createApp({
  components: { Views, Access, Rental, Rating, Checklist },
  setup() {
    const h = location.hash.slice(1)
    const tab = ref(TABS.some(x => x.id === h) ? h : 'views')
    const dev = ref('desktop')
    const cur = computed(() => TABS.find(x => x.id === tab.value))
    const go = (id) => { tab.value = id; location.hash = id }
    return { TABS, tab, dev, cur, go }
  },
  template: `
  <div>
    <div class="proto-bar"><div class="proto-bar-in">
      <b>原型 · 全部为模拟数据</b><span>不连接服务端 · 不代表已实现 · G2 待用户确认</span><span class="spacer"></span>
      <span>视口</span><div class="seg"><button v-for="d in [['desktop','桌面'],['tablet','768'],['phone','390']]" :key="d[0]" :class="{on:dev===d[0]}" @click="dev=d[0]" :data-test="'dev-'+d[0]">{{ d[1] }}</button></div>
    </div></div>
    <header class="site-header">
      <div class="brand"><span class="brand-mark">F</span>FINNS</div>
      <nav class="tabs" aria-label="原型页面"><button v-for="x in TABS" :key="x.id" class="tab" :class="{on:tab===x.id}" @click="go(x.id)" :data-test="'tab-'+x.id">{{ x.t }}<small>{{ x.s }}</small></button></nav>
    </header>
    <div class="device-wrap"><div class="device" :class="{tablet:dev==='tablet',phone:dev==='phone'}" data-test="device">
      <component :is="cur.c" :key="tab"></component>
    </div></div>
  </div>`
}).mount('#app')
