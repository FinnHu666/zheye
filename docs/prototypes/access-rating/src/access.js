import { ref, reactive, computed } from 'vue'

/* T02 简单管理员与受限访问（0.7.0 · FINNS-11 · REQ-016） */
export default {
  name: 'AccessPage',
  setup() {
    const users = reactive([
      { id: 'u5', name: '你（管理员）', email: 'admin@example.test', role: 'admin', allowed: true },
      { id: 'u2', name: '小周', email: 'zhou@example.test', role: 'user', allowed: true },
      { id: 'u3', name: '阿远', email: 'yuan@example.test', role: 'user', allowed: true },
      { id: 'u1', name: '小林', email: 'lin@example.test', role: 'user', allowed: false },
      { id: 'u4', name: '小陈', email: 'chen@example.test', role: 'user', allowed: false },
      { id: 'u6', name: '小何', email: 'he@example.test', role: 'user', allowed: false }
    ])
    const viewerId = ref('u5')              // 模拟当前登录者；'guest' = 游客
    const tab = ref('home')                 // home | restricted | admin | personal
    const direct = ref('/collections/internal')
    const directResult = ref(null)
    const q = ref('')
    const page = ref(1)
    const pageSize = 4
    const audit = ref([{ at: '10-13 10:02', op: '你（管理员）', target: '小周', field: '受限访问资格', before: '无', after: '允许' }])
    const edit = ref(null)                  // { user, target, error }
    const failNext = ref(false)
    const toast = ref('')

    const viewer = computed(() => viewerId.value === 'guest' ? null : users.find(u => u.id === viewerId.value))
    const canRestricted = computed(() => !!viewer.value && (viewer.value.allowed || viewer.value.role === 'admin'))
    const isAdmin = computed(() => viewer.value?.role === 'admin')
    const adminCount = computed(() => users.filter(u => u.role === 'admin').length)
    const filtered = computed(() => users.filter(u => !q.value || (u.name + u.email).toLowerCase().includes(q.value.toLowerCase())))
    const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
    const rows = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
    const nav = computed(() => [
      { id: 'home', label: '首页', show: true },
      { id: 'restricted', label: '内部资料专题', show: true, lock: !canRestricted.value },
      { id: 'admin', label: '访问管理', show: isAdmin.value },
      { id: 'personal', label: '个人资料', show: !!viewer.value }
    ].filter(n => n.show))

    const flash = (m) => { toast.value = m; setTimeout(() => { if (toast.value === m) toast.value = '' }, 2600) }
    const switchViewer = (id) => { viewerId.value = id; if ((tab.value === 'admin' && users.find(u => u.id === id)?.role !== 'admin') || (tab.value === 'personal' && !id)) tab.value = 'home'; directResult.value = null }
    const openEdit = (u) => { edit.value = { user: u, target: u.allowed ? 'deny' : 'allow', error: '' } }
    const save = () => {
      const e = edit.value
      if (failNext.value) { e.error = '保存失败（模拟）。你的选择已保留，可直接重试。'; return }
      const next = e.target === 'allow'
      audit.value.unshift({ at: '10-13 ' + new Date(2026, 9, 13, 10, 3 + audit.value.length).toTimeString().slice(0, 5), op: viewer.value.name, target: e.user.name, field: '受限访问资格', before: e.user.allowed ? '允许' : '无', after: next ? '允许' : '无' })
      e.user.allowed = next
      flash(`已${next ? '授予' : '撤销'}「${e.user.name}」的受限访问资格；其下一次请求即生效，无需退出。`)
      edit.value = null
    }
    const demote = (u) => {
      if (u.role === 'admin' && adminCount.value <= 1) { flash('已拒绝：这是最后一个管理员，不能取消其管理员身份（最后管理员保护）。'); return }
      flash('（原型）管理员身份的授予方式属设计阶段：首个管理员由服务端初始化，不开放注册提权。')
    }
    const goDirect = () => {
      const p = direct.value.trim()
      if (p.startsWith('/admin')) directResult.value = !viewer.value ? { code: 401, text: '未登录：跳转登录页，登录后返回。' } : isAdmin.value ? { code: 200, text: '管理员：显示访问管理页。' } : { code: 403, text: '无权限：普通用户直达管理地址被拒绝（接口同样拒绝）。' }
      else if (p.startsWith('/collections/internal')) directResult.value = !viewer.value ? { code: 401, text: '未登录：跳转登录页，登录后返回。' } : canRestricted.value ? { code: 200, text: '允许：显示受限专题。' } : { code: 403, text: '无权限：显示拒绝页，仍可使用登录/退出、个人资料入口。' }
      else directResult.value = { code: 200, text: '公开地址：任何人可访问。' }
    }
    return { users, viewerId, viewer, tab, direct, directResult, q, page, pages, rows, audit, edit, failNext, toast, canRestricted, isAdmin, nav, adminCount, switchViewer, openEdit, save, demote, goDirect, pageSize }
  },
  template: `
  <section class="page">
    <div class="page-head">
      <div><span class="version">0.7.0</span><span class="chip blue">FINNS-11 · 原型 T02</span><h2>简单管理员与受限内容访问</h2></div>
      <p class="lead">采用“普通用户 / 管理员 + 单一受限访问资格”的草案。角色切换是模拟，不使用真实账户，也不证明接口权限和 CSRF 已实现。</p>
    </div>

    <div class="card">
      <div class="row sb">
        <div class="row"><b>以谁的视角：</b>
          <div class="seg" style="border-color:var(--line)">
            <button v-for="v in [{id:'guest',n:'游客'},{id:'u1',n:'小林（无资格）'},{id:'u2',n:'小周（有资格）'},{id:'u5',n:'管理员'}]" :key="v.id"
              :class="{on:viewerId===v.id}" :style="{color:viewerId===v.id?'#fff':'var(--ink)',background:viewerId===v.id?'var(--green)':'#fff'}" @click="switchViewer(v.id)" :data-test="'view-'+v.id">{{ v.n }}</button>
          </div></div>
        <span class="chip" :class="viewer ? (isAdmin?'green':'blue') : ''">{{ viewer ? viewer.name + ' · ' + (isAdmin?'管理员':'普通用户') : '游客' }}</span>
      </div>
      <div class="tabs" style="margin-top:12px" role="tablist">
        <button v-for="n in nav" :key="n.id" class="tab" :class="{on:tab===n.id}" @click="tab=n.id" :data-test="'nav-'+n.id">{{ n.label }}<span v-if="n.lock"> 🔒</span></button>
        <button class="tab" disabled style="opacity:.5" title="评分审核属于 0.8.0，与访问管理分开">评分审核（0.8.0）</button>
        <button v-if="viewer" class="tab" @click="switchViewer('guest')">退出登录</button>
        <button v-else class="tab" @click="switchViewer('u1')">登录</button>
      </div>
    </div>

    <div v-if="toast" class="note ok" style="margin-top:12px" role="status" data-test="toast">{{ toast }}</div>

    <div class="card" v-if="tab==='home'">
      <h3>首页</h3><p class="muted">公开内容对所有人可见；“内部资料专题”在导航中显示锁标记，进入后按资格判断。</p>
    </div>

    <div class="card" v-if="tab==='restricted'" data-test="restricted-page">
      <template v-if="!viewer">
        <h3>需要登录</h3><p class="muted">此专题为受限内容。请登录后重试（模拟：点击上方“登录”）。</p>
      </template>
      <template v-else-if="canRestricted">
        <h3>内部资料专题（示例）</h3><span class="chip green">你有访问资格</span>
        <p class="muted" style="margin-top:8px">这里是受限内容的占位正文。列表、摘要、关联与总数对无资格者同样隐藏。</p>
      </template>
      <template v-else>
        <h3>无权访问此内容</h3>
        <p class="muted">你的账号没有该专题的访问资格。你仍可使用登录/退出、个人资料和公开内容。</p>
        <div class="row" style="margin-top:8px"><button class="btn ghost sm" @click="tab='personal'">个人资料</button><button class="btn ghost sm" @click="tab='home'">回首页</button></div>
      </template>
    </div>

    <div v-if="tab==='personal'" class="card"><h3>个人资料</h3><p class="muted">无论有无资格都可进入；管理员也<b>不能</b>由此查看他人私人草稿、完整 Key 或本地工具。</p></div>

    <template v-if="tab==='admin' && isAdmin">
      <div class="card" data-test="admin-page">
        <div class="row sb"><h3>用户与受限访问资格</h3>
          <label class="row small"><input type="checkbox" v-model="failNext"> 模拟下次保存失败</label></div>
        <div class="row" style="margin-bottom:8px"><input type="search" v-model="q" placeholder="搜索昵称或邮箱" @input="page=1" style="max-width:260px" data-test="user-search"></div>
        <div class="tbl-wrap"><table>
          <thead><tr><th>用户</th><th>身份</th><th>受限访问资格</th><th></th></tr></thead>
          <tbody>
            <tr v-for="u in rows" :key="u.id">
              <td><b>{{ u.name }}</b><div class="small muted">{{ u.email }}</div></td>
              <td><span class="chip" :class="u.role==='admin'?'green':''">{{ u.role==='admin'?'管理员':'普通用户' }}</span></td>
              <td><span class="chip" :class="u.allowed?'green':'amber'">{{ u.allowed?'允许':'无' }}</span></td>
              <td class="row" style="justify-content:flex-end">
                <button class="btn sm ghost" @click="openEdit(u)" :data-test="'edit-'+u.id">修改资格</button>
                <button v-if="u.role==='admin'" class="btn sm warn" @click="demote(u)">取消管理员</button>
              </td>
            </tr>
            <tr v-if="!rows.length"><td colspan="4"><div class="empty">没有匹配的用户。</div></td></tr>
          </tbody></table></div>
        <div class="row sb" style="margin-top:8px"><span class="small muted">第 {{ page }} / {{ pages }} 页 · 管理员 {{ adminCount }} 人</span>
          <span class="row"><button class="btn ghost sm" :disabled="page<=1" @click="page--">上一页</button><button class="btn ghost sm" :disabled="page>=pages" @click="page++">下一页</button></span></div>
        <p class="note info small" style="margin-top:10px">撤销后无需先退出：切换到被撤销用户的视角，其下一次访问立即变为拒绝。</p>
      </div>

      <div class="card">
        <h3>操作记录</h3>
        <div class="tbl-wrap"><table data-test="audit">
          <thead><tr><th>时间</th><th>操作者</th><th>目标</th><th>项目</th><th>前 → 后</th></tr></thead>
          <tbody><tr v-for="(a,i) in audit" :key="i"><td class="mono">{{ a.at }}</td><td>{{ a.op }}</td><td>{{ a.target }}</td><td>{{ a.field }}</td><td>{{ a.before }} → {{ a.after }}</td></tr></tbody>
        </table></div>
      </div>
    </template>

    <div class="card">
      <h3>直接访问地址（模拟）</h3>
      <div class="row"><input type="text" v-model="direct" style="max-width:320px" data-test="direct-input"><button class="btn sm" @click="goDirect" data-test="direct-go">访问</button>
        <button class="btn ghost sm" @click="direct='/admin/users'">换成管理地址</button></div>
      <div v-if="directResult" class="note" :class="directResult.code===200?'ok':'bad'" style="margin-top:8px" data-test="direct-result"><b>{{ directResult.code }}</b> · {{ directResult.text }}</div>
      <p class="small muted" style="margin-top:6px">真实实现中导航、页面、直达与 API 必须一致授权，列表/摘要/总数/缓存不泄漏受限信息。</p>
    </div>

    <div v-if="edit" class="overlay" @click.self="edit=null">
      <div class="modal" role="dialog" aria-label="修改资格">
        <h3>修改「{{ edit.user.name }}」的受限访问资格</h3>
        <dl class="kv"><dt>当前</dt><dd>{{ edit.user.allowed?'允许':'无' }}</dd><dt>目标</dt><dd>{{ edit.target==='allow'?'允许':'无' }}</dd></dl>
        <div class="row" style="margin:10px 0"><label class="row"><input type="radio" value="allow" v-model="edit.target"> 授予</label><label class="row"><input type="radio" value="deny" v-model="edit.target"> 撤销</label></div>
        <div v-if="edit.error" class="note bad" data-test="save-error">{{ edit.error }}</div>
        <div class="row" style="justify-content:flex-end;margin-top:12px"><button class="btn ghost" @click="edit=null">取消</button><button class="btn" @click="save" data-test="save-btn">确认修改</button></div>
      </div>
    </div>
  </section>`
}
