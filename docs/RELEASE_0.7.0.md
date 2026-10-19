# FINNS 0.7.0 本机主站发布记录

日期：2026-10-17（Asia/Shanghai）。需求：FINNS-10 首页全站累计访问量、FINNS-11 基于角色的访问管理（含受限专题）。详见 [需规](iterations/0.7.0/REQUIREMENTS.md)、[实施与验证记录](iterations/0.7.0/IMPLEMENTATION_SITE_ACCESS.md)、[验收清单](iterations/0.7.0/ACCEPTANCE_SITE_ACCESS.md)。

## 授权和范围

用户 2026-10-17 回复“验收通过”“确认发布”，并提供首位管理员邮箱（出于隐私不写入文档），同时放行了构建、读取/备份主库与启停 8080 的权限。目标是**本机 8080 主站，使用现有主库**（与 0.6.0 相同，不代表公网服务）。未授权且未执行：Git 提交/推送、Plane 状态变更。多轮 AI 沟通（FINNS-9）及 0.8.0 内容不在本次发布内。

四处版本 0.6.0→0.7.0：VERSION、pom.xml、frontend/package.json、frontend/package-lock.json；CHANGELOG 新增 0.7.0 段。

## 运行实例

| 项目 | 状态 |
| --- | --- |
| 主站 | http://127.0.0.1:8080/ ，PID 29248，仅绑定 127.0.0.1，postgres profile，会话 Cookie `FINNS_MAIN` |
| 发布目录 | `data/deployments/20261017-access-0.7.0/`（Git 忽略） |
| JAR | `finns-0.7.0.jar`，SHA256 `CBCA98C5C733AB3D7FAC4A7251408FBCB6B88A822D4985CE76441706493B36DF` |
| 数据库 | 127.0.0.1:5432/finns，Flyway V1–V12 全部成功 |
| 启动方式 | `scripts/start-local-main.ps1 -ReleaseDirectory data/deployments/20261017-access-0.7.0`，调用前设置 `FINNS_BOOTSTRAP_ADMIN_EMAIL` |

发布前状态：8080 与 5432 均未运行（疑为机器重启后非正常退出，`postmaster.pid` 残留）。主库集群按发布脚本方式启动，PostgreSQL 自动完成崩溃恢复后正常，日志“数据库系统准备好接受连接”。主库为 V10：1 个用户（演示账户）、4 篇内容、3 个公开专题。

## 构建与测试

`scripts/package.ps1`（前端构建 + Maven 全部测试 + 打包）。第 1、2 次因 `TopicBuilderTests.generatesSnapshotAndChatPreservesAnswers`（该测试把 AI 超时设为 1 秒，调用本地假上游）返回 504 而失败，日志保留为 `package-attempt1-failed.log`、`package-attempt2-failed.log`；该测试类单独重跑 3 次均 8/8；此时机器 CPU 约 50% 且被其他程序占用，第 3 次完整构建 58/58、BUILD SUCCESS（`package.log`）。未跳过任何测试。该测试对机器负载敏感，属已知计时风险，后续可考虑放宽测试内超时（本次未改）。

## 备份、恢复与迁移演练

1. 备份：`finns-before-0.7.0.dump`（pg_dump 自定义格式，57,245 字节），SHA256 `67F39A7B63AD9802C86C7E5AAD936205C9A147D284D82B2B8E51C142C6502EB7`；`pg_restore -l` 可读（`backup-contents.txt`，127 行）。
2. 恢复校验：完整恢复到新建隔离库 `finns_restore_check_070_release_20261017`，17 张表行数与主库逐表一致（`restore-compare.txt`）。
3. 迁移演练：在该恢复库上用 0.7.0 发布包启动：V11/V12 成功，16 张既有业务表行数不变，用户保留为普通成员，管理员邮箱尚无账户时只记警告、不阻止启动（`migration-drill.log`）。
4. 主库迁移：8080 启动时应用 V11/V12，耗时约 0.15 秒；发布后既有业务表与备份逐表行数一致（0 差异）；`user_ai_credentials` 记录保留。

## 发布后验证

| 检查 | 结果 |
| --- | --- |
| 进程与端口 | 8080 归属 PID 29248，命令行指向发布 JAR，绑定 127.0.0.1；日志 0 个 ERROR，错误输出 0 字节 |
| SPA 地址 | `/`、`/collections`、`/collections/1`、`/vps`、`/me`、`/my/collections`、`/admin`、`/admin/users|roles|audit` 均 200；入口资源 `index-DlvuPHFx.js`/`index-CyuAGKmA.css` 在发布 JAR 内，HTML `no-store` |
| 公开 API | `/api/posts`、`/api/collections`、`/api/vps`、`/api/stats` 均 200；stats 为 1/4/3、累计访问 0（起点 2026-10-17 11:37 +08:00） |
| 权限 | 游客访问 `/api/admin/*`、`/api/me/*`、`/api/auth/me` 均 401；无 CSRF 令牌的登录请求 403；`/api/site-visits` 跨站 Origin 403 |
| 数据 | 4 个角色（权限数 3/7/8/11），唯一用户为普通成员，受限专题 0，操作记录 0 |

未做：真实浏览器登录/管理员流程（避免在生产登录演示账户，且浏览器访问会计入访问量）；本轮未验证 8080 上的管理页面交互，这些已在验收环境（同一前端构建产物）通过。

## 尚需处理

1. **第一位管理员尚未生效**：主库中没有所提供管理员邮箱对应的账户。需本人在 http://127.0.0.1:8080/ 注册该邮箱，再**重启 8080**（启动时若没有任何管理者，才会把该邮箱设为管理员并写一条“系统初始化”记录）。在此之前没有管理员，管理页面无法使用。
2. **演示账户风险**：主库唯一的账户是 `demo@aitome.dev`（昵称“纸飞机”），其密码写在源码 `DemoData.java` 中，不应作为生产账户长期保留。建议注册自己的账号后，由管理员把它降为受限成员或另行处理。本次没有对它做任何改动，也没有把管理员权限给它。
3. Git：已在本地分支 `release/0.7.0` 提交（`1ccf197` 文档与流程、`f7c025d` 代码），未推送、未合并到 `main`；`.claude/` 未入库。Plane：FINNS-10/11 已置为 Done 并更新正文（2026-10-17）。演示账户按用户决定先保持默认密码，未改动。

## 启停与回退

启动命令同上；**停止前**读取 `main.pid`，核对该进程命令行指向发布 JAR 且 8080 归属一致，再停止该精确进程，不可停止所有 Java/PostgreSQL 进程。

回退到 0.6.0：停止 0.7.0；0.6.0 固定包仍在 `data/deployments/20261010-topic-builder-0.6.0/finns-0.6.0.jar`，已在隔离库演练（V12 库上启动 0.6.0 可正常读取并注册新用户，旧注册的用户无角色，再升级时自动补为普通成员）。V11/V12 为附加变更，回退不需要删除。注意：0.6.0 不识别专题可见范围，回退期间受限专题会按公开显示；当前无受限专题。若需回到发布前数据（会丢失发布后的写入），先备份当前库，再把 `finns-before-0.7.0.dump` 恢复到**新命名的隔离库**校验后再切换，不要直接覆盖主库。

## 保留的数据库与演练进程

新建并保留：`finns_restore_check_070_release_20261017`（本次备份恢复与迁移演练）及此前验证库 `finns_test_070_access*`、`finns_restore_check_070_upgrade`。演练应用（8094）已停止。测试实例 8090 已停止。主密钥 `FINNS_AI_CREDENTIAL_SECRET` 沿用既有（来自服务端用户环境变量），本次未读取、输出或更换。

---

# 补充发布（2026-10-18）：账号功能（FINNS-15）+ 用户管理（FINNS-16）

日期：2026-10-18（Asia/Shanghai）。用户回复“验收通过，进行发布”；版本号**仍为 0.7.0**（用户决定，四处版本未改，新旧包同名，以 SHA256 区分）；生产站长账号与“不回退旧包”由用户决定（USER-ADMIN-D13，邮箱不入库）；邮件由用户自行在用户级环境变量中设置 SMTP 凭据（本记录不含任何密钥）。目标仍是本机 8080，使用现有主库。部署流程见 [部署手册](DEPLOYMENT.md)。

| 项目 | 状态 |
| --- | --- |
| 发布目录 | `data/deployments/20261018-account-useradmin-0.7.0/`（Git 忽略） |
| JAR | `finns-0.7.0.jar`，SHA256 `A7F58983A56411C7873D70BF767A43AED095F92D88D3EFD302322377E788A1F4`（含 V13、V14；前端入口 `index-DuIkGitI.js` / `index-DNPT71H9.css`） |
| 主站 | PID 34880，仅 127.0.0.1:8080，postgres profile；邮件 `smtp`（Brevo，587，日预算 240），`FINNS_PUBLIC_BASE_URL=http://127.0.0.1:8080`（邮件链接只能在本机打开） |
| 数据库 | 127.0.0.1:5432/finns，Flyway 到 V14 |

## 备份、恢复与迁移演练

1. 备份：`finns-before-account-useradmin.dump`（pg_dump 自定义格式，71,269 字节），SHA256 `6B838FA2B0FFF9DF570B6B25C416BCDE6775A28818B5FED4819E3BA00C8D956A`；`pg_restore -l` 可读（160 行，`backup-contents.txt`）。
2. 恢复校验：恢复到 `finns_restore_check_070_useradmin_20261018`，22 张表行数与主库逐表一致（0 差异，`restore-compare.txt`）。
3. 迁移演练：在恢复库上用发布包（8094）启动：V13、V14 成功，日志 0 个 ERROR；按 `FINNS_BOOTSTRAP_ADMIN_EMAIL` 把用户 2 指定为唯一站长（“系统初始化”记录 1 条），既有账号状态正常、仍为旧账号过渡标记。演练进程已停止。
4. 主库迁移：切换时应用 V13、V14；发布前主库只有 2 个账号（演示账户 + 用户本人，后者本就是管理员）。

## 过程记录

第一次切换启动失败：`smtp` 模式缺 `FINNS_PUBLIC_BASE_URL`（启动即拒绝，数据未受影响，日志保留为 `main-server-attempt1-failed.log`）；补上后第二次启动成功（约 27 秒）。旧进程（PID 30184）按 `main.pid`、8080 归属与命令行核对后精确停止。

## 发布后验证

| 检查 | 结果 |
| --- | --- |
| 数据 | Flyway 最高版本 14；用户 1 普通成员/正常，用户 2 站长/正常；站长指针=用户 2 |
| 页面/API | `/`、`/admin/users`、`/auth/login`、`/api/stats`、`/api/posts`、`/api/collections` 均 200；入口资源在 JAR 内 |
| 权限 | 游客访问 `/api/admin/users`、`/api/admin/ownership`、`/api/account/security`、`/api/auth/me` 均 401；无 CSRF 的登录 403 |
| 日志 | 0 个 ERROR，错误输出 0 字节 |

**未做**：真实邮件送达验证（垃圾箱、SPF/DKIM/DMARC、QQ/163/Outlook；FINNS-15 的 ACCOUNT-D12 延后项，用户可注册新账号触发）；在 8080 上登录站长账号的浏览器检查（避免计入访问量与在生产登录）；站长账号需本人用自己的密码登录核对管理页；演示账户 `demo@aitome.dev` 仍存在（密码写在源码里，建议降级或停用——现在可在用户管理页停用）。

## 回退

**不支持回退到更早的程序包**（USER-ADMIN-D13）：旧包不认识账号停用状态。出问题时前向修复；如需恢复数据，用 `finns-before-account-useradmin.dump` 恢复到新库并核对，不覆盖主库。

## 发布后的真实邮件验证（2026-10-18 夜至 2026-10-19）

- 8080 上两次“找回密码”（21:31、21:33，令牌 1、2）发送失败（状态 FAILED）；应用日志按设计只记“发送失败”，未记原因，**根因未能确认**。排查时用同一套用户级环境变量直连 Brevo：连接、STARTTLS、登录成功，并向用户 Gmail 发了一封诊断邮件，**用户在收件箱收到**；本机出口 IP 当时可登录。推测失败时的出口 IP 尚未被 Brevo 授权或其后改过，**未证实**。
- 2026-10-19 12:46 用户再次在 8080 申请找回密码：令牌 3 为 ACTIVE，日志“账号邮件已发送”，用户随后**收到重置链接邮件**。结论：生产站经 Brevo 向 Gmail 发信可用；**仅验证了 Gmail 一个收件方**，QQ/163/Outlook、垃圾箱比例、SPF/DKIM/DMARC 判定、HTTPS 链接影响仍未验证。邮件链接前缀为 `http://127.0.0.1:8080`，只能在本机打开。
- 遗留改进（未做，需用户同意后重启 8080）：发送失败时把 SMTP 异常类型和回复码（不含收件人与密钥）写入日志，便于下次定位。

## 演示账户停用（2026-10-19）

用户要求直接停用 `demo@aitome.dev`（演示账户，密码写在已公开的源码里）。操作前备份主库（`data/deployments/20261018-account-useradmin-0.7.0/finns-before-demo-disable.dump`，80,279 字节，SHA256 `511191FA4438302F07DBEFF22B2EA0E7EFDBC948DD17DA56122B50C3DEBEE901`）；随后在主库用一个事务停用（状态 DISABLED、版本+1、会话撤销）并写入 1 条操作记录（操作者“站长授权的运维操作”，因没有站长登录凭据而绕过管理页，与页面操作等价）。验证：该账号用原密码登录得到 403（账号已停用）；公开文章/专题仍可见。**该账号名下有全站现有内容（4 篇文章、1 条评论、7 个专题），因此未删除**：删除会因外键阻止或使 7 个专题失去归属，需用户确认处置方式（见交接）。可在用户管理页随时恢复。
