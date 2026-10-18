# FINNS 0.7.0｜F01 账号与身份维护（FINNS-15）：实施与验证记录

日期：2026-10-18。依据：[概要设计](DESIGN_ACCOUNT.md)、[契约](CONTRACT_ACCOUNT.md)。授权：用户 2026-10-18 明确“授权按 CONTRACT_ACCOUNT.md 的 B1–B7 实施，同意新增 spring-boot-starter-mail；资料局部 B8 暂不含”（ACCOUNT-D09）。

状态：**B1–B7 已实施，自动化与本机浏览器验证通过；代码已本地提交（`efaf23a`，分支 `release/0.7.0`，未推送）；尚未验收（G7）、未发布（G8）、未部署到 8080。** 版本文件（四处）仍为 0.7.0，没有改动；变更放在 CHANGELOG 的 Unreleased。

代码快照：提交前 Git `HEAD` 为 `09a7a16`，实施代码随后提交为 `efaf23a`（文档另一次提交，均未推送）；验证产物构建于提交前的同一工作树；验证所用打包产物 `target/finns-0.7.0.jar` 的 SHA256 `e4a39d8700bd27fd9fa8f2f4db670aec90cc24c180862b73fe790bf9746a3de4`（构建于 2026-10-18 10:07，含上述全部后端与前端改动；`target/` 被忽略，可重建）。该哈希只是本次验证产物的标识，**不是**发布包。

## 1. 实施范围与文件

| 批次 | 内容 | 主要文件 |
| --- | --- | --- |
| B1 | V13、实体字段、`PasswordPolicy`、H2 旧账号标记、测试夹具 | `db/migration/V13__account_verification_and_recovery.sql`、`user/UserAccount.java`、`account/PasswordPolicy.java`、`account/AccountBootstrap.java`、`account/AccountState.java`、`account/AccountActionToken.java`、`config/DemoData.java`、`src/test/.../TestUsers.java` |
| B2 | `session_epoch` 贯通 | `access/AccessContext.java`、`access/AccessService.java`、`user/AuthController.java`（`requireUser`）、`access/SessionRevocationService.java` |
| B3 | 令牌状态机、邮件端口与三种实现、投递、限流 | `account/AccountTokenService.java`、`TokenPurpose.java`、`SlidingWindowLimiter.java`、`AccountMailSender.java`、`Smtp/Outbox/DisabledAccountMailSender.java`、`AccountMailConfig.java`、`AccountMailService.java`、`AccountConfig.java` |
| B4 | 公开接口、登录改造、`/auth/**` 回退与响应头 | `account/AccountLinkService.java`、`AuthLinkController.java`、`user/AuthController.java`、`config/SpaController.java` |
| B5 | 登录态接口、主动改密、重置 | `account/AccountController.java`、`AccountPasswordService.java` |
| B6 | 前端路由与页面、`api()` 状态、会话保护 | `frontend/src/pages/AccountFlowPage.vue`、`AccountSecurityPage.vue`、`lib/sessionGuard.js`、`lib/accountValidation.js`、`components/ReauthPanel.vue`、`assets/account.css`、`main.js`、`App.vue`、`lib/api.js`、`PersonalNav.vue`、`PersonalPage.vue`、`ComposerForm.vue`、`CollectionEditorPage.vue` |
| B7 | 验收脚本、环境变量示例、文档、验证 | `scripts/lib/dev-signup.mjs`、`scripts/test-topic-builder-*.mjs`、`.env.example`、`application.yml`、本文件 |

新增依赖：`spring-boot-starter-mail`（版本由 Boot 3.3.8 管理）。构建输出 `src/main/resources/static/` 随前端改动重新生成（该目录已入库）。

## 2. 与契约的偏差与补充

| 项 | 契约 | 实际 | 说明 |
| --- | --- | --- | --- |
| D1 | `token_hash char(64)` | `varchar(64)` | `char` 在 PostgreSQL 的 Hibernate validate 中类型不符 |
| D2 | 仅令牌表 | 另增单行表 `account_state` | H2 与 PostgreSQL 共用“旧账号过渡已执行”标记，避免 H2 重复标记新账号 |
| D3 | 会话保护用于资料编辑/发布/评论 | 已接入：个人资料页（现有常驻表单）、发布表单、专题编辑器（含自动保存）。**评论框、AI 专题工作台未接入** | 评论框与 `TopicBuilderPage` 的 401 行为未核对；见 §5 |
| D4 | 资料局部 B8 | 未实施 | 按用户授权不含；`/me` 仍是原常驻编辑表单，账号安全入口加在个人导航 |
| D5 | 契约 §8“`/?signin=1` 旧链接重定向” | 已实现于路由守卫 | |
| D6 | 登录/注册弹窗 | 已删除，`openAuth` 跳转 `/auth/login` | 导航“登录”按钮与“开始创作”未登录时进入该路由，`next` 回到原页 |
| 补充 | — | 令牌检查/消耗按来源 IP 限流 30 次 / 10 分钟 | 契约 §3.6 的默认值 |

## 3. 验证（含命令与结果）

环境：Windows，JDK 17（`D:\java\jdk\jdk-17.0.12`），Maven `-s .mvn/settings.xml`，PostgreSQL 17.11 本机实例（`E:\tools\finns-runtime`）。

| 检查 | 命令/方式 | 结果 |
| --- | --- | --- |
| 后端全量（H2） | `scripts/package.ps1`（含 `mvn package`） | 83/83 通过，BUILD SUCCESS，退出码 0（其中新增 `AccountFlowTests` 25 项；既有测试夹具已迁移） |
| 后端全量（PostgreSQL） | `scripts/test-postgres.ps1 -DatabaseName finns_test_070_account` | 83/83 通过，退出码 0；Flyway 迁移到 V13，Hibernate validate 通过，**包含两项 PostgreSQL 并发测试（同一重置链接并发 6 次仅 1 次成功；同一注册链接并发 6 次仅创建 1 个账号）** |
| 变异检查 | 临时让 epoch 比对恒真后运行 `AccountFlowTests` | 4 项失败（改密/重置后旧会话仍可用），已还原源码并复跑通过——说明撤销测试确实能发现回归 |
| 前端单元 | `npm test`（frontend） | 35/35 通过（新增 5 项：401 状态保留、同账号重新登录放行、换账号拒绝、账号核对、密码/邮箱校验） |
| 前端构建 | `npm run build` / `package.ps1` 内 | 通过（72 modules） |
| 迁移演练 | 复制 `finns_restore_check_070_release_20261017`（V12，1 个账号）为 `finns_test_070_account_upgrade`，用打包 jar 在 127.0.0.1:8091 启动 | Flyway 应用 V13；账号 `legacy_account=true`、`email_verified_at` 为空、`account_state` 1 行、令牌表空；`/auth/login` 返回 200 且带 `Referrer-Policy: no-referrer` |
| 重复启动 | 同一复制库再次用 `scripts/start-account-preview.ps1` 启动 | Flyway 校验 13 个迁移、“Schema is up to date. No migration necessary”，无重复迁移；预览脚本拒绝主库名与 8080（参数校验） |
| 浏览器（本机内置） | 8091 实例，出信箱模式 | 通过：注册（邮箱 → 邮件链接 → 打开链接不消耗 → 验证并继续 → 设置账号，两次密码不一致的行内提示 → 创建成功不自动登录）→ 登录并按 `next` 回到账号安全页 → 原密码错误提示且不改变 → 改密成功、当前设备保持登录且刷新后仍登录 → “忘记原密码”申请邮件、倒计时 → 重置链接设置新密码 → 提示“所有设备已退出” → 再访问 `/me/security` 被带到登录页；390px 手机宽度注册页无横向溢出 |
| 日志 | 8091 实例日志 | 未出现邮箱明文、令牌或密码；无 ERROR/Exception |

主库未触碰：`finns` 仅做只读行数核对（2 用户、4 内容、V12）；没有对它迁移、写入或部署。新建隔离库 `finns_test_070_account`、`finns_test_070_account_upgrade` 保留。

## 4. 事故记录（运行环境）

实施期间（约 01:09）本机 PostgreSQL 的一个后端进程被外部终止（退出码 0x40010004，日志可见），随后集群关闭，**同时 8080 主站不在运行**（`data/main.pid` 也已缺失）。原因未确认，时间上与一次中断请求重合，推测是子进程被一并终止，**不是有意操作**。已用 `scripts/postgres.ps1 start` 恢复数据库：崩溃恢复完成，`finns` 数据核对正常（2 用户/4 内容/V12）。随后按用户此前授权过的发布目录 `data/deployments/20261017-access-0.7.0`，用项目脚本 `scripts/start-local-main.ps1` **恢复了 8080 主站**（同一份 0.7.0 旧包，PID 30184；`/api/stats` 返回与此前一致的 2 创作者/4 内容/3 专题，日志无 ERROR），这只是恢复被误终止的服务，**不是新的发布**——8080 仍不含本次账号改动。

## 5. 未验证与限制

- **真实 SMTP 未验证**：没有邮件服务商和凭据；投递率、延迟、退信、STARTTLS 握手均未测。仅 `SmtpAccountMailSender` 的构造与配置校验被编译覆盖，未向真实服务器发信。
- **会话失效的浏览器保护（T-A19）未在浏览器验证**：对应的浏览器操作被自动模式拒绝，未重试；仅有前端单元测试（5 项）与后端撤销测试覆盖。
- 评论框、AI 专题工作台未接入重新登录面板（D3）；专题编辑器的自动保存路径已接入但未在浏览器触发。
- 旧账号补验证提示只在账号安全页；资料页徽章属 FINNS-21（B8）。
- `scripts/lib/dev-signup.mjs` 与两个 Node 验收脚本的改动只做了语法检查，**未运行**（这两个脚本需要真实 DeepSeek Key 与带出信箱的服务）。
- 邮件通知超时、队列满、`PENDING` 超时回收等边界仅有部分单元覆盖（发送失败、窗口内、上限已测；队列满与 2 分钟回收未专门测试）。
- 进程内限流在重启/多实例下不共享；来源 IP 取连接地址，若置于反向代理之后需先配置受信代理。
- 未做真人手机/跨浏览器验收、无障碍专项检查；未执行 G7 验收与 G8 发布。8080 主站仍是旧包，没有新账号能力。
- 回退：V13 为附加变更；回退到无 V13 的 0.7.0 包后，旧注册接口创建的账号会被新包视为“未验证且非旧账号”而拒绝登录（设计 §3.5）。

## 6. 下一步

1. 用户验收（G7）：本机可自行体验的方式见交接；真实邮件验收需先提供 SMTP 服务商/发信地址/凭据（契约 U2）。
2. 决定 8080 主站是否恢复、以及何时发布（需要备份与恢复演练、四处版本、发布授权，G8）。
3. 补做：评论框/AI 工作台的会话保护核对、浏览器内触发会话失效的验证、真实 SMTP 验证。
4. Plane：ACCOUNT-P02 正文差异仍待单项确认。
