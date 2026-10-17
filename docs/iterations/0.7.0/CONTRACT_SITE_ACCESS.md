# FINNS 0.7.0｜网站访问量与角色访问管理：详细设计与接口契约（G4）

日期：2026-10-15。契约修订：2。依据：[需规修订 5](REQUIREMENTS.md)、[概要设计修订 2](DESIGN_SITE_ACCESS.md)（G3 通过，D16）、确认原型 [R2–R4](PROTOTYPE_SITE_ACCESS.md)。状态：已定稿并按 B1–B6 实施（D17）；实施偏差见 [实施与验证记录](IMPLEMENTATION_SITE_ACCESS.md) §2。

约定：JSON 字段 camelCase；时间为 ISO-8601 UTC；错误响应沿用现有 `{ "status": <int>, "message": "<中文>" }`（`ApiExceptionHandler`）。所有非 GET 请求沿用 Cookie-to-header CSRF（例外见 §2.1）。

## 1. 权限目录（代码枚举 `Permission`）

| 代码 | 名称 | 锁定 | 游客 |
| --- | --- | --- | --- |
| `content.public` | 浏览公开内容 | 所有角色 | 是 |
| `profile.manage` | 个人资料与我的内容 | 所有角色 | 否 |
| `selection.manage` | 自选专题 | — | 否 |
| `post.create` | 发布文章/话题 | — | 否 |
| `post.interact` | 点赞、讨论、收录 | — | 否 |
| `collection.manage` | 创建和编辑专题 | — | 否 |
| `ai.use` | 使用 AI | — | 否 |
| `content.restricted` | 阅读受限专题 | — | 否 |
| `admin.users` | 用户与角色分配 | 管理员角色 | 否 |
| `admin.audit` | 查看操作记录 | 管理员角色 | 否 |
| `admin.roles` | 管理角色（权限、增删改名） | 管理员角色 | 否 |

种子权限：受限成员 = public/profile/selection；普通成员 = 受限成员 + post.create/post.interact/collection.manage/ai.use（等同 0.6.0 登录用户）；受信成员 = 普通成员 + content.restricted；管理员 = 全部。

## 2. 网站访问计数（FINNS-10）

### 2.1 `POST /api/site-visits`

- 请求体：`{}`（可空）。必须 `Content-Type: application/json`；若有 `Origin` 头必须与站点同源，否则 403。该路径从 CSRF 校验中排除（只增加计数、不读写身份数据）。
- 逻辑：请求不带有效 `FINNS_VISIT` Cookie → 计数 +1，并下发 Cookie；带有 → 不计数，只续期。
- Cookie：`FINNS_VISIT=<随机 128 位 base64url>; Max-Age=1800; Path=/; HttpOnly; SameSite=Lax`，HTTPS 环境加 `Secure`（跟随 `server.servlet.session.cookie.secure` 或请求是否安全）。
- 响应 200：`{ "counted": true, "total": 12847 }`。
- 失败：数据库异常 → 503 `访问计数暂不可用`（前端忽略）。

### 2.2 访问续期过滤器 `VisitCookieRefreshFilter`

对 `/api/**` 中携带 `FINNS_VISIT` 的请求，在响应中重写同值 Cookie（`Max-Age=1800`）。不访问数据库，不影响响应体。

### 2.3 `GET /api/stats`（扩展）

```json
{ "creatorCount": 5, "contentCount": 8, "collectionCount": 4,
  "visits": { "total": 12847, "since": "2026-11-01T00:00:00Z" } }
```

`collectionCount` 只计公开且已发布的专题（按 `collection_publications.visibility='PUBLIC'`）。计数读取异常时 `visits: null`，其余字段照常返回。

### 2.4 数据（Flyway `V11__site_visits.sql`）

```sql
create table site_visit_counter (
  id smallint primary key check (id = 1),
  total bigint not null default 0 check (total >= 0),
  counted_since timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
insert into site_visit_counter (id, total, counted_since, updated_at) values (1, 0, current_timestamp, current_timestamp);
```

计数：`update site_visit_counter set total = total + 1, updated_at = current_timestamp where id = 1`，在同一事务中执行 `select total`。H2 开发库由实体 `SiteVisitCounter` 自动建表，初始化器补插 id=1 行。

## 3. 角色与权限数据（Flyway `V12__roles_and_access.sql`）

```sql
create table roles (
  id bigserial primary key,
  code varchar(40) not null unique,
  name varchar(20) not null,
  description varchar(60) not null default '',
  system_kind varchar(12) check (system_kind in ('ADMIN','DEFAULT')),
  sort_order int not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);
create unique index ux_roles_name_lower on roles (lower(name));
create unique index ux_roles_system_kind on roles (system_kind) where system_kind is not null;

create table role_permissions (
  role_id bigint not null references roles(id) on delete cascade,
  permission varchar(40) not null,
  primary key (role_id, permission)
);

create table access_control_state (id smallint primary key check (id = 1), version bigint not null);
insert into access_control_state values (1, 1);

alter table user_accounts add column role_id bigint references roles(id);
-- 种子四个角色及权限（代码 RESTRICTED/MEMBER/TRUSTED/ADMIN；MEMBER=DEFAULT，ADMIN=ADMIN）
-- update user_accounts set role_id = (select id from roles where code='MEMBER');
-- 修订 2：role_id 保持可空（回退到 0.6.0 时旧注册流程不写该列）；空角色按注册默认角色处理，启动时补齐。
create index idx_user_accounts_role on user_accounts(role_id);

create table access_audit_log (
  id bigserial primary key,
  type varchar(20) not null check (type in ('ROLE_ASSIGN','ROLE_PERMISSION','ROLE_DEFINE')),
  action varchar(10) check (action in ('CREATE','UPDATE','DELETE')),
  operator_id bigint references user_accounts(id) on delete set null,
  operator_name varchar(40) not null,
  target_user_id bigint references user_accounts(id) on delete set null,
  target_user_name varchar(40),
  role_id bigint, role_code varchar(40), role_name varchar(20),
  before_role_code varchar(40), before_role_name varchar(20),
  after_role_code varchar(40), after_role_name varchar(20),
  copy_from_code varchar(40), copy_from_name varchar(20),
  move_to_code varchar(40), move_to_name varchar(20),
  added_permissions varchar(500), removed_permissions varchar(500),
  moved_count int, moved_names varchar(500),
  note varchar(200) not null default '',
  created_at timestamp with time zone not null
);
create index idx_audit_created on access_audit_log(created_at desc, id desc);
create index idx_audit_type_created on access_audit_log(type, created_at desc);

alter table community_collections add column visibility varchar(12) not null default 'PUBLIC' check (visibility in ('PUBLIC','RESTRICTED'));
alter table collection_publications add column visibility varchar(12) not null default 'PUBLIC' check (visibility in ('PUBLIC','RESTRICTED'));
```

`role_id/role_code` 不设外键（角色删除后记录保留名称快照）。权限列表以逗号分隔的代码保存（目录固定、长度有限）；查询时按名称快照与代码匹配关键词。

新角色 `code` = `R` + 序列号，系统生成，不可修改。H2：实体 `Role`、`AccessAuditEntry` 等由 ddl update 建表，`AccessBootstrap` 启动初始化补齐四个种子角色、默认权限与现有用户的 role_id（幂等）。

## 4. 身份与执行

- `AccessContext`（每请求）：`userId, displayName, roleId, roleCode, roleName, systemKind, EnumSet<Permission> permissions`；未登录为游客上下文（只有 `content.public`）。由 `CurrentUserService.resolve(session)` 一次查询读取（user join role join role_permissions）。
- `@RequirePermission(Permission...)`（方法或类级）+ `PermissionInterceptor`：未登录 401 `请先登录`；会话用户不存在 401 `登录已失效`；缺少权限 403 `当前角色没有此操作权限`。
- 覆盖性：测试遍历 `RequestMappingHandlerMapping` 的全部 `/api/**` 处理器，断言每个都有注解或在公开白名单中（白名单：`/api/auth/*`、`/api/site-visits`、`/api/stats`、公开内容 GET、`/api/vps` GET、`/api/private/tools`（独立密钥））。
- 注册：`UserAccount` 构造时由服务端设为 `system_kind='DEFAULT'` 的角色；`RegisterRequest` 不含角色字段，多余字段被忽略。
- 首位管理员：`finns.access.bootstrap-admin-email`（环境变量 `FINNS_BOOTSTRAP_ADMIN_EMAIL`）。启动时若“拥有 admin.roles 的用户数”为 0 且该邮箱账户存在 → 设为 ADMIN 角色，写一条 `ROLE_ASSIGN`（操作者名“系统初始化”）。邮箱未配置或账户不存在时记录警告，不阻止启动。

## 5. 管理接口

所有写接口在一个事务中执行，并以 `select version from access_control_state where id=1 for update` 串行化。字段校验失败返回 400，并附第一条具体原因。

### 5.1 `GET /api/auth/me`（扩展）

```json
{ "id": 1, "email": "a@x.test", "displayName": "站长",
  "role": { "code": "ADMIN", "name": "管理员" },
  "permissions": ["content.public", "profile.manage", "admin.roles"] }
```

`register`/`login` 返回同一结构。

### 5.2 用户

`GET /api/admin/users?q=&role=&page=0&size=10`（`admin.users`；q ≤80，size ≤50）

```json
{ "items": [ { "id": 2, "displayName": "周以宁", "email": "zhou@x.test",
    "role": { "code": "TRUSTED", "name": "受信成员" }, "canManage": false, "createdAt": "…" } ],
  "page": 0, "size": 10, "total": 24, "managerCount": 1 }
```

`PATCH /api/admin/users/{id}/role`（`admin.users`）请求 `{ "roleCode": "MEMBER", "expectedRoleCode": "TRUSTED", "note": "" }`，响应为单个用户项。

| 情况 | 状态 | 文案 |
| --- | --- | --- |
| 用户不存在 | 404 | 用户不存在 |
| 角色不存在 | 400 | 目标角色不存在，可能已被删除，请刷新后重试 |
| expectedRoleCode 不符 | 409 | 该用户角色已被修改为「X」，请核对后重试 |
| 变更后管理者为 0 | 422 | 这是最后一位能管理角色的用户…… |
| 角色未变 | 200 | 不写记录 |

### 5.3 角色与权限

`GET /api/admin/roles`（`admin.users`）

```json
{ "version": 7, "maxRoles": 12,
  "catalog": [ { "code": "post.create", "group": "操作", "label": "发布文章/话题", "detail": "…", "lockAll": false, "lockAdmin": false, "guest": false } ],
  "guest": ["content.public"],
  "roles": [ { "code": "MEMBER", "name": "普通成员", "description": "…", "systemKind": "DEFAULT", "permissions": ["…"], "userCount": 18 } ] }
```

| 接口 | 权限 | 请求 | 成功 | 主要错误 |
| --- | --- | --- | --- | --- |
| `PUT /api/admin/roles` | admin.roles | `{ version, permissions: { roleCode: [perm] }, note }`（未列出的角色保持不变） | 200，同 GET | 409 版本；400 未知权限/角色；422 关闭锁定项、管理者为 0 |
| `POST /api/admin/roles/definitions` | admin.roles | `{ version, name, description, copyFrom?, note }` | 201，同 GET + `createdCode` | 400 名称为空/>20/说明>60/复制源不存在；409 版本或重名；422 已达 12 个 |
| `PATCH /api/admin/roles/definitions/{code}` | admin.roles | `{ version, name, description, note }` | 200，同 GET；无变化不写记录 | 404；400；409 |
| `DELETE /api/admin/roles/definitions/{code}` | admin.roles | 请求体 `{ version, moveTo?, note }` | 200，同 GET | 404；422 管理员/默认角色、管理者为 0；400 有用户未给 moveTo 或 moveTo 无效；409 版本 |

每次成功写入：`version + 1`，并写入操作记录（含名称快照）。删除角色时，在同一事务中把用户批量改到 `moveTo`，`moved_names` 记录前 10 位用户。

### 5.4 操作记录

`GET /api/admin/audit?q=&type=&role=&from=YYYY-MM-DD&to=YYYY-MM-DD&page=0&size=10`（`admin.audit`；size ≤50）

- 日期按 `finns.access.audit-zone`（默认 `Asia/Shanghai`）解释为当天 00:00 至次日 00:00；from > to → 400 `开始日期不能晚于结束日期`。
- `role` 匹配 role_code、before/after/copy_from/move_to 的代码；`q` 对操作者名、目标用户名、各角色名快照、权限名称、备注、被转移用户名做不区分大小写的包含匹配（≤80 字）。
- 排序：created_at desc, id desc。响应项包含：`id, type, action, createdAt, operatorName, targetUserName, roleName, beforeRoleName, afterRoleName, copyFromName, moveToName, addedPermissions[], removedPermissions[]`（以权限名称返回）、`movedCount, movedNames[], note`；以及 `page, size, total`。

## 6. 受限内容

| 位置 | 变更 |
| --- | --- |
| `ContentController` `/api/collections` 列表与 total | 增加条件 `(p.visibility='PUBLIC' or :canRestricted)` |
| `/api/collections/{id}`、`/{id}/posts`、`CollectionPublicationService` 大纲（`/api/collections/{id}/outline`） | 受限且无权限：未登录 401 `这是受限专题，请先登录后再访问`；已登录 403 `当前角色无权阅读这个专题` |
| `PersonalController` 自选列表 | 过滤掉当前无权读取的受限专题（不删除自选关系，权限恢复后重新出现） |
| 加入自选 `PUT /api/me/collections/selected/{id}` | 无权读取时 404 `专题不存在` |
| `SiteStatsController` | 只计公开 |
| `CollectionWorkspaceController` `DraftDocument` | 增加 `visibility`、`publishedVisibility`；`SaveDraftRequest` 增加 `visibility`（缺省时保持原值） |
| `PUT …/draft` | 从非 RESTRICTED 改为 RESTRICTED 且无 `content.restricted` → 403 `当前角色不能把专题设为受限` |
| `POST …/publish` | 发布时把 `visibility` 写入 `collection_publications`；若由公开变为受限且无权限 → 403 |
| 复制/导入 | 复制专题时，可见范围复制为 PUBLIC（避免无权限者通过复制得到受限）；导入新建为 PUBLIC |

## 7. 前端

| 文件 | 变更 |
| --- | --- |
| `lib/session.js`（新） | `currentUser`（ref）、`can(perm)`、`refreshSession()` |
| `main.js` | 路由 `meta.perm`；`/admin/users|roles|audit`（`/admin` 重定向）；守卫每次导航刷新会话 |
| `App.vue` | 访问上报；“管理”导航与“开始创作”按权限显示；403 区块；页头角色名；提示条 `show` 类 |
| `DiscoverPage.vue` | 第 4 个统计数字，悬停提示；无互动权限时隐藏点赞/讨论/收录 |
| `PersonalNav.vue` | 按权限隐藏 AI 配置、我创建的专题 |
| `CollectionEditorPage.vue` | 可见范围字段与说明 |
| `pages/admin/*.vue`（新） | 从原型移植三页与 AdminNav，去掉模拟数据与控制台 |
| `SpaController` | 增加 `/admin`、`/admin/**` |
| `styles.css` | 合入原型中的管理页、矩阵、角色标签、可见范围样式（不带原型控制台样式） |

## 8. 验收测试映射

| ID | 需规 | 测试 | 类型 |
| --- | --- | --- | --- |
| V01 | 2.2.2 | 无 Cookie +1 且下发；有 Cookie 不计且续期；Cookie 过期后 +1 | `SiteVisitTests`（MockMvc） |
| V02 | 2.2.4 | 50 线程并发进入，总数 = 初始 + 50 | PostgreSQL 隔离库集成测试 |
| V03 | 2.2.5 | 计数异常时 stats.visits 为 null、其余字段正常；site-visits 跨源 Origin 403 | MockMvc |
| V04 | 2.2.3/5 | 首页第 4 个数字、悬停提示、“—”与 0 | `frontend/tests` + 浏览器 |
| A01 | 2.3.1 | 注册得到默认角色；请求带 role 字段被忽略 | MockMvc |
| A02 | 2.3.2/8 | 映射覆盖测试：所有 `/api/**` 有注解或在白名单；按 §1 种子对四种角色+游客逐接口断言允许/拒绝 | MockMvc 参数化 |
| A03 | 2.3.3 | 修改权限即时生效（同一会话下一请求）；锁定项 422；版本 409 | 服务 + MockMvc |
| A04 | 2.3.4 | 新增（复制/基本）、重名（大小写）、长度、上限 12、改名后 me 显示新名、删除系统角色 422、删除转移、失败回滚 | 服务 + MockMvc |
| A05 | 2.3.5/6 | 分配角色、expectedRole 409、三条路径的管理者保护、首位管理员初始化幂等 | 服务 + 启动测试 |
| A06 | 2.3.7 | 受限专题：列表/总数/统计/大纲/文章分页/自选列表/加入自选，对游客、无权、有权三类身份 | MockMvc |
| A07 | 2.3.7 设置 | 草稿设受限的权限校验、发布生效、失去权限可保持/改公开不可再设、复制得 PUBLIC | MockMvc |
| A08 | 2.3.10 | 管理员读取他人草稿/预览/Key/模块收藏仍 404/403；本地工具不受角色影响 | MockMvc |
| A09 | 2.3.11 | 三类记录写入快照；筛选：关键词、类型、角色、日期边界（时区）、分页、from>to 400 | 服务 + MockMvc |
| A10 | 2.3.12 | 管理写请求缺 CSRF 403 | MockMvc（启用 CSRF） |
| A11 | 2.3.8 | 前端：直达 403 保留地址、导航按权限、刷新 `/admin/**` 不 404、390/768/桌面 | 浏览器 |
| M01 | 迁移 | 空库与 0.6.0 数据库升级到 V12：现有用户为普通成员、计数行存在、重复启动无副作用；回退旧包可启动 | `scripts/test-postgres.ps1` 隔离库 |

## 9. 实施批次（每批独立可验证）

1. **B1 访问计数**：V11、计数服务、site-visits、续期过滤器、stats 扩展、首页数字。验证 V01–V04。
2. **B2 角色模型与执行**：V12（角色/权限/用户 role_id/状态表）、AccessContext、注解拦截器、全部接口标注、me 扩展、注册默认角色、首位管理员。验证 A01、A02、A05（初始化部分）、A08、A10。
3. **B3 管理接口**：用户分配、权限修改、角色定义、操作记录与筛选。验证 A03–A05、A09。
4. **B4 受限内容**：visibility 两列、查询过滤、草稿/发布字段、复制/导入规则。验证 A06、A07。
5. **B5 前端**：session、路由、App/Discover/PersonalNav/编辑页、管理三页、SpaController、样式。验证 V04、A11。
6. **B6 PostgreSQL 与回归**：隔离库迁移与回退演练、全量测试、构建。验证 M01 与既有测试。

每批结束更新 CHANGELOG Unreleased、交接与进度证据。发布（G8）另需授权和备份演练。

## 10. 实施前需要的授权

本契约定稿后，业务实施需要用户明确授权实施范围（建议按 B1–B6 全部，或指定批次）。不需要另外批准技术细节。实施不包括部署主站、Git 提交和 Plane 状态变更，这些各自另行确认。
