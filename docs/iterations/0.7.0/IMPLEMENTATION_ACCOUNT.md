# FINNS 0.7.0｜F01 账号与身份维护（FINNS-15）：实施与验证记录

日期：2026-10-18。依据：[概要设计](DESIGN_ACCOUNT.md)、[契约](CONTRACT_ACCOUNT.md)。授权：用户 2026-10-18 明确“授权按 CONTRACT_ACCOUNT.md 的 B1–B7 实施，同意新增 spring-boot-starter-mail；资料局部 B8 暂不含”（ACCOUNT-D09）。

状态：**B1–B7 已实施，自动化与本机浏览器验证通过；代码已本地提交（`efaf23a`，分支 `release/0.7.0`，未推送）；尚未验收（G7）、未发布（G8）、未部署到 8080。** 版本文件（四处）仍为 0.7.0，没有改动；变更放在 CHANGELOG 的 Unreleased。

代码快照：提交前 Git `HEAD` 为 `09a7a16`，实施代码随后提交为 `efaf23a`（文档另一次提交，均未推送）；验证产物构建于提交前的同一工作树；验证所用打包产物 `target/finns-0.7.0.jar` 的 SHA256 `e4a39d8700bd27fd9fa8f2f4db670aec90cc24c180862b73fe790bf9746a3de4`（§1–§6 的验证对应这一次构建，构建于 2026-10-18 10:07；§7 之后的构建哈希见 §7，含上述全部后端与前端改动；`target/` 被忽略，可重建）。该哈希只是本次验证产物的标识，**不是**发布包。

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

## 7. 追加：`FINNS_MAIL_SSL` 与全局日预算（2026-10-18，用户授权“先实现”）

对应设计评审条件 C3（已关闭）。改动：`AccountMailConfig`（隐式 TLS、STARTTLS 必须、证书主机名校验、有 SMTP 账号却无 TLS 时拒绝启动）、新增 `AccountMailBudget`（按审计时区自然日统计已发令牌邮件 + 在途 + 通知邮件，用尽后新令牌邮件按发送失败处理、不建令牌行，通知邮件放行但计入，80% 记 WARN 一次）、`AccountLinkService`/`AccountMailService`/`AccountTokenService` 接入、`application.yml`/`.env.example` 配置项；`scripts/start-account-preview.ps1` 新增 `-RealMail`（需要当前进程里有 SMTP 设置，否则拒绝）；新增 [MAIL_SETUP.md](MAIL_SETUP.md)。

| 检查 | 结果 |
| --- | --- |
| 后端全量（H2） | 88/88 通过（新增 `AccountMailConfigTests` 4 项、`AccountMailBudgetTests` 1 项） |
| 后端全量（PostgreSQL 隔离库 `finns_test_070_account_r2`） | 88/88 通过。注意：首次在**已用过**的库 `finns_test_070_account` 上复跑出现 3 项失败，原因是持久测试库里留有之前运行的数据（预算按库里当天已发邮件计；公开列表计数基线含受限专题），不是代码缺陷；已让预算测试开头清空令牌表，并改用全新库复跑通过。**PostgreSQL 隔离测试每次应使用新库名**（项目约定） |
| 变异检查 | 临时关闭预算检查后预算测试失败，已还原 |
| 打包 | `scripts/package.ps1` 88/88，BUILD SUCCESS；jar SHA256 `5573f2eab4a7fb25dac34d7d15c5d6d578d499c27b0d074feae69ba362a6f3d6`（注意：8091 预览占用 jar 时重新打包会失败，要先 `-Stop`） |
| 真实 SMTP | 首次真实发信见 §8：邮件到达 Gmail，但进了垃圾箱 |

代码仍未提交（上一次代码提交 `efaf23a`）。

## 6. 下一步

1. 先按 [MAIL_SETUP.md](MAIL_SETUP.md) 注册 Brevo、配置 DNS 与凭据，用 `-RealMail` 预览做真实邮件测试；然后用户验收（G7）：本机可自行体验的方式见交接；真实邮件验收需先提供 SMTP 服务商/发信地址/凭据（契约 U2）。
2. 决定 8080 主站是否恢复、以及何时发布（需要备份与恢复演练、四处版本、发布授权，G8）。
3. 补做：评论框/AI 工作台的会话保护核对、浏览器内触发会话失效的验证、真实 SMTP 验证。
4. Plane：ACCOUNT-P02 正文差异仍待单项确认。

## 8. 首次真实邮件测试（2026-10-18，Brevo → 用户的 Gmail）

环境：本机预览（127.0.0.1:8091，真实 SMTP 模式，复制库），Brevo 免费档，发件人 `no-reply@2000678.xyz`，链接前缀固定为本机地址。用户本人在浏览器里提交了注册邮箱（AI 在浏览器里提交被权限系统拒绝，未绕过）。

| 检查 | 结果 |
| --- | --- |
| Brevo SMTP 登录（587 + STARTTLS） | 成功。先后遇到两个配置问题：用了 API 密钥（`xkeysib-`，535 认证失败）；换成 SMTP 密钥后因 Brevo 的“未授权 IP 拦截”被拒（525），授权代理出口 IP 后成功 |
| 应用发送 | 日志记录“账号邮件已发送 purpose=SIGNUP”，令牌随之生效 |
| 邮件是否到达 | **到达 Gmail**（用户截图：发件人显示 `FINNS <no-reply@2000678.xyz>`，主题与正文正常，链接可见，正文末尾**没有** Brevo 页脚——一个样本） |
| 邮件放在哪里 | **进了垃圾邮件**（用户反馈；截图里的“收件箱”标签是用户移出垃圾箱之后的状态） |
| 域名认证记录（公共 DNS，8.8.8.8） | 存在：`brevo1/brevo2._domainkey` 两条 DKIM CNAME、根域名 `brevo-code` TXT、`_dmarc` = `v=DMARC1; p=none; rua=mailto:rua@dmarc.brevo.com`；根域名没有 SPF TXT。**注意**：本轮较早的一次 DNS 查询曾得出“无 DMARC”，那次结果是错的（查询不稳定），以本次为准 |
| 未验证 | 邮件头里 SPF/DKIM/DMARC 的 Gmail 判定（需要用户在 Gmail“显示原始邮件”里查看）；其他邮箱（QQ、163、Outlook）；链接可点性之外的完整注册流程 |

对垃圾箱的判断（**推测，未证实**）：（1）邮件里的链接是 `http://127.0.0.1:8091/...`，指向本机回环地址的 HTTP 链接是常见的垃圾邮件信号，这是本次测试的人为条件，正式部署会用 HTTPS 公网地址；（2）新域名首次发信、`.xyz` 后缀、共享 IP 的信誉；（3）纯文本无品牌 HTML。已排除的原因：DKIM/DMARC 记录缺失（记录都在）。下一步用“显示原始邮件”确认三项认证是否通过，并用 HTTPS 域名链接重发一次对比。

用户决定（ACCOUNT-D12）：送达位置等真实邮件验证延后到上线前，不作为 G7 验收前置。预览已停止（带密钥的进程已结束）。密钥只存在于该进程的环境变量中，没有写入任何文件或文档；它们贴在过聊天里，仍应在 Brevo 删除或重新生成。

## 9. 追加：C7 会话失效保护补全（2026-10-18）

对应设计评审条件 C7。先核对现状（按开发 skill）：评论框在 401 时只弹 toast，评论留在输入框但用户得跳去登录页、离开即丢稿；AI 专题工作台在 401 时只设 `storageError`/toast，没有就地重新登录。核对中还发现**第一轮接入的一个缺陷**：个人资料页、发布表单在“换号后”仍可提交（提交按钮没被禁用、提交前没核对账号），资料保存是按当前会话用户写入的，会把前一账号的内容写到新账号上——这违反 P04“换账号不得提交前一账号内容”。本轮一并修复。

改动：
- `lib/api.js`：受保护请求（非 `/api/auth/`）返回 401 时广播 `finns-unauthorized`；登录失败和身份核对自身的 401 不算“登录失效”。
- `lib/sessionGuard.js`：在组件 setup 中自动监听该广播（仅对进入页面时已登录的用户生效，游客不触发）；新增 `beforeSubmit()`——提交前核对会话仍有效且仍是原账号，否则进入“需重新登录/换号被拒”并阻止提交（网络错误不拦截，由真实提交报告）。
- 接入：`AI 专题工作台`（`TopicBuilderPage`，任何受保护请求 401 即出现面板，归属仍由服务端校验）；`评论框`（`DiscoverPage`，评论留在输入框，登录失效/换号时在原处出现面板，提交按钮禁用）；`个人资料页`和`发布表单`补上提交前核对与按钮禁用。专题编辑器沿用上一轮的接入（自动保存也会触发广播）。
- 重新登录面板样式：`.reauth-banner`。

| 检查 | 结果 |
| --- | --- |
| 前端单元 | 38/38（新增/改写：401 状态与广播、登录接口 401 不广播、游客不触发、换号被拒、提交前核对、网络错误不拦截）；变异检查：把换号判断改为恒真后测试失败，已还原 |
| 打包 | `scripts/package.ps1` 88/88，BUILD SUCCESS；jar SHA256 `4FD654086079153B93E8607537CCD8BF72838CDA891A4B1BA6C563528D23F222`（后端代码本轮未变） |
| 浏览器（本机预览，出信箱模式，复制库） | **评论路径已验证**：登录测试账号→打开内容→在评论框输入→在数据库里让该账号会话失效（`session_epoch + 1`，模拟别处改了密码）→点“参与讨论”→出现“登录状态已变化，修改仍保留”面板，**评论文字仍在输入框，提交按钮变灰**。这同时补上了上一轮没能在浏览器验证的 T-A19 的评论部分 |
| 未在浏览器验证 | 在面板里重新登录（用同一账号放行 / 换号被拒）：浏览器里输入密码的操作被权限系统拒绝，未绕过，改由前端单元测试覆盖；AI 工作台、个人资料页、发布表单、专题编辑器的面板只有单元测试与代码走查，没有逐页在浏览器触发 |

预览已停止。

## 10. 追加：个人中心各页统一版式（2026-10-18，验收中用户反馈）

用户在验收中指出：点击个人资料、账号安全、AI 配置等页签时，页面“割裂、跳跃”，要求结构一致、不要浮动。属于验收反馈的界面一致性修复，不改任何账号行为或接口。

原因（读源码确认）：六个个人页的结构各不相同——个人资料/AI 配置/我的内容/我创建的专题/自选专题有高低不一的大标题横幅（`page-hero`，随标题行数变化），账号安全没有横幅；导航在其中两页放在 960 容器里居中，另三页直接放在全宽区域里左对齐；背景一页白一页透明；手机宽度下导航一页是两列网格、一页是横向滚动。所以切换页签时导航会上下、左右跳动。

改动：新增共用组件 `components/PersonalLayout.vue`（导航 + 统一标题块 + 内容槽），六个页面都改用它：去掉各页的大标题横幅，标题块改为统一样式（kicker + 38px 标题 + 一行说明，最小高度固定 132px，操作按钮放右侧）；容器统一 960px 居中；背景统一；手机宽度导航统一为单行横向滚动，标题块改为竖排。页面内部的重复标题（个人资料卡片、AI 配置卡片、各页 `section-heading`）已并入统一标题块。另加 `html{scrollbar-gutter:stable}`，避免页面长短不同导致滚动条出现/消失而使内容水平方向偏移 7px。账号安全页内容区取消 850px 上限，与其他页同宽。

取舍：去掉大标题横幅会让这些页面比以前简洁，这是“结构一致”的代价；如果需要保留横幅，应让所有页横幅高度固定一致，需要你另行决定。

验证（本轮）：
- 前端单元 38/38；`scripts/package.ps1` 88/88，BUILD SUCCESS；jar SHA256 `3512BBA5A3764D058F293367F63DDBDE4D2D13CACF75CEF5D4DE19FB19A094B1`。
- 用临时的布局检查环境（真实页面 + 原型的模拟接口，不属于项目产物）测量：桌面 1280 宽下，六个页面的导航位置全部为 x=153 y=161 宽 960，标题块 y=246 高 132，首个内容 y=406，不随页面长度（900–1258px）变化；手机 375 宽下导航 x=20 y=163、标题块 y=236，六页一致，无页面横向溢出。第一次测量曾发现“我的内容/我创建的专题”两页的 x 偏 7px（滚动条），加 `scrollbar-gutter` 后复测一致。
- **用户确认（2026-10-18）**：在重启后的真实预览里登录后点了页签，反馈“已经不跳了，确认修复”。
- 档案：`我创建的专题`页在窄屏下操作按钮较多会换行，标题块高度会增加（仅影响其下方内容，不影响导航位置）。

## 11. 验收结果（2026-10-18）

用户在本机预览里按 [验收清单](ACCEPTANCE_ACCOUNT.md) 走完 A–H 并回复“验收结论：全部通过”（G7 通过）。验收中的唯一反馈是 H1（个人中心页签跳动，见 §10，已修复并经用户确认）。验收是人工确认，没有逐项截图；§5、§8、§9 的未验证项里**与真实邮件送达有关的仍未验证**（延后到上线前，ACCOUNT-D12），其余由用户在预览中人工覆盖（包括面板内重新登录、两个会话的撤销）。验收通过不等于发布：G8 需要用户授权、邮件配置、主库备份与恢复演练和版本同步。
