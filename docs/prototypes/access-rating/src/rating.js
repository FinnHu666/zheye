import { ref, reactive, computed } from 'vue'
import { params, uid } from './shared.js'

/* T04 自定义评价模块与独立榜单复用（0.8.0 · FINNS-12 · REQ-018）— 全部为固定测试样例，不代表真实评价 */
const KINDS = { text: '文字', number: '数字', single: '单选', multi: '多选', region: '地域（可选）' }

export default {
  name: 'RatingPage',
  setup() {
    const step = ref(1)
    const visited = reactive({ 1: true })
    const role = ref('author')   // author | member | visitor
    const moduleName = ref('番剧评价榜（私人草稿）')
    const types = reactive([
      { id: 't1', name: '房源', preset: true, active: true, geo: true, fields: [
        { id: 'f1', label: '区域', kind: 'region', required: true, options: [], filter: true },
        { id: 'f2', label: '月租(元)', kind: 'number', required: true, options: [], filter: true },
        { id: 'f3', label: '户型', kind: 'single', required: false, options: ['一居', '两居', '合租'], filter: true }] },
      { id: 't2', name: '租赁方', preset: true, active: true, geo: true, fields: [
        { id: 'f4', label: '类型', kind: 'single', required: true, options: ['个人房东', '中介', '长租公寓'], options2: [], filter: true }] },
      { id: 't3', name: '番剧', preset: false, active: true, geo: false, fields: [
        { id: 'f5', label: '题材', kind: 'multi', required: true, options: ['热血', '悬疑', '日常', '科幻'], filter: true },
        { id: 'f6', label: '集数', kind: 'number', required: true, options: [], filter: false },
        { id: 'f7', label: '平台', kind: 'single', required: false, options: ['A 站', 'B 站', '自购'], filter: true }] }
    ])
    const curTypeId = ref('t3')
    const curType = computed(() => types.find(t => t.id === curTypeId.value))
    const newTypeName = ref('')
    const typeMsg = ref('')
    const nf = reactive({ label: '', kind: 'text', required: false, options: '', filter: false })
    const fieldMsg = ref('')
    const history = ref([])
    const objects = reactive([
      { id: 'o1', typeId: 't3', name: '示例番剧甲', values: { 题材: ['热血'], 集数: 24, 平台: 'A 站' } },
      { id: 'o2', typeId: 't3', name: '示例番剧乙', values: { 题材: ['悬疑', '科幻'], 集数: 12, 平台: 'B 站' } },
      { id: 'o3', typeId: 't3', name: '示例番剧丙', values: { 题材: ['日常'], 集数: 13, 平台: '自购' } },
      { id: 'o4', typeId: 't3', name: '示例番剧丁', values: { 题材: ['热血', '科幻'], 集数: 26, 平台: 'A 站' } }
    ])
    const draft = reactive({ name: '', values: {} })
    const errors = ref([])
    const filters = reactive({})
    // 评价：固定样例。同一 评价者+对象 取最新版本；最新为已通过 → 计入；为已撤回 → 不计；待审核/驳回 → 沿用上一个已通过版本
    const reviews = reactive([
      ...[5, 4, 5, 4, 5, 4].map((s, i) => ({ id: 'ra' + i, obj: 'o1', who: '样例评价者' + (i + 1), ver: 1, score: s, status: 'approved' })),
      { id: 'ra6', obj: 'o1', who: '样例评价者1', ver: 2, score: 3, status: 'pending' },
      ...[2, 1, 2, 2, 1, 2].map((s, i) => ({ id: 'rb' + i, obj: 'o2', who: '样例评价者' + (i + 1), ver: 1, score: s, status: 'approved' })),
      ...[4, 3, 4].map((s, i) => ({ id: 'rc' + i, obj: 'o3', who: '样例评价者' + (i + 1), ver: 1, score: s, status: 'approved' })),
      { id: 'rc9', obj: 'o3', who: '样例评价者9', ver: 1, score: 1, status: 'rejected' }
    ])
    const newRev = reactive({ obj: 'o4', score: 4 })
    const revMsg = ref('')
    const sourceFieldAdded = ref(false)
    const copyTo = ref('b')
    const copies = ref([])

    const visTypes = computed(() => types)
    const typeObjects = computed(() => objects.filter(o => o.typeId === curTypeId.value))
    const filterFields = computed(() => curType.value.fields.filter(f => f.filter && (f.kind === 'single' || f.kind === 'multi')))
    const shownObjects = computed(() => typeObjects.value.filter(o => filterFields.value.every(f => {
      const want = filters[f.label]; if (!want) return true
      const v = o.values[f.label]; return Array.isArray(v) ? v.includes(want) : v === want
    })))
    const effective = (objId) => {
      const by = {}
      reviews.filter(r => r.obj === objId).forEach(r => { (by[r.who] ||= []).push(r) })
      const vals = []
      Object.values(by).forEach(list => {
        list.sort((a, b) => b.ver - a.ver)
        const hit = list.find(r => r.status === 'approved' || r.status === 'withdrawn')
        if (hit && hit.status === 'approved') vals.push(hit.score)
      })
      return vals
    }
    const ranking = computed(() => typeObjects.value.map(o => {
      const v = effective(o.id)
      const n = v.length
      const avg = n ? v.reduce((a, b) => a + b, 0) / n : 0
      let tag = 'none'
      if (n === 0) tag = 'none'
      else if (n < params.minSample) tag = 'thin'
      else if (avg >= params.red) tag = 'red'
      else if (avg <= params.black) tag = 'black'
      else tag = 'mid'
      return { o, n, avg, tag }
    }).sort((a, b) => b.avg - a.avg))
    const tagText = { red: ['红榜', 'green'], black: ['黑榜', 'red'], mid: ['中间', ''], thin: ['样本不足', 'amber'], none: ['暂无有效评价', ''] }

    const addType = () => {
      const n = newTypeName.value.trim()
      if (!n) { typeMsg.value = '请先输入类型名称。'; return }
      if (types.some(t => t.name === n)) { typeMsg.value = '已有同名类型。'; return }
      const t = { id: uid('t'), name: n, preset: false, active: true, geo: false, fields: [] }
      types.push(t); curTypeId.value = t.id; newTypeName.value = ''; typeMsg.value = `已新增类型“${n}”（地域默认关闭，不强制填城市/区）。无需改代码。`
    }
    const moveType = (i, d) => { const j = i + d; if (j < 0 || j >= types.length) return; const [x] = types.splice(i, 1); types.splice(j, 0, x) }
    const toggleType = (t) => {
      t.active = !t.active
      history.value.unshift(t.active ? `启用类型“${t.name}”：恢复后旧对象与评分原样可用。` : `停用类型“${t.name}”：已有对象与评分保留为只读，不删除，不再可新增。`)
    }
    const renameType = (t) => {
      const n = (prompt0(t.name) || '').trim(); if (!n) return
      history.value.unshift(`类型“${t.name}”改名为“${n}”：已有对象与评分通过类型身份保持关联，历史不丢。`); t.name = n
    }
    const prompt0 = (d) => (typeof window !== 'undefined' && window.__protoRename) ? window.__protoRename : d + '（改）'
    const addField = () => {
      const label = nf.label.trim()
      if (!label) { fieldMsg.value = '请填写属性名称。'; return }
      if (curType.value.fields.some(f => f.label === label)) { fieldMsg.value = '该类型已有同名属性。'; return }
      const opts = nf.options.split(/[,，、]/).map(s => s.trim()).filter(Boolean)
      if ((nf.kind === 'single' || nf.kind === 'multi') && !opts.length) { fieldMsg.value = '单选/多选需要至少一个候选值。'; return }
      if (nf.kind === 'region' && !curType.value.geo) { fieldMsg.value = '此类型地域已关闭；先开启“启用地域”再添加地域属性。'; return }
      curType.value.fields.push({ id: uid('f'), label, kind: nf.kind, required: nf.required, options: opts, filter: nf.filter })
      fieldMsg.value = `已添加属性“${label}”。`
      if (objects.some(o => o.typeId === curTypeId.value)) history.value.unshift(`新增属性“${label}”：已有 ${objects.filter(o => o.typeId === curTypeId.value).length} 个对象缺该项${nf.required ? '（必填 → 标记“待补充”，不阻断阅读）' : '（选填）'}。`)
      Object.assign(nf, { label: '', kind: 'text', required: false, options: '', filter: false })
    }
    const removeField = (f) => { curType.value.fields = curType.value.fields.filter(x => x.id !== f.id); history.value.unshift(`移除属性“${f.label}”：该属性历史取值保留在旧对象中，不静默删除。`) }
    const changeScale = () => { params.scaleMax = params.scaleMax === 5 ? 10 : 5; history.value.unshift(`量表改为 1–${params.scaleMax}：已有评分标注“旧量表（1–${params.scaleMax === 5 ? 10 : 5}）”，不静默折算或覆盖。`) }
    const toggleGeo = () => { curType.value.geo = !curType.value.geo; if (!curType.value.geo) curType.value.fields = curType.value.fields.filter(f => f.kind !== 'region') }

    const resetDraft = () => { draft.name = ''; draft.values = {}; errors.value = [] }
    const addObject = () => {
      const errs = []
      if (!draft.name.trim()) errs.push('对象名称不能为空。')
      curType.value.fields.forEach(f => {
        const v = draft.values[f.label]
        const empty = v === undefined || v === '' || (Array.isArray(v) && !v.length)
        if (f.required && empty) errs.push(`“${f.label}”为必填。`)
        if (!empty && f.kind === 'number' && !(Number(v) >= 0)) errs.push(`“${f.label}”需要是不小于 0 的数字。`)
        if (!empty && (f.kind === 'single') && !f.options.includes(v)) errs.push(`“${f.label}”的值不在候选项内。`)
        if (!empty && f.kind === 'multi' && !v.every(x => f.options.includes(x))) errs.push(`“${f.label}”含有不在候选项内的值。`)
      })
      if (!curType.value.active) errs.push('该类型已停用，不能新增对象。')
      errors.value = errs
      if (errs.length) return
      objects.push({ id: uid('o'), typeId: curTypeId.value, name: draft.name.trim(), values: JSON.parse(JSON.stringify(draft.values)) })
      resetDraft()
    }
    const submitReview = () => {
      if (role.value === 'visitor') { revMsg.value = '访客没有权限（私人草稿，仅本人可见）。'; return }
      const mine = reviews.filter(r => r.obj === newRev.obj && r.who === '模拟参与者')
      const ver = mine.length ? Math.max(...mine.map(r => r.ver)) + 1 : 1
      reviews.push({ id: uid('r'), obj: newRev.obj, who: '模拟参与者', ver, score: Number(newRev.score), status: 'pending' })
      revMsg.value = `已提交（第 ${ver} 版，待审核）。通过前不计入榜单；若有更早的通过版本，仍沿用它。`
    }
    const act = (r, status) => { if (role.value !== 'author') return; r.status = status }
    const objName = (id) => objects.find(o => o.id === id)?.name || id
    const statusText = { approved: ['已通过', 'green'], pending: ['待审核', 'amber'], rejected: ['已驳回', 'red'], withdrawn: ['已撤回', ''] }

    const doCopy = () => {
      const src = types.filter(t => !t.preset || true)
      copies.value.unshift({
        id: uid('copy'), target: copyTo.value === 'b' ? '租房私人专题' : '新建专题（空）',
        types: types.length, fields: types.reduce((a, t) => a + t.fields.length, 0), objects: 0, reviews: 0, at: copies.value.length + 1
      })
    }
    const addSourceField = () => { types.find(t => t.id === 't3').fields.push({ id: uid('f'), label: '制作公司', kind: 'text', required: false, options: [], filter: false }); sourceFieldAdded.value = true }
    const stepNames = ['模块', '实体类型', '属性与选项', '对象', '筛选与量表', '提交与审核', '榜单', '复制']
    const go = (n) => { step.value = n; visited[n] = true }
    return { step, visited, role, moduleName, types, curTypeId, curType, newTypeName, typeMsg, nf, fieldMsg, history, objects, draft, errors, filters, reviews, newRev, revMsg,
      sourceFieldAdded, copyTo, copies, KINDS, typeObjects, filterFields, shownObjects, ranking, tagText, addType, moveType, toggleType, renameType, addField, removeField, changeScale,
      toggleGeo, addObject, submitReview, act, objName, statusText, doCopy, addSourceField, stepNames, go, params, effective }
  },
  template: `
  <section class="page">
    <div class="page-head">
      <div><span class="version">0.8.0</span><span class="chip blue">FINNS-12 · 原型 T04</span><h2>自定义评价模块与独立榜单</h2></div>
      <p class="lead">类型、属性、对象、评价四层分开。示例对象与评价均为固定测试样例，不代表真实多人评价；参与者视角是拟议状态，不绕过私人草稿归属。</p>
    </div>

    <div class="card">
      <div class="row sb">
        <div class="row"><b>视角：</b>
          <div class="seg" style="border-color:var(--line)"><button v-for="v in [{id:'author',n:'本人（作者）'},{id:'member',n:'模拟参与者（拟议）'},{id:'visitor',n:'访客'}]" :key="v.id" :style="{background:role===v.id?'var(--green)':'#fff',color:role===v.id?'#fff':'var(--ink)'}" @click="role=v.id" :data-test="'role-'+v.id">{{ v.n }}</button></div></div>
        <span class="chip amber">私人草稿</span>
      </div>
      <div v-if="role==='visitor'" class="note bad" style="margin-top:10px" data-test="visitor-block">此评价模块处于私人草稿，访客不可见。请切换回“本人”继续演示。</div>
    </div>

    <template v-if="role!=='visitor'">
    <div class="steps" style="margin-top:14px" role="tablist">
      <button v-for="(n,i) in stepNames" :key="i" class="step" :class="{on:step===i+1,done:visited[i+1]&&step!==i+1}" @click="go(i+1)" :data-test="'step-'+(i+1)">{{ i+1 }} {{ n }}</button>
    </div>

    <div v-if="step===1" class="card">
      <h3>1 · 新建评价模块</h3>
      <label class="f">模块名称<input type="text" v-model="moduleName" :disabled="role!=='author'"></label>
      <p class="muted small" style="margin-top:8px">模块属于专题的私人草稿；只有本人可编辑定义。下一步：定义这个榜单里“被评价的是什么”。</p>
      <div class="row" style="margin-top:10px"><button class="btn" @click="go(2)">下一步：实体类型</button></div>
    </div>

    <div v-if="step===2" class="card" data-test="step2">
      <h3>2 · 实体类型（作者自定义）</h3>
      <p class="muted small">“房源”“租赁方”只是可修改的预设，不是固定白名单。</p>
      <div class="stack" style="margin-top:8px">
        <div v-for="(t,i) in types" :key="t.id" class="row sb card" style="padding:9px 12px;margin:0">
          <div class="row"><b :style="{opacity:t.active?1:.5}">{{ t.name }}</b><span v-if="t.preset" class="chip">预设</span><span v-if="!t.active" class="chip amber">已停用</span><span class="chip" :class="t.geo?'blue':''">{{ t.geo?'含地域':'无地域' }}</span></div>
          <div class="row" v-if="role==='author'"><button class="btn ghost sm" @click="curTypeId=t.id;go(3)">属性</button><button class="btn ghost sm" @click="moveType(i,-1)" :disabled="i===0">上移</button><button class="btn ghost sm" @click="moveType(i,1)" :disabled="i===types.length-1">下移</button><button class="btn ghost sm" @click="renameType(t)">改名</button><button class="btn warn sm" @click="toggleType(t)">{{ t.active?'停用':'启用' }}</button></div>
        </div>
      </div>
      <div v-if="role==='author'" class="row" style="margin-top:10px"><input type="text" v-model="newTypeName" placeholder="输入新的类型名称，例如：咖啡豆、课程、软件" style="max-width:360px" data-test="new-type"><button class="btn" @click="addType" data-test="add-type">新增类型</button></div>
      <p v-if="typeMsg" class="note ok small" style="margin-top:8px" data-test="type-msg">{{ typeMsg }}</p>
      <div v-if="history.length" class="note info small" style="margin-top:8px"><b>历史处理提示：</b><div v-for="(h,i) in history.slice(0,4)" :key="i">· {{ h }}</div></div>
    </div>

    <div v-if="step===3" class="card" data-test="step3">
      <div class="row sb"><h3>3 · “{{ curType.name }}”的属性与候选项</h3>
        <select v-model="curTypeId" style="max-width:200px"><option v-for="t in types" :key="t.id" :value="t.id">{{ t.name }}</option></select></div>
      <label class="row small" style="margin:6px 0"><input type="checkbox" :checked="curType.geo" @change="toggleGeo" :disabled="role!=='author'" data-test="geo-toggle"> 启用地域（城市/区）——非租房类型可关闭，不强制填写</label>
      <div class="tbl-wrap"><table><thead><tr><th>属性</th><th>类型</th><th>必填</th><th>候选项</th><th>可筛选</th><th></th></tr></thead>
        <tbody><tr v-for="f in curType.fields" :key="f.id"><td>{{ f.label }}</td><td>{{ KINDS[f.kind] }}</td><td>{{ f.required?'是':'否' }}</td><td>{{ f.options.join('、')||'—' }}</td><td>{{ f.filter?'是':'否' }}</td><td><button v-if="role==='author'" class="btn warn sm" @click="removeField(f)">移除</button></td></tr>
          <tr v-if="!curType.fields.length"><td colspan="6"><div class="empty">还没有属性。先添加一个。</div></td></tr></tbody></table></div>
      <div v-if="role==='author'" class="fieldrow" style="margin-top:10px">
        <label class="f">属性名称<input type="text" v-model="nf.label" data-test="nf-label"></label>
        <label class="f">类型<select v-model="nf.kind" data-test="nf-kind"><option v-for="(n,k) in KINDS" :key="k" :value="k">{{ n }}</option></select></label>
        <label class="f">候选值（逗号分隔）<input type="text" v-model="nf.options" data-test="nf-options" placeholder="单选/多选必填"></label>
        <div class="stack"><label class="row small"><input type="checkbox" v-model="nf.required"> 必填</label><label class="row small"><input type="checkbox" v-model="nf.filter"> 可筛选</label></div>
        <button class="btn" @click="addField" data-test="nf-add">添加</button>
      </div>
      <p v-if="fieldMsg" class="note small" style="margin-top:8px" data-test="field-msg">{{ fieldMsg }}</p>
      <p class="small muted" style="margin-top:6px">字段库工作草案：文字、数字、单选、多选、可选地域。字段变更对历史对象的影响见下方提示。</p>
      <div v-if="history.length" class="note info small" style="margin-top:8px"><b>历史处理提示：</b><div v-for="(h,i) in history.slice(0,5)" :key="i">· {{ h }}</div></div>
    </div>

    <div v-if="step===4" class="card" data-test="step4">
      <div class="row sb"><h3>4 · 添加具体对象（按定义生成表单）</h3>
        <select v-model="curTypeId" style="max-width:200px" @change="resetDraft"><option v-for="t in types" :key="t.id" :value="t.id">{{ t.name }}</option></select></div>
      <p class="muted small">给“具体对象”评分，不给类型标签本身评分。</p>
      <div v-if="role==='author'" class="grid2" style="margin-top:8px">
        <label class="f">对象名称<input type="text" v-model="draft.name" data-test="obj-name"></label>
        <label v-for="f in curType.fields" :key="f.id" class="f">{{ f.label }}<b v-if="f.required" style="color:var(--red)">*</b>
          <select v-if="f.kind==='single'" v-model="draft.values[f.label]"><option value="">请选择</option><option v-for="o in f.options" :key="o" :value="o">{{ o }}</option></select>
          <select v-else-if="f.kind==='multi'" multiple v-model="draft.values[f.label]"><option v-for="o in f.options" :key="o" :value="o">{{ o }}</option></select>
          <input v-else-if="f.kind==='number'" type="text" inputmode="decimal" v-model="draft.values[f.label]" :data-test="'obj-'+f.label">
          <input v-else type="text" v-model="draft.values[f.label]" :data-test="'obj-'+f.label"></label>
      </div>
      <div v-if="errors.length" class="note bad small" style="margin-top:8px" data-test="obj-errors"><div v-for="(e,i) in errors" :key="i">· {{ e }}</div></div>
      <div v-if="role==='author'" class="row" style="margin-top:8px"><button class="btn" @click="addObject" data-test="obj-add">添加对象</button><button class="btn ghost" @click="resetDraft">清空</button></div>
      <h3 style="margin-top:14px">已有对象（{{ typeObjects.length }}）</h3>
      <div v-if="!typeObjects.length" class="empty">这个类型还没有对象。</div>
      <div class="tbl-wrap" v-else><table><thead><tr><th>名称</th><th v-for="f in curType.fields" :key="f.id">{{ f.label }}</th></tr></thead>
        <tbody><tr v-for="o in typeObjects" :key="o.id"><td><b>{{ o.name }}</b></td><td v-for="f in curType.fields" :key="f.id">{{ Array.isArray(o.values[f.label]) ? o.values[f.label].join('、') : (o.values[f.label] ?? '待补充') }}</td></tr></tbody></table></div>
    </div>

    <div v-if="step===5" class="card" data-test="step5">
      <h3>5 · 筛选与量表</h3>
      <div class="grid2">
        <div><b>按属性筛选（{{ curType.name }}）</b>
          <div class="stack" style="margin-top:6px"><label v-for="f in filterFields" :key="f.id" class="f">{{ f.label }}
            <select v-model="filters[f.label]" :data-test="'filter-'+f.label"><option value="">全部</option><option v-for="o in f.options" :key="o" :value="o">{{ o }}</option></select></label></div>
          <p class="small muted" style="margin-top:6px">当前匹配 <b data-test="filter-count">{{ shownObjects.length }}</b> / {{ typeObjects.length }}：{{ shownObjects.map(o=>o.name).join('、')||'无' }}</p></div>
        <div><b>评分量表与榜单规则（工作草案）</b>
          <div class="stack" style="margin-top:6px">
            <div class="row sb"><span>量表：1–{{ params.scaleMax }} 分</span><button class="btn ghost sm" @click="changeScale" :disabled="role!=='author'">改变量表</button></div>
            <label class="f">有效样本不少于<input type="number" v-model.number="params.minSample" min="1" max="20" :disabled="role!=='author'"></label>
            <label class="f">红榜：平均分 ≥<input type="number" v-model.number="params.red" step="0.5" :disabled="role!=='author'"></label>
            <label class="f">黑榜：平均分 ≤<input type="number" v-model.number="params.black" step="0.5" :disabled="role!=='author'"></label>
          </div></div>
      </div>
      <div v-if="history.length" class="note info small" style="margin-top:10px"><b>历史处理提示：</b><div v-for="(h,i) in history.slice(0,5)" :key="i">· {{ h }}</div></div>
    </div>

    <div v-if="step===6" class="card" data-test="step6">
      <h3>6 · 提交与审核</h3>
      <p class="small muted">计入规则：同一评价者对同一对象取最新版本；最新为“已通过”才计入，“已撤回”不计，“待审核/已驳回”不替换更早的已通过版本。</p>
      <div class="row" style="margin:8px 0"><label class="f" style="min-width:160px">对象<select v-model="newRev.obj"><option v-for="o in objects.filter(o=>o.typeId==='t3')" :key="o.id" :value="o.id">{{ o.name }}</option></select></label>
        <label class="f" style="width:90px">分数<input type="number" v-model.number="newRev.score" min="1" :max="params.scaleMax"></label>
        <button class="btn" @click="submitReview" data-test="rev-submit" style="align-self:end">提交评价</button></div>
      <p v-if="revMsg" class="note small" data-test="rev-msg">{{ revMsg }}</p>
      <div class="tbl-wrap" style="margin-top:8px"><table data-test="rev-table"><thead><tr><th>对象</th><th>评价者</th><th>版本</th><th>分数</th><th>状态</th><th v-if="role==='author'">审核</th></tr></thead>
        <tbody><tr v-for="r in reviews.filter(r=>r.obj!=='o3'||true).slice(-14)" :key="r.id"><td>{{ objName(r.obj) }}</td><td>{{ r.who }}<span class="chip" v-if="r.who.startsWith('样例')" style="margin-left:4px">样例</span></td><td>v{{ r.ver }}</td><td class="mono">{{ r.score }}</td>
          <td><span class="chip" :class="statusText[r.status][1]">{{ statusText[r.status][0] }}</span></td>
          <td v-if="role==='author'" class="row"><button class="btn ghost sm" @click="act(r,'approved')" :data-test="'approve-'+r.id">通过</button><button class="btn ghost sm" @click="act(r,'rejected')">驳回</button><button class="btn warn sm" @click="act(r,'withdrawn')">撤回</button><button class="btn ghost sm" @click="act(r,'approved')" v-if="r.status==='withdrawn'">恢复</button></td></tr></tbody></table></div>
    </div>

    <div v-if="step===7" class="card" data-test="step7">
      <h3>7 · 榜单（{{ curType.name }}）</h3>
      <p class="small muted">红榜：均分 ≥ {{ params.red }} 且有效样本 ≥ {{ params.minSample }}；黑榜：均分 ≤ {{ params.black }} 且样本达标；其余为中间或样本不足。数值为测试样例。</p>
      <div v-if="!ranking.length" class="empty">该类型暂无对象。</div>
      <div class="rank" style="margin-top:8px" data-test="ranking">
        <div v-for="(r,i) in ranking" :key="r.o.id" class="rank-item" :data-tag="r.tag">
          <div class="rank-no">{{ i+1 }}</div>
          <div><b>{{ r.o.name }}</b><div class="small muted">有效评价 {{ r.n }} 条<span v-if="r.n"> · 均分 {{ r.avg.toFixed(2) }}</span></div>
            <div class="bar" :class="r.tag==='black'?'black':r.tag==='red'?'':'' "><i :style="{width:(r.avg/params.scaleMax*100)+'%'}"></i></div></div>
          <div><span class="chip" :class="tagText[r.tag][1]">{{ tagText[r.tag][0] }}</span></div>
        </div>
      </div>
    </div>

    <div v-if="step===8" class="card" data-test="step8">
      <h3>8 · 复制到另一个专题（独立空榜单）</h3>
      <p class="small muted">复制的是“配置”：类型、属性、选项、筛选与量表。<b>不带</b>对象、评分、审核或原权限；副本有新的模块身份，与来源互不影响。</p>
      <div class="row" style="margin:8px 0"><select v-model="copyTo" style="max-width:240px"><option value="b">租房私人专题</option><option value="c">新建专题（空）</option></select><button class="btn" @click="doCopy" data-test="copy-btn" :disabled="role!=='author'">复制配置</button></div>
      <div class="grid2">
        <div class="card" style="margin:0"><b>来源：{{ moduleName }}</b><dl class="kv small"><dt>类型</dt><dd data-test="src-types">{{ types.length }}</dd><dt>属性</dt><dd data-test="src-fields">{{ types.reduce((a,t)=>a+t.fields.length,0) }}</dd><dt>对象</dt><dd>{{ objects.length }}</dd><dt>评价</dt><dd>{{ reviews.length }}</dd></dl></div>
        <div class="card" style="margin:0" v-if="copies.length" data-test="copy-result"><b>副本：{{ copies[0].target }} <span class="chip green">新身份 {{ copies[0].id }}</span></b>
          <dl class="kv small"><dt>类型</dt><dd>{{ copies[0].types }}</dd><dt>属性</dt><dd data-test="copy-fields">{{ copies[0].fields }}</dd><dt>对象</dt><dd data-test="copy-objects">{{ copies[0].objects }}</dd><dt>评价</dt><dd data-test="copy-reviews">{{ copies[0].reviews }}</dd></dl></div>
        <div class="empty" v-else>尚未复制。</div>
      </div>
      <div class="row" style="margin-top:10px"><button class="btn ghost sm" @click="addSourceField" :disabled="role!=='author'" data-test="src-change">修改来源：给“番剧”加一个属性</button>
        <span v-if="sourceFieldAdded" class="note ok small">来源已变化（属性 +1），已生成的副本保持原样，不受影响。</span></div>
      <p class="small muted" style="margin-top:6px">收藏同理：收藏是配置快照，来源后续修改不影响收藏或副本。</p>
    </div>
    </template>
  </section>`
}
