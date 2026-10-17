// 原型内存数据库：只存在于当前浏览器 localStorage，用于刷新/直达/多标签页演示。
// 不连接 FINNS 后端或任何数据库。
import { DEFAULT_PERMISSIONS, ROLES } from './roles'

const DB_KEY = 'finns-proto-site-access-db-v4'
const MINUTE = 60 * 1000
const DAY = 24 * 60 * MINUTE

const daysAgo = (n) => new Date(Date.now() - n * DAY).toISOString()

const NAMES = ['林思远', '周以宁', '阿远', '陈默', '何小满', '许知行', '宋青', '唐一', '韩露', '方舟', '叶未', '沈池', '江屿', '白鹭', '顾川', '程橙', '苏木', '陆离', '温言', '秦晓', '袁野', '杜若', '邵阳', '孟夏']

function seedUsers() {
  const fixed = [
    { id: 1, displayName: '你（站长）', email: 'admin@finns.test', role: 'ADMIN', createdAt: daysAgo(210), bio: '维护 FINNS。' },
    { id: 2, displayName: '周以宁', email: 'zhou@finns.test', role: 'TRUSTED', createdAt: daysAgo(160), bio: '运维与部署笔记。' },
    { id: 3, displayName: '阿远', email: 'yuan@finns.test', role: 'MEMBER', createdAt: daysAgo(120), bio: '' },
    { id: 4, displayName: '陈默', email: 'chen@finns.test', role: 'RESTRICTED', createdAt: daysAgo(90), bio: '' },
  ]
  const rest = NAMES.slice(4).map((name, index) => ({ id: 5 + index, displayName: name, email: `user${5 + index}@finns.test`, role: index % 7 === 3 ? 'TRUSTED' : 'MEMBER', createdAt: daysAgo(80 - index * 3), bio: '' }))
  return [...fixed, ...rest]
}

function seedPosts() {
  const raw = [
    ['ARTICLE', '用一台 2 核 VPS 跑起个人知识库的完整记录', '从选机房、装 PostgreSQL 到配置备份，记录我踩过的每一个坑。', 2, 46],
    ['TOPIC', '你们的 AI 写作工作流里，最难的是哪一步？', '是想清楚要写什么，还是把 AI 的初稿改成自己的话？', 3, 18],
    ['ARTICLE', 'Spring Boot 3 的会话与 CSRF：一次讲清', '为什么 SPA + Cookie 会话仍然需要 CSRF 令牌，以及怎么配。', 1, 31],
    ['ARTICLE', '把读过的文章串成一条学习路径', '专题不是收藏夹：如何用“章节 + 注释”组织一组资料。', 2, 22],
    ['TOPIC', '值得长期订阅的技术周刊有哪些？', '欢迎补充，我先列三个。', 5, 9],
    ['ARTICLE', 'Vue Router 直达刷新为什么会 404', '前端路由与服务端回退的关系，附 Spring 的处理方式。', 3, 27],
    ['TOPIC', '小团队需要多复杂的权限系统？', '我们只有四种角色，够用吗？', 6, 12],
    ['ARTICLE', 'PostgreSQL 备份恢复演练清单', '备份成功不等于能恢复：一份可以照着做的演练步骤。', 1, 38],
  ]
  return raw.map(([type, title, summary, authorId, likes], index) => ({
    id: 201 + index, type, title, summary, authorId, likes,
    body: `${summary}\n\n（原型示例正文）这里展示的是模拟内容，用于体验真实页面的布局与交互。`,
    createdAt: daysAgo(index * 2 + 1),
  }))
}

function seedCollections() {
  return [
    { id: 101, name: '从零部署个人站点', description: '域名、VPS、反向代理到备份的一条完整路径', ownerId: 2, format: 'GUIDE', visibility: 'PUBLIC', status: 'PUBLISHED', postIds: [201, 208], audience: '第一次自己部署网站的人', goal: '能独立上线并恢复一个小站' },
    { id: 102, name: 'AI 辅助写作入门', description: '从想法到成稿，让 AI 做助手而不是作者', ownerId: 3, format: 'READING_PATH', visibility: 'PUBLIC', status: 'PUBLISHED', postIds: [202] },
    { id: 103, name: 'Spring 安全基础', description: '会话、CSRF 与授权的最小知识集', ownerId: 1, format: 'RESOURCE_LIST', visibility: 'PUBLIC', status: 'PUBLISHED', postIds: [203, 206] },
    { id: 104, name: '值得长期读的周刊', description: '社区推荐的技术周刊与博客', ownerId: 5, format: 'CUSTOM', visibility: 'PUBLIC', status: 'PUBLISHED', postIds: [205] },
    { id: 105, name: '内部：运维值班手册', description: '服务器、备份与告警处理步骤（仅受信成员可读）', ownerId: 2, format: 'GUIDE', visibility: 'RESTRICTED', status: 'PUBLISHED', postIds: [208] },
    { id: 106, name: '内部：供应商比价记录', description: '云厂商报价与续费记录（仅受信成员可读）', ownerId: 1, format: 'RESOURCE_LIST', visibility: 'RESTRICTED', status: 'PUBLISHED', postIds: [] },
    { id: 107, name: '我的读书路径（草稿）', description: '还没整理完', ownerId: 3, format: 'READING_PATH', visibility: 'PUBLIC', status: 'DRAFT', postIds: [] },
  ]
}

// 操作记录示例：用户角色分配（ROLE_ASSIGN）与角色权限修改（ROLE_PERMISSION），用于演示筛选与查询。
function seedAudit() {
  const assign = (days, operatorId, targetId, before, after, note = '') => ({ type: 'ROLE_ASSIGN', at: daysAgo(days), operatorId, targetId, before, after, note })
  const perm = (days, operatorId, role, added, removed, note = '') => ({ type: 'ROLE_PERMISSION', at: daysAgo(days), operatorId, role, added, removed, note })
  return [
    assign(1.2, 1, 9, 'MEMBER', 'TRUSTED', '协助整理内部专题'),
    perm(2.5, 1, 'RESTRICTED', ['selection.manage'], [], '受限成员也可以收藏自选'),
    assign(3, 1, 4, 'MEMBER', 'RESTRICTED', '多次发布广告，先限制发布'),
    assign(5, 1, 12, 'RESTRICTED', 'MEMBER', '申诉通过，恢复'),
    perm(8, 1, 'MEMBER', [], ['content.restricted'], '内部资料只给受信成员'),
    assign(11, 1, 12, 'MEMBER', 'RESTRICTED', '刷屏评论'),
    assign(15, 1, 7, 'MEMBER', 'TRUSTED', ''),
    perm(19, 1, 'TRUSTED', ['content.restricted'], [], '新增受信成员角色权限'),
    assign(24, 1, 15, 'MEMBER', 'TRUSTED', '参与运维'),
    assign(30, 1, 2, 'MEMBER', 'TRUSTED', '负责运维值班'),
    assign(38, 1, 6, 'TRUSTED', 'MEMBER', '不再参与内部资料整理'),
    assign(44, 1, 1, 'MEMBER', 'ADMIN', '部署时指定首位管理员'),
  ].map((entry, index) => {
    // 记录写入时的角色名称快照：角色日后改名或删除，历史记录仍显示当时的名称
    const name = (key) => ROLES.find((role) => role.key === key)?.label
    return { id: index + 1, ...entry, beforeName: name(entry.before), afterName: name(entry.after), roleName: name(entry.role) }
  })
}

export function seed() {
  const launch = new Date(Date.now() - 45 * DAY)
  launch.setHours(0, 0, 0, 0)
  return {
    version: 4,
    users: seedUsers(),
    posts: seedPosts(),
    comments: [
      { id: 1, postId: 201, authorId: 3, body: '备份那一节太有用了，收藏。', createdAt: daysAgo(1) },
      { id: 2, postId: 207, authorId: 2, body: '四种够用，关键是后端每个接口都要查。', createdAt: daysAgo(2) },
    ],
    collections: seedCollections(),
    selections: { 3: [101, 103] },
    vps: [
      { id: 1, provider: 'Vultr', planName: 'Cloud Compute', region: '东京', cpu: 1, memoryGb: 2, storageGb: 55, monthlyPrice: '12.00', score: 4.6, description: '延迟稳定，适合个人站点。', authorName: '周以宁' },
      { id: 2, provider: 'Hetzner', planName: 'CX22', region: '赫尔辛基', cpu: 2, memoryGb: 4, storageGb: 40, monthlyPrice: '4.59', score: 4.4, description: '性价比高，国内访问偏慢。', authorName: '阿远' },
    ],
    audit: seedAudit(),
    roles: JSON.parse(JSON.stringify(ROLES)),
    permissions: JSON.parse(JSON.stringify(DEFAULT_PERMISSIONS)),
    permissionsVersion: 1,
    visits: { total: 12846, since: launch.toISOString(), visitors: {} },
    session: { userId: null },
    csrf: Math.random().toString(36).slice(2),
    settings: {
      rule: 'idle30', excludeAdmin: false,
      failVisitWrite: false, failVisitRead: false, slowStats: false,
      dropCsrfOnce: false, failRoleSaveOnce: false,
    },
    clockOffset: 0,
    log: [],
    nextId: 1000,
  }
}

let db
export function getDb() {
  if (db) return db
  try { const saved = JSON.parse(localStorage.getItem(DB_KEY) || 'null'); db = saved?.version === 4 ? saved : seed() }
  catch { db = seed() }
  return db
}

// 多标签页共享同一份模拟数据：写之前重新读取，避免旧标签页覆盖新状态。
export function reloadDb() { db = null; return getDb() }
export function saveDb() { try { localStorage.setItem(DB_KEY, JSON.stringify(db)) } catch { /* 存储不可用时仅保留内存状态 */ } }
export function resetDb() { db = seed(); saveDb(); return db }
export const now = () => Date.now() + (getDb().clockOffset || 0)
export const nextId = () => { const d = getDb(); d.nextId += 1; return d.nextId }
export { MINUTE, DAY }
