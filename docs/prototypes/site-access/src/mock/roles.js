// 0.7.0 FINNS-11 角色与权限（原型 R3）。
// 每个用户一个角色。管理员可修改各角色权限（R2），并可新增、改名、删除角色（R3，D13）。
// system：ADMIN 为管理角色、MEMBER 为注册默认角色，二者可改名但不可删除。

export const ROLES = [
  { key: 'OWNER', label: '站长', description: '唯一站点所有者，管理管理员；仅通过专门入口转交，不可直接分配或删除', system: 'owner' },
  { key: 'RESTRICTED', label: '受限成员', description: '可浏览，暂停发布与互动' },
  { key: 'MEMBER', label: '普通成员', description: '注册用户的默认角色', system: 'default' },
  { key: 'TRUSTED', label: '受信成员', description: '可阅读内部受限专题' },
  { key: 'ADMIN', label: '管理员', description: '管理用户、角色与权限', system: 'admin' },
]
// 仅作前端兜底；角色名称以接口返回为准（可能已被改名）。
export const roleLabel = (key) => (key && key !== 'GUEST' ? ROLES.find((role) => role.key === key)?.label || key : '游客')

// 权限目录。lockAll：所有角色都必须拥有（网站公开、基本登录能力）；lockAdmin：管理员角色必须拥有（防止锁死管理入口）。
export const CATALOG = [
  { key: 'content.public', group: '页面', label: '浏览公开内容', detail: '发现、专题广场、公开专题、VPS 指南', guest: true, lockAll: true },
  { key: 'profile.manage', group: '页面', label: '个人资料与我的内容', detail: '登录、退出、个人资料始终可用', lockAll: true },
  { key: 'selection.manage', group: '操作', label: '自选专题', detail: '加入、移除、排序自选' },
  { key: 'post.create', group: '操作', label: '发布文章/话题', detail: '“开始创作”入口与发布接口' },
  { key: 'post.interact', group: '操作', label: '点赞、讨论、收录', detail: '内容详情中的互动' },
  { key: 'collection.manage', group: '页面', label: '创建和编辑专题', detail: '我创建的专题、专题编辑、导入' },
  { key: 'ai.use', group: '页面', label: '使用 AI', detail: 'AI 配置、AI 创建专题' },
  { key: 'content.restricted', group: '内容', label: '阅读受限专题', detail: '列表、总数、详情、直达、自选' },
  { key: 'admin.users', group: '管理', label: '用户与角色分配', detail: '查看用户、调整用户角色', lockAdmin: true },
  { key: 'admin.audit', group: '管理', label: '查看操作记录', detail: '筛选、查询角色与权限变更', lockAdmin: true },
  { key: 'admin.roles', group: '管理', label: '管理角色', detail: '修改权限，新增、改名、删除角色', lockAdmin: true },
  { key: 'content.review', group: '管理', label: '内容审批', detail: '首次专题审批、敏感复审及内容下架恢复；禁止自审' },
]

export const DEFAULT_PERMISSIONS = {
  RESTRICTED: ['content.public', 'profile.manage', 'selection.manage'],
  MEMBER: ['content.public', 'profile.manage', 'selection.manage', 'post.create', 'post.interact', 'collection.manage', 'ai.use'],
  TRUSTED: ['content.public', 'profile.manage', 'selection.manage', 'post.create', 'post.interact', 'collection.manage', 'ai.use', 'content.restricted'],
  ADMIN: CATALOG.filter((perm) => perm.key !== 'content.review').map((perm) => perm.key),
  OWNER: CATALOG.map((perm) => perm.key),
}

export const isLocked = (roleKey, perm) => !!(roleKey === 'OWNER' || perm.lockAll || (perm.lockAdmin && roleKey === 'ADMIN'))
export const MAX_ROLES = 12

export const permLabel = (key) => CATALOG.find((perm) => perm.key === key)?.label || key
