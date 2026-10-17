# FINNS 0.7.0｜网站访问量与角色访问管理：实施与验证记录（G5/G6）

日期：2026-10-15。记录修订：1。授权：用户“授权实施 B1–B6 全部”（DECISIONS D17）。依据：[详细设计与接口契约](CONTRACT_SITE_ACCESS.md)、[需规修订 5](REQUIREMENTS.md)。状态：**已实施，自动化与本机验证通过；尚未用户验收，未发布**。应用版本仍为 0.6.0（四处未改）；未部署主站、未连接主库 `finns`、未提交 Git、未改 Plane 状态。

## 1. 实际改动

| 批次 | 文件 | 内容 |
| --- | --- | --- |
| B1 访问计数 | `stats/SiteVisitCounter.java`、`SiteVisitService.java`、`SiteVisitController.java`、`VisitCookieRefreshFilter.java`、`SiteStatsController.java`、`config/SecurityConfig.java`、`db/migration/V11__site_visits.sql`；前端 `lib/siteVisit.js`、`App.vue`、`pages/DiscoverPage.vue` | 30 分钟滑动访问 Cookie 去重、单行计数、`/api/site-visits`（免 CSRF，校验 JSON 与同源主机）、`/api/stats.visits`、首页第 4 个数字与悬停说明 |
| B2 角色模型与执行 | `access/Permission.java`、`Role.java`、`RoleRow.java`、`RolePermission.java`、`AccessControlState.java`、`AccessAuditEntry.java`、`AccessContext.java`、`AccessService.java`、`RequirePermission.java`、`PublicEndpoint.java`、`PermissionInterceptor.java`、`AccessWebConfig.java`、`AccessBootstrap.java`、`AccessAuditWriter.java`、`user/UserAccount.java`、`user/AuthController.java`、`config/SpaController.java`、`application.yml`、`db/migration/V12__roles_and_access.sql`；所有 `/api` 控制器加权限声明 | 11 项权限、四个种子角色、逐请求读取权限、注解拦截器、注册默认角色、首位管理员配置、`/admin/**` 回退 |
| B3 管理接口 | `access/AccessAdminService.java`、`AccessAdminController.java` | 用户列表/分配、权限修改、角色新增/改名/删除、操作记录查询；版本串行化、锁定项、管理者保护 |
| B4 受限内容 | `content/CommunityCollection.java`、`CollectionPublication.java`、`CollectionPublicationService.java`、`ContentController.java`、`CollectionWorkspaceController.java`、`user/PersonalController.java` | 工作稿与公开版本的 `visibility`；列表/总数/详情/大纲/文章分页/自选/统计过滤；设受限的权限校验 |
| B5 前端 | `lib/session.js`、`main.js`、`App.vue`、`components/PersonalNav.vue`、`components/AdminNav.vue`、`pages/admin/AdminUsersPage.vue`、`AdminRolesPage.vue`、`AdminAuditPage.vue`、`pages/CollectionEditorPage.vue`、`pages/DiscoverPage.vue`、`assets/styles.css`；构建输出 `src/main/resources/static` | 路由权限、403 区块、管理三页、编辑页可见范围、按权限隐藏入口；提示条补 `show` 类（原提示不可见的缺陷） |
| B6 测试与文档 | `src/test/.../SiteVisitTests`、`AccessTestSupport`、`AccessEnforcementTests`、`AccessBootstrapTests`、`AccessAdminTests`、`RestrictedContentTests`；`frontend/tests/session.test.js`；`.env.example`、`docs/DATABASE.md` | 见 §3 |

## 2. 与契约的偏差（已核对，不改变已确认产品行为）

| 项 | 契约 | 实际 | 原因 |
| --- | --- | --- | --- |
| `user_accounts.role_id` | not null | 可空；空角色按注册默认角色处理，启动时补齐 | 回退演练发现：0.6.0 程序包注册时不写该列，NOT NULL 会使回退期间注册失败 |
| 未列出的接口 | — | `POST /api/collections`（旧建专题）→ `collection.manage`；`POST /api/vps` → `post.create` | 覆盖性测试要求所有 `/api` 接口声明权限 |
| 前端路由 | — | `/my/collections` 需 `selection.manage`；AI 工作台路由需 `ai.use` | 与接口权限一致 |
| 游客点赞 | 0.6.0 游客可匿名点赞 | 需要 `post.interact`（游客 401） | 按已确认的权限矩阵（游客只有浏览公开内容） |
| 访问上报同源校验 | 比较 Origin 与站点 | 只比较主机名 | 反向代理后端口/协议可能不同，比较端口会误拒 |

## 3. 验证（全部本机，Java 17.0.12，Node 22.11）

| 检查 | 命令/方式 | 结果 |
| --- | --- | --- |
| 基线 | `scripts/test.ps1`（改动前） | 29/29 通过 |
| H2 全量 | `scripts/test.ps1` | 退出码 0；58/58（新增 29：访问 9、执行 9、启动 1、管理 7、受限 3） |
| PostgreSQL 全量 | `scripts/test-postgres.ps1 -DatabaseName finns_test_070_access_final`（新建隔离库） | 退出码 0；58/58；Flyway 从空库应用 V1–V12，Hibernate validate 通过 |
| 前端 | `npm test`、`npm run build` | 30/30；构建退出码 0 |
| 迁移/回退演练 M01 | 隔离库 `finns_restore_check_070_upgrade`（0.6.0 发布恢复库副本：31 用户、66 公开专题、V10） | 升级：启动无 ERROR，V12，31 用户均有角色（普通成员 30、配置的首位管理员 1），计数行存在，66 个专题仍公开；重复启动：初始化记录仍 1 条、角色 4 个；用发布目录中的 0.6.0 程序包（SHA256 与发布记录一致）回退：启动成功，专题 66，注册返回 201；再升级：无角色用户 0；全程无 ERROR |
| 浏览器（真实应用） | 打包后以隔离 H2 运行于 127.0.0.1:8090，配置首位管理员为演示种子账户 | 首页第 4 个数字及悬停说明；种子账户启动后为管理员，页头显示角色、出现“管理”；角色页由真实接口渲染，修改受限成员权限并保存、写入操作记录；操作记录按类型筛选（地址回填）；编辑页可见范围：选受限后工作稿为受限、公开版本仍公开；注册时附带 role=ADMIN 仍为普通成员；普通成员直达/刷新 `/admin/users` 显示 403 并保留地址；提示条可见；390/768 宽无横向滚动 |

自动化覆盖的主要场景：访问去重、Cookie 续期、跨站 Origin 与非 JSON 拒绝、并发 20 次全部计数、读取失败/计数失败、缺行自动补齐；权限声明覆盖性；游客/受限/普通/受信/管理员逐接口允许与拒绝；即时生效；失效会话；空角色用户；管理员不能读他人草稿与预览；首位管理员幂等；分页/搜索（含通配符转义）/筛选；分配冲突 409；锁定项与管理者保护 422；角色新增（复制/基本）、重名、长度、上限 12、改名生效、系统角色不可删、删除转移；操作记录快照与关键词/类型/角色/日期/分页；缺 CSRF 403；受限专题在列表/总数/统计/大纲/详情/文章分页/自选/加入自选中的表现；设受限权限、失去权限后保持或改公开、复制得到公开。

## 4. 未验证 / 限制

- 浏览器检查使用的是“缺行补齐”修复之后、“空角色兼容”和“失效会话按游客处理”修复之前的程序包；后两项由自动化测试（H2 与 PostgreSQL）覆盖，未在浏览器重测。
- 管理员身份在 390/768 宽度下的管理页面未在真实应用上逐页检查（原型阶段已检查同一套样式）；角色页离开未保存提醒（浏览器 confirm）未自动化操作。
- 两位管理员真实并发编辑、两个真实浏览器标签页的访问去重未做人工并排验证（服务端版本与 Cookie 逻辑由自动化测试覆盖）。
- 回退风险：0.6.0 不识别可见范围，回退期间受限专题会按公开显示；如已有受限专题，回退前需评估。
- 不在本轮范围：DiscoverPage “收录到我的专题”调用未带 `revision` 参数（既有问题，单独记录，未修改）。

## 5. 运行与数据

- 新建隔离库（保留，不自动删除）：`finns_test_070_access`（首次运行，V12 校验和已过时）、`finns_test_070_access_r2`、`finns_test_070_access_final`、`finns_restore_check_070_upgrade`。主库 `finns` 未连接。
- 验证用 H2 文件与日志在忽略目录 `target/site-access-proof/`（可清理）。8090/8092/8093 验证进程已全部停止。
- 构建产物 `target/finns-0.6.0.jar` 被重新打包（构建输出，非发布包）；主站固定包在 `data/deployments/20261010-topic-builder-0.6.0/`，未改动。

## 6. 下一步

G7 用户验收（建议按 §3 浏览器步骤在测试实例复核），G8 发布需另行授权：四处版本升 0.7.0、主库备份与恢复演练、部署与回退准备、Plane 状态变更。

## 7. 验收后修改（2026-10-17，D18）

`pages/DiscoverPage.vue` 去掉“累计访问”悬停提示；`App.vue` 页头不再显示角色名；`pages/PersonalPage.vue` 个人资料对管理员显示只读“角色”；`assets/styles.css` 删除不再使用的样式。验证：`npm test` 30/30、`npm run build` 退出码 0；重新打包后在 127.0.0.1:8090 复核：首页该数字无 title/aria-label，管理员页头无角色且个人资料显示“角色：管理员”，新注册普通成员个人资料不显示角色。后端未改，未重跑后端测试（本轮没有后端改动）。§3 与 §4 中关于悬停说明的描述以本节为准。
