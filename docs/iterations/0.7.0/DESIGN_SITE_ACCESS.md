# FINNS 0.7.0｜网站访问量与角色访问管理：概要设计（G3）

日期：2026-10-15。设计修订：2。依据：[需规修订 5](REQUIREMENTS.md) §2.2/§2.3、确认原型 [R2/R3](PROTOTYPE_SITE_ACCESS.md)、DECISIONS D12–D14。状态：概要设计已定稿（G3 通过，D16）；未实施，未改源码/数据库。G4 见 [详细设计与接口契约](CONTRACT_SITE_ACCESS.md)。

技术基线不变：Java 17、Spring Boot 3.3、Spring Security（会话 + Cookie CSRF）、JPA + JdbcTemplate、PostgreSQL（Flyway + Hibernate validate）、开发期 H2（ddl update）、Vue 3 + Vue Router + Vite。不引入新框架或新数据库产品。

## 1. 现状（以源码为准）

| 方面 | 现状 | 位置 |
| --- | --- | --- |
| 身份 | 会话属性 `USER_ID`；各控制器调用 `AuthController.requireUser` 只校验登录 | `user/AuthController.java` |
| 授权 | Spring Security `anyRequest().permitAll()`；无角色/权限模型 | `config/SecurityConfig.java` |
| CSRF | Cookie-to-header，SPA 先取 `/api/auth/csrf` | `SecurityConfig`、`frontend/src/lib/api.js` |
| 用户表 | `user_accounts` 无角色列 | `user/UserAccount.java` |
| 首页统计 | `/api/stats` 三个数字；专题计数只看 `PUBLISHED` | `stats/SiteStatsController.java` |
| 专题可见性 | 只有草稿/发布/归档状态，无“受限”概念 | `content/CommunityCollection.java` 等 |
| SPA 回退 | 白名单路由转发 index.html，无 `/admin/**` | `config/SpaController.java` |
| 迁移 | PostgreSQL 由 Flyway 管理，当前到 V10 | `src/main/resources/db/migration` |

## 2. FINNS-10 网站访问计数

### 2.1 去重方式：访问 Cookie（推荐）

“30 分钟无活动后再进入算新访问”直接用一个滑动过期的 Cookie 表达，服务端不保存访客明细：

- `FINNS_VISIT`：HttpOnly、`SameSite=Lax`、`Path=/`、`Max-Age=1800`，值为随机标记（无个人信息）。
- 进入网站（SPA 首次加载）调用 `POST /api/site-visits`：请求不带有效 `FINNS_VISIT` → 计数 +1 并下发 Cookie；带有 → 不计数，只续期。
- 站内活动续期：一个轻量过滤器对携带 `FINNS_VISIT` 的 `/api/**` 请求续写 `Max-Age=1800`，所以浏览中的用户不会因 30 分钟窗口被重复计数，也不需要额外的“心跳”请求。
- 刷新、新标签页共享同一 Cookie → 不计；清除 Cookie/换浏览器 → 计数（符合 V2）。

备选方案（不采用）：服务端访客表（按 visitorId 记录 lastSeen）可以更精确地统计，但会产生持续增长的数据和清理任务，本需求不需要；设备指纹按需规排除。

### 2.2 存储与并发

- 新表 `site_visit_counter`（单行 `id=1`）：`total bigint not null`、`counted_since timestamptz not null`（迁移执行时间即上线起点）、`updated_at`。
- 计数用单条 `update site_visit_counter set total = total + 1, updated_at = now() where id = 1`，在同一事务内读回总数；行锁保证并发不丢计。当前站点规模下单行热点可接受；若将来压力大，可改为分片计数，接口不变。
- 重复提交：同一请求重试时已带 Cookie → 不重复计；Cookie 写回前失败的极少数情况可能多计 1，记录为已知限度。

### 2.3 接口与页面

- `POST /api/site-visits`：无需登录，返回 `{ counted, total }`。只能增加计数、不涉及身份数据，建议对它关闭 CSRF 令牌校验（省去首访的令牌请求），改为校验 `Content-Type: application/json` 与同源 `Origin`；其他接口的 CSRF 不变。
- `GET /api/stats` 增加 `visits: { total, since } | null`；计数读取失败时 `visits` 为 null，其余三个数字照常返回（对应“—”）。
- 前端：`App.vue` 挂载时调用一次 `site-visits`（失败静默）；`DiscoverPage.vue` 统计区第 4 项“累计访问”，起点与口径说明放 `title`/`aria-label`。

## 3. FINNS-11 角色与权限

### 3.1 数据模型（新增，均为附加变更）

| 表 | 主要字段 | 说明 |
| --- | --- | --- |
| `roles` | `id`、`code`（唯一，系统角色 `ADMIN`/`MEMBER`/`RESTRICTED`/`TRUSTED`，新角色系统生成）、`name`（≤20，按小写唯一）、`description`（≤60）、`system_kind`（`ADMIN`/`DEFAULT`/空）、`sort_order`、时间戳 | 系统种类决定不可删除 |
| `role_permissions` | `role_id`、`permission`（主键二者） | 权限值来自代码中的权限枚举 |
| `user_accounts.role_id` | `not null`，外键 `roles(id)` | 单角色；迁移时现有用户设为普通成员 |
| `access_control_state` | 单行 `version bigint` | 所有角色/权限/分配写操作的版本与串行化锁 |
| `access_audit_log` | 类型、动作、操作者/目标用户 id 及名称快照、角色 id 及名称快照、前后角色名、增删权限、转移人数及名单、备注、时间 | 索引：时间、类型、涉及角色 |
| `community_collections.visibility` | `PUBLIC`/`RESTRICTED`，默认 `PUBLIC` | 工作稿中的可见范围（编辑页设置，随草稿保存） |
| `collection_publications.visibility` | 同上 | 公开版本的可见范围，发布时从工作稿复制；所有公开查询按它过滤 |

权限目录用 Java 枚举 `Permission`（与原型 11 项一致），因为代码检查依赖它；数据库只保存“角色拥有哪些权限”。锁定规则（全部角色必须有浏览/个人资料；管理员角色必须有三项管理权限）放在服务层，写入时校验。

### 3.2 授权执行

- **当前身份**：新增 `CurrentUserService`，每个请求从会话 `USER_ID` 读取用户、角色与权限（一次带连接的查询）。不把权限缓存在会话里，所以角色或权限变化在下一次请求即生效。需要时可加以 `access_control_state.version` 为键的短缓存，不改变语义。
- **接口检查**：新增注解 `@RequirePermission(Permission.X)` 和 `HandlerInterceptor`：未登录 401、无权限 403（JSON，与现有错误格式一致）。现有控制器按 §3.4 表逐个标注，`requireUser` 保留用于取当前用户和归属检查。
  - 备选（不采用）：把身份迁到 Spring Security 的 `SecurityContext` + `authorizeHttpRequests` 路由规则。更“标准”，但要重做现有会话登录流程，改动面大；注解拦截器与现有模式一致，风险更低。
- **内容范围**：受限专题不能只靠拦截器，需要在查询中过滤：公开列表/总数、专题详情与大纲、专题内文章分页、自选列表与加入自选、首页专题统计（统计只计公开，避免泄漏数量）。无权读取时详情返回 404/403（未登录 401），与原型一致。
- **受限设置**：保存工作稿时，如果把可见范围从非受限改为受限，需要 `content.restricted`；发布时再校验一次。已受限的专题，作者失去权限后仍可保持或改为公开。
- **归属检查不变**：私人草稿、预览、AI Key、模块收藏仍按 `ownerId == 当前用户` 检查；本地工具仍使用独立密钥，不进入权限目录。

### 3.3 管理服务与保护规则

`AccessAdminService` 统一处理分配角色、修改权限、新增/改名/删除角色。每个写操作在一个事务内完成：

1. 校验 `admin.users`（分配）或 `admin.roles`（权限与角色定义）；
2. `select ... for update` 锁定 `access_control_state` 并比对请求带来的 `version`，不一致返回 409；分配角色另比对目标用户的 `expectedRole`；
3. 校验锁定项、名称唯一与长度、角色上限 12、系统角色不可删除、删除时转入角色有效；
4. 计算变更后的“拥有 `admin.roles` 的用户数”，小于 1 返回 422；
5. 写入变更、`version + 1`、写操作记录（含名称快照）。

首位管理员：启动时读取配置 `finns.access.bootstrap-admin-email`。若系统中没有任何拥有 `admin.roles` 的用户，并且该邮箱账户存在，就把它设为管理员并写一条操作记录。这个过程是幂等的，不在页面上创建管理员。

### 3.4 接口与权限映射

| 接口 | 权限 |
| --- | --- |
| `GET /api/posts*`、`/api/collections*`（公开）、`/api/vps`、`/api/stats` | 无需登录（受限专题按 §3.2 过滤） |
| `GET/PATCH /api/me/profile`、`GET /api/me/posts` | 登录（`profile.manage`，锁定） |
| `/api/me/collections/selected*` | `selection.manage` |
| `POST /api/posts` | `post.create` |
| `POST /api/posts/{id}/like`、`POST /api/posts/{id}/comments`、`PUT/DELETE /api/me/collections/{id}/posts/{postId}` | `post.interact` |
| `/api/me/collections`（列表）、`/api/me/collections/drafts`、`/{id}/draft|publish|archive|preview|duplicate|imports`、`/api/me/collection-imports/*`、`/api/me/collection-templates` | `collection.manage` |
| `/api/me/ai-settings/*`、`/api/me/topic-builder/*`、`/api/me/collections/{id}/builder-document` | `ai.use` |
| `GET /api/admin/users`、`PATCH /api/admin/users/{id}/role` | `admin.users` |
| `GET /api/admin/audit` | `admin.audit` |
| `GET /api/admin/roles` | `admin.users` |
| `PUT /api/admin/roles`、`POST/PATCH/DELETE /api/admin/roles/definitions*` | `admin.roles` |
| `/api/private/tools` | 独立私人密钥（不变） |
| `GET /api/auth/me` | 登录；响应增加 `role{code,name}`、`permissions[]` |

注册：服务端始终分配注册默认角色，忽略请求中的任何角色字段。

### 3.5 前端

- 路由：新增 `/admin/users`、`/admin/roles`、`/admin/audit`（`/admin` 重定向），路由 `meta.perm`；全局守卫每次导航刷新 `/api/auth/me`。`SpaController` 增加 `/admin`、`/admin/**`。
- `App.vue`：按权限显示“管理”导航与“开始创作”；无权限时保留地址渲染 403 区块；页头显示角色名。`PersonalNav.vue`、`DiscoverPage.vue` 按权限隐藏入口与互动。
- 新页面从原型 `docs/prototypes/site-access/src/pages` 移植到 `frontend/src/pages/admin/`，去掉模拟数据，调用真实接口；样式并入 `styles.css`。
- 共享权限状态：`frontend/src/lib/session.js`（`currentUser`、`can(perm)`），替代 `App.vue` 内部的 `user` 引用并向页面提供。
- 顺带修复：提示条缺少 `show` 类导致不可见（已有单独任务；若先合入则复用）。

## 4. 迁移、兼容与回退

- **PostgreSQL**：新增 Flyway `V11__site_visits.sql`、`V12__roles_and_access.sql`（建表、种子四个角色和默认权限、现有用户设为普通成员、`visibility` 默认 PUBLIC）。全部为附加变更；Hibernate validate 对应新增实体。
- **H2 开发库**：沿用 ddl update；角色与权限种子由启动初始化补齐（幂等），与现有 DemoData 模式一致。
- **数据迁移**：不改动已有用户、内容与归属；生产执行前按项目规则准备可恢复备份并演练。
- **回退**：变更均为附加，回退到 0.6.0 程序包时，旧代码不读取新列和新表，可继续运行。之后要再升级，直接重新部署即可，迁移不会重复执行。功能出问题时，也可以只回退前端：管理入口会消失，但后端的权限检查仍然有效。
- **兼容**：现有登录、CSRF、私人草稿、AI Key、HTML 模块、导入和保存行为不变；普通成员的默认权限与 0.6.0 的登录用户相同，所以上线后普通用户感知不到变化。

## 5. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 漏标注接口导致越权 | §3.4 映射表逐项实现；增加“遍历全部 `/api/**` 映射、确认都有标注或白名单”的测试 |
| 受限专题通过列表/总数/自选泄漏 | 查询层统一过滤，按原型验证项逐条写集成测试 |
| 管理者全部失权 | 事务内锁定加计数校验；启动时的首位管理员配置作为兜底 |
| 每请求查权限的开销 | 单查询，索引在 `role_id`；必要时按版本短缓存 |
| 访问计数被脚本刷高 | 需规已明确不承诺防机器人；仅同源校验 |

## 6. 验收映射（到 G4/G6 细化）

| 需规条目 | 测试类型 |
| --- | --- |
| §2.2 计数规则与失败表现 | 控制器集成测试（Cookie 有无、过期）、并发计数测试（PostgreSQL）、前端显示单测/浏览器检查 |
| §2.3.2–2.3.4 权限与角色定义 | 服务层单元测试（锁定、上限、重名、删除转移、管理者保护、版本冲突）+ 接口集成测试 |
| §2.3.7 内容范围 | 各列表/总数/详情/自选的集成测试（游客、无权限、有权限三类身份） |
| §2.3.8–2.3.9 前后端一致与即时生效 | 接口全覆盖测试；浏览器验证 403 直达、导航、角色变更后的下一次请求 |
| §2.3.11 操作记录 | 写入快照与筛选（关键词/类型/角色/日期/分页）测试 |
| §2.3.12 CSRF 与并发 | 缺少令牌 403、旧版本 409 |

## 7. 用户决定（2026-10-15，D15）

1. **受限专题的设置入口**：按建议放在专题编辑页的“可见范围”（原型 R4，已确认）。前端改动为在 overlay 清单中加入 `CollectionEditorPage.vue`；接口 `PUT /api/me/collections/{id}/draft` 与 `publish` 增加 `visibility` 字段及权限校验。
2. **首位管理员**：通过部署配置邮箱指定（§3.3），已确认。

其余技术取舍（访问 Cookie、注解拦截器、单行计数、关闭 site-visits 的 CSRF 改为同源校验）由 AI 按本设计执行，记录在案，不需要逐项批准。
