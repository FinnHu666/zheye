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
3. Git 提交/推送、Plane 状态与 U17（FINNS-10/11 正文阶段文字）仍待用户确认。

## 启停与回退

启动命令同上；**停止前**读取 `main.pid`，核对该进程命令行指向发布 JAR 且 8080 归属一致，再停止该精确进程，不可停止所有 Java/PostgreSQL 进程。

回退到 0.6.0：停止 0.7.0；0.6.0 固定包仍在 `data/deployments/20261010-topic-builder-0.6.0/finns-0.6.0.jar`，已在隔离库演练（V12 库上启动 0.6.0 可正常读取并注册新用户，旧注册的用户无角色，再升级时自动补为普通成员）。V11/V12 为附加变更，回退不需要删除。注意：0.6.0 不识别专题可见范围，回退期间受限专题会按公开显示；当前无受限专题。若需回到发布前数据（会丢失发布后的写入），先备份当前库，再把 `finns-before-0.7.0.dump` 恢复到**新命名的隔离库**校验后再切换，不要直接覆盖主库。

## 保留的数据库与演练进程

新建并保留：`finns_restore_check_070_release_20261017`（本次备份恢复与迁移演练）及此前验证库 `finns_test_070_access*`、`finns_restore_check_070_upgrade`。演练应用（8094）已停止。测试实例 8090 已停止。主密钥 `FINNS_AI_CREDENTIAL_SECRET` 沿用既有（来自服务端用户环境变量），本次未读取、输出或更换。
