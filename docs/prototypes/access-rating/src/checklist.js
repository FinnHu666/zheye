import { computed } from 'vue'
import { params, confirmations } from './shared.js'

/* G2 确认清单：列出原型中的工作草案参数与待确认项。勾选只在本页内存，不代表用户已确认，不写入任何文件。 */
const ITEMS = [
  { id: 'c1', ver: '0.7.0', req: 'FINNS-10', text: '浏览量去重窗口（同会话同对象）', val: p => p.windowMin + ' 分钟' },
  { id: 'c2', ver: '0.7.0', req: 'FINNS-10', text: '首页弹层阅读是否计入累计浏览', val: p => p.modalCounts ? '计入' : '不计入' },
  { id: 'c3', ver: '0.7.0', req: 'FINNS-10', text: '统计起点：启用当日，不补造旧历史；未知/失败不显示成 0', val: () => '启用当日' },
  { id: 'c4', ver: '0.7.0', req: 'FINNS-11', text: '角色草案：普通用户 / 管理员 + 单一“受限访问资格”', val: () => '二角色 + 一资格' },
  { id: 'c5', ver: '0.7.0', req: 'FINNS-11', text: '未登录 401、无资格 403、不能降级最后一名管理员、资格变更写审计', val: () => '已演示' },
  { id: 'c6', ver: '0.8.0', req: 'FINNS-13', text: '租房专题首期仅私人草稿；示例地区与“待补充来源”标记；无公开发布', val: () => '私人草稿' },
  { id: 'c7', ver: '0.8.0', req: 'FINNS-12', text: '评分量表上限', val: p => '1–' + p.scaleMax },
  { id: 'c8', ver: '0.8.0', req: 'FINNS-12', text: '红榜 / 黑榜 / 最小有效样本', val: p => `红≥${p.red} · 黑≤${p.black} · 样本≥${p.minSample}` },
  { id: 'c9', ver: '0.8.0', req: 'FINNS-12', text: '评价版本：最新已通过版本计入；撤回不计；待审/驳回保留旧通过版', val: () => '已演示' },
  { id: 'c10', ver: '0.8.0', req: 'FINNS-12', text: '复制模块只复制定义，不复制对象与评价，与源模块互相独立', val: () => '已演示' }
]

export default {
  name: 'ChecklistPage',
  setup() {
    const done = computed(() => ITEMS.filter(i => confirmations[i.id]).length)
    return { ITEMS, params, confirmations, done }
  },
  template: `
  <section class="page">
    <div class="page-head">
      <div><span class="chip amber">G2 · 待用户确认</span><h2>原型确认清单</h2></div>
      <p class="lead">逐项核对原型中的工作草案。这里的勾选只用于边看边记，<b>不等于</b>用户已确认；G2 以用户明确回复的“具体原型版本与范围”为准。</p>
    </div>
    <div class="card">
      <table class="tbl"><thead><tr><th></th><th>版本</th><th>需求</th><th>确认项</th><th>当前演示值</th></tr></thead>
        <tbody><tr v-for="i in ITEMS" :key="i.id">
          <td><input type="checkbox" v-model="confirmations[i.id]" :aria-label="i.text" :data-test="'chk-'+i.id"></td>
          <td>{{ i.ver }}</td><td>{{ i.req }}</td><td>{{ i.text }}</td><td class="mono">{{ i.val(params) }}</td></tr></tbody></table>
      <p class="small muted" style="margin-top:8px">已勾选 {{ done }} / {{ ITEMS.length }}（仅本页内存，刷新即清空）</p>
    </div>
    <div class="note info" style="margin-top:12px">本原型无法证明：真实并发计数、持久化、接口级权限与 CSRF、服务端校验。这些在 G3–G6 用设计、后端测试与回归验证。</div>
  </section>`
}
