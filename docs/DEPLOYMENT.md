# FINNS 部署与发布手册（本机主站 / 换机器部署）

适用：把 FINNS 以“一个可执行 JAR + PostgreSQL”的方式部署到一台 Windows 机器（当前主站就是这种方式：只监听 `127.0.0.1:8080`，数据库 `127.0.0.1:5432/finns`）。**密钥、密码、真实邮箱一律不写进仓库或本文档**，只放在部署机器的环境变量里。具体某次发布的证据见 `docs/RELEASE_<版本>.md`；变量清单见根目录 `.env.example`。

## 1. 机器前置

| 组件 | 要求 | 本机现状（供对照） |
| --- | --- | --- |
| Java | 17（项目脚本固定 `D:\java\jdk\jdk-17.0.12`，换机器改脚本里的路径） | 同左 |
| PostgreSQL | 17，只监听 127.0.0.1；库 `finns`，属主 `finns_app`（`scripts/postgres.ps1` 可初始化/启停，路径 `E:\tools\finns-runtime\postgresql-17.11\pgsql`，数据 `data/postgres`） | 同左 |
| Node | 仅构建前端时需要（22.x） | `E:\download\nodejs` |
| Maven | 构建时需要，用 `-s .mvn/settings.xml` | `E:\program\codebasic\apache-maven-3.9.6` |
| 约束 | 工具不装到 C: 盘 | — |

只运行已构建的 JAR 的机器只需要 Java + PostgreSQL；构建机器另需 Node/Maven。

## 2. 环境变量（用户级，在部署机器自己的 PowerShell 里设置）

必需：`FINNS_AI_CREDENTIAL_SECRET`（32 字节随机数的 Base64，用于加密用户保存的 AI Key；**换机器或重装时必须沿用旧值，否则已保存的 Key 无法解密**）。

站长与邮件（FINNS-15/16 起）：

| 变量 | 说明 |
| --- | --- |
| `FINNS_BOOTSTRAP_ADMIN_EMAIL` | 站长账号邮箱。**该账号须已在站点注册且状态正常**；仅当库里还没有站长时生效（之后站长只能在“管理 → 用户与角色”里转交）。没有任何管理者时，该账号同时成为首位管理员 |
| `FINNS_MAIL_MODE` | `smtp`（生产）/ `disabled`（默认，新用户注册与找回密码不可用，老账号登录不受影响）/ `outbox`（仅本机预览） |
| `FINNS_MAIL_HOST/PORT/USERNAME/PASSWORD/FROM` | SMTP 服务器（当前用 Brevo `smtp-relay.brevo.com:587`）；**用 SMTP 密钥，不是 API 密钥**；服务商可能要求授权出口 IP |
| `FINNS_MAIL_STARTTLS` / `FINNS_MAIL_SSL` | 587 用 `true/false`；465 用 `false/true` |
| `FINNS_MAIL_DAILY_BUDGET` | 全局日发送预算，建议服务商日额度的 80%（Brevo 300→240） |
| `FINNS_PUBLIC_BASE_URL` | `smtp` 模式**必须设置**（缺少则启动失败）：邮件链接前缀，必须是用户实际访问站点的地址；仅本机访问时用 `http://127.0.0.1:8080`，此时邮件链接只能在本机打开 |

设置示例（密钥在提示时输入，不进命令历史；设置后需**新开终端**才生效）见 [MAIL_SETUP.md 步骤 4](iterations/0.7.0/MAIL_SETUP.md)。

## 3. 发布步骤（每次都按序做，缺一不可）

1. **构建并测试**：`scripts\package.ps1`（先构建 Vue，再 `mvn package` 跑全部测试并打包到 `target\finns-<版本>.jar`）。涉及数据库迁移时，另用全新隔离库跑 `scripts\test-postgres.ps1 -DatabaseName finns_test_<名字>`（库名每次要新的，须先 `createdb -O finns_app`）。
2. **版本**：发版时同步 `VERSION`、`pom.xml`、`frontend/package.json`、`frontend/package-lock.json`，更新 `CHANGELOG.md`。
3. **发布目录**：`data\deployments\<日期>-<说明>-<版本>\`（Git 忽略），放入 JAR、`VERSION`、JAR 的 SHA256。
4. **备份并演练**：`pg_dump -Fc` 备份主库 → `pg_restore -l` 检查可读 → 恢复到新建隔离库 `finns_restore_check_*` → 逐表行数与主库比对（应 0 差异）→ 在恢复库上用新 JAR 在另一个端口（如 8094）启动，确认 Flyway 迁移成功、站长初始化符合预期、日志无 ERROR → 停掉演练进程。
5. **切换主站**：核对旧进程（读 `main.pid`，确认 8080 归属且命令行指向旧发布目录）后**只停这个进程**；在**同一个 PowerShell 进程**里把用户级变量读进进程环境（`FINNS_MAIL_*`、`FINNS_PUBLIC_BASE_URL`、`FINNS_BOOTSTRAP_ADMIN_EMAIL`），再运行 `scripts\start-local-main.ps1 -ReleaseDirectory <发布目录>`；脚本只启动已有集群和 JAR，不初始化数据库。启动约需 30 秒。
6. **发布后核对**：`flyway_schema_history` 最高版本与预期一致；站长/账号状态符合预期；`/`、`/admin/users`、`/auth/login`、`/api/stats`、`/api/posts`、`/api/collections` 为 200；游客访问 `/api/admin/*`、`/api/account/*`、`/api/auth/me` 为 401；无 CSRF 的登录请求为 403；日志 0 个 ERROR；入口资源名在 JAR 内。
7. **记录**：写 `docs/RELEASE_<版本>.md`（授权、备份校验和、演练结果、实例、核对结果、未做项）、更新 `AI_HANDOFF.md`、`delivery-progress.json`。

## 4. 常见问题

- **启动失败：`finns.mail.mode=smtp 需要配置 FINNS_MAIL_HOST、FINNS_MAIL_FROM 和 FINNS_PUBLIC_BASE_URL`**：smtp 模式缺变量；补上后重启（失败的那次启动不会损坏数据，迁移已幂等）。
- **新用户收不到验证邮件**：先看 Brevo 发送日志与垃圾箱；邮件链接是 127.0.0.1 时只能在本机打开；服务商可能拦截未授权的出口 IP。
- **回退**：从 FINNS-16 起（V14 之后）**不支持回退到更早的程序包**（旧包不认识账号停用状态，会让被停用账号重新可登录；站长角色在旧包里只是普通角色）。出问题时前向修复；如必须恢复数据，用发布目录里的 `pg_dump` 备份恢复到新库并核对，不要覆盖主库。
- **停止主站**：只能停精确的进程（核对 `main.pid`、8080 归属和命令行），不要停止所有 Java/PostgreSQL 进程。
- **隔离测试/预览**：`scripts\start-account-preview.ps1 -DatabaseName finns_test_*`（8091，出信箱）；`scripts\seed-user-admin-preview.ps1` 造演示账号。预览与测试只连 `finns_test_*`/`finns_restore_check_*`，脚本会拒绝主库。
- **含中文的 `.ps1` 必须带 UTF-8 BOM**，并在 Windows PowerShell 5.1 里验证。
