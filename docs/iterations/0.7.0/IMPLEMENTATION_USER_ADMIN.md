# FINNS 0.7.0｜F07 账号停用与用户管理衔接（FINNS-16）：实施与验证记录

日期：2026-10-18。依据：[概要设计](DESIGN_USER_ADMIN.md)、[设计评审](DESIGN_REVIEW_USER_ADMIN.md)、[契约](CONTRACT_USER_ADMIN.md)。授权：用户 2026-10-18 要求“完成这个 Plane 上的任务”并说明“原型已经开发完毕”，AI 将其理解为授权按契约 B1–B5 实施（USER-ADMIN-D12）；**不含**部署 8080、Git 提交/推送、发布、主库迁移、真实邮件、Plane 写入。

状态：**B1–B5 已实施，H2 与 PostgreSQL 隔离库自动化测试通过，本机浏览器主路径与 390px 检查通过；未经用户验收（G7）、未发布（G8）、未提交 Git。** 业务验收 UA-A01–A15 的“用户验收结果”仍为未执行，见[验收清单](ACCEPTANCE_USER_ADMIN.md)。

## 1. 实施范围与文件

| 批次 | 内容 | 主要文件 |
| --- | --- | --- |
| B1 | V14 迁移、实体字段、站长系统角色、启动初始化与自愈 | `db/migration/V14__account_status_and_site_owner.sql`、`user/UserAccount.java`、`access/AccessControlState.java`、`access/AccessAuditEntry.java`、`access/Role.java`、`RoleRow.java`、`Permission.java`、`AccessBootstrap.java` |
| B2 | 登录/会话强制点、停用恢复、用户列表扩展、审计扩展 | `user/AuthController.java`、`access/AccessContext.java`、`AccessService.java`、`AccessAdminService.java`、`AccessAuditWriter.java`、`AccessAdminController.java` |
| B3 | 层级规则接入既有角色接口、站长转交与转交接口 | `access/AccessAdminService.java`、`AccessAdminController.java` |
| B4 | 前端：共用外壳/弹窗/样式、三管理页 | `frontend/src/components/AdminLayout.vue`、`AdminDialog.vue`、`assets/admin.css`、`pages/admin/AdminUsersPage.vue`、`AdminRolesPage.vue`、`AdminAuditPage.vue`、`main.js` |
| B5 | 后端测试、测试夹具、预览种子脚本、文档 | `src/test/java/com/aitome/UserStatusTests.java`、`OwnerTransferTests.java`、`AccessTestSupport.java`、`AccessAdminTests.java`、`AccessBootstrapTests.java`、`scripts/seed-user-admin-preview.*` |

新增依赖：无。构建输出 `src/main/resources/static/` 随前端改动重新生成（该目录已入库）。四处发布版本未改（仍为 0.7.0）。

## 2. 与契约的偏差与补充

| 项 | 契约 | 实际 | 说明 |
| --- | --- | --- | --- |
| D1 | 站长转交候选＝正常的 `ADMIN` 角色用户 | 同 | 以系统种类判定，不依赖角色名称 |
| D2 | 非站长操作者对受保护角色授予/修改 | 同时拒绝“授予任何 `admin.*` 权限”给任何角色 | 与原型一致；移除管理权限不受限 |
| D3 | 契约 §4.3：角色分配拒绝不写记录 | 同 | 保持既有行为，避免扩大范围 |
| D4 | “最后一位正常管理者”规则 | 保留为兜底，**经接口不可触达** | 站长永不被停用且拥有全部权限，任何操作后至少有站长在；规则口径（停用账号不计入）由用户列表 `managerCount` 的测试覆盖 |
| D5 | 契约 §10：UA-A11 前端 `adminUserAccess.test.js` | 未新增前端单测 | 管理页是 Vue 单文件组件，仓库的前端测试针对 `lib/`；失效保护逻辑沿用 FINNS-15 的 `sessionGuard`（已有测试），F07 的 401 路径由后端测试与浏览器核对覆盖，未做 F07 专项浏览器会话失效演练 |
| D6 | 无 | 新增 `scripts/seed-user-admin-preview.*` | 为验收预览准备虚构账号与层级；只连 `finns_test_*` 隔离库 |

## 3. 验证（环境：Windows，JDK 17，Maven `-s .mvn/settings.xml -o`，Node 22.11，PostgreSQL 17.11 本机实例；2026-10-18）

| 项 | 命令/方法 | 结果 |
| --- | --- | --- |
| 后端全量（H2） | `scripts/package.ps1`（含 `mvn test`） | 107/107 通过，退出码 0（FINNS-15 基线 88，新增 `UserStatusTests` 10、`OwnerTransferTests` 8、`AccessBootstrapOwnerEmailTests` 1） |
| 新增后端测试（H2） | `UserStatusTests` 10 项、`OwnerTransferTests` 8 项 | 通过 |
| 新增后端测试（PostgreSQL） | 设置 `FINNS_USER_ADMIN_TEST_DB_URL`/`_FLYWAY=true`/`_DDL=validate` 与 `postgres` 配置，分别对全新隔离库 `finns_test_070_useradmin_status2`、`finns_test_070_useradmin_owner2` 运行 | `UserStatusTests` 10/10、`OwnerTransferTests` 8/8 通过（含并发停用与并发转交）；Flyway 从空库迁移到 V14，Hibernate validate 通过；`pg_constraint` 核对 CHECK 约束名与迁移一致（设计评审 C-02、C-04 在此关闭） |
| 全量套件（PostgreSQL） | `scripts/test-postgres.ps1 -DatabaseName finns_test_070_useradmin_full`（新库） | 106/106 通过，退出码 0（该次早于 D13 的启动初始化小改动，未重跑 PostgreSQL 全量；该改动只影响站长指针为空时的候选选择，已由 H2 测试覆盖，PostgreSQL 上未单独复验）；FINNS-15 等默认上下文测试在 V14 的 PostgreSQL 上回归通过（新增两类在该脚本里使用各自的 H2 库，PG 证据见上一行） |
| V13→V14 升级演练 | 复制库 `finns_test_070_account_accept`（V13、2 个既有账号）为 `finns_test_070_useradmin_upgrade`，用 0.7.0 包启动 | `flyway_schema_history` 13、14 均成功；既有账号 `ACTIVE`、`status_version=1`；站长角色存在且有 11 项权限；无管理者时启动仅警告，不失败 |
| 前端 | `npm test`、`npm run build` | 38/38；构建通过（76 模块） |
| 打包 | `scripts/package.ps1`（含全量 H2 测试） | 通过，生成 `target/finns-0.7.0.jar` |
| 本机浏览器 | 隔离 PostgreSQL 库的预览（<http://127.0.0.1:8091/admin/users>，出信箱模式，虚构账号） | 站长视角列表/概览/转交入口；停用两步确认成功并写记录；停用账号用正确密码登录得到“账号已停用，请联系站点管理员。”，错误密码与不存在邮箱得到统一提示；站长转交两步（候选仅正常管理员、邮箱不区分大小写）成功，原站长当前会话保留且立即为管理员；角色页站长/管理员卡无编辑删除；操作记录页展示转交与结果；URL 筛选直达/刷新；390px 无横向溢出、弹窗可滚动且焦点进入弹窗 |

### 3.1 变异检查（证明新增测试能在行为回归时失败）

对 `UserStatusTests`+`OwnerTransferTests` 逐项注入缺陷、各自还原后再验证：登录处不挡停用账号（3 项失败）；停用不抬高 `session_epoch`（1 项失败）；去掉“管理员不能管理受保护用户”的层级规则（8 项失败）；转交时不抬高接任人 `session_epoch`（4 项失败）；`AccessService.resolve` 不再检查停用状态——首轮存活（新增防御测试只经 `requireUser`），补一条只经权限拦截器的管理接口断言后被抓到（1 项失败）。记录中曾有一次还原脚本的缺陷导致首轮变异累积，已用逐项重跑取代，源码已核对无遗留变异。

## 4. 未验项与限制

- 未做：真人手机、读屏与完整键盘遍历；多设备/多浏览器同时登录的 F07 专项浏览器演练（API 级多会话已由测试覆盖）；768px 平板宽度未逐页检查；真实邮件（停用账号找回仍走 FINNS-15 的邮件，真实送达已延后到上线前）。
- 浏览器内置窗格在页面滚动到下方时截图会出现空白（窗格截图问题，DOM 与布局检查正常），因此浏览器验证使用 URL 筛选使页面保持在可见区域。
- 生产站长账号与“不回退旧包”已由用户决定（USER-ADMIN-D13）；据此把启动初始化改为部署配置邮箱的正常账号优先（`AccessBootstrap.pickOwnerCandidate`），新增 `AccessBootstrapOwnerEmailTests`。发布仍需用户另行授权，并按 `FINNS_BOOTSTRAP_ADMIN_EMAIL` 配置该账号、先做主库备份与恢复演练。
- 本轮未改 `finns` 主库、8080 主站、四处版本；未提交、推送或发布；未写 Plane。

## 5. 运行实例与数据触碰

- 预览：`scripts\start-account-preview.ps1 -DatabaseName finns_test_070_useradmin_upgrade` 启动的 8091 实例，仅监听 127.0.0.1、只连隔离库、邮件写 `data/dev-mail/outbox.jsonl`。用完停止：`scripts\start-account-preview.ps1 -DatabaseName finns_test_070_useradmin_upgrade -Stop`。
- 新建隔离库：`finns_test_070_useradmin_status`、`finns_test_070_useradmin_owner`（PG 测试）、`finns_test_070_useradmin_upgrade`（升级演练与预览）。库内为虚构账号，不含真实数据。
- 8080 主站与 `finns` 主库未触碰。
