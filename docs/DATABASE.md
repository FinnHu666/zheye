# FINNS 数据库运行与迁移

## 日常本地开发

开发运行脚本在 E 盘使用 PostgreSQL 17 便携二进制，数据库数据保存在已忽略的 `data/postgres/`，监听地址限制为 `127.0.0.1:5432`，不注册 Windows 服务、不修改系统 PATH。`scripts/postgres.ps1 start|status|stop` 管理该本地实例；`scripts/run.ps1` 会构建 Vue、启动 `postgres` profile，并将 FINNS Web 绑定限制为 `127.0.0.1`。若有明确的局域网/生产发布需求，应另行设计访问控制与部署配置，不要直接移除本地绑定保护。

本机开发实例的 PostgreSQL 认证限定在回环地址，应用角色是无建库/无提权权限的 `finns_app`。该信任认证只适合本机开发，不能复制到共享或生产环境。外部/生产实例必须提供有效的 `FINNS_DB_URL`、`FINNS_DB_USERNAME` 与 `FINNS_DB_PASSWORD`，采用受控的密码/证书策略；配置通过进程环境变量读取。`.env.example` 仅是格式示例，Spring Boot 不会自动加载 `.env`。

```powershell
./scripts/postgres.ps1 status
./scripts/postgres.ps1 stop
./scripts/run.ps1
```

运行期如需改用外部 PostgreSQL，先在当前 PowerShell 会话设置变量，然后运行 `scripts/run.ps1`；其中的 `FINNS_DB_PASSWORD` 不要写入仓库、脚本、命令历史或日志。

## 从旧 H2 导入

默认 H2 原文件保留在 `data/aitome.mv.db`。迁移前要先停止占用它的应用；`scripts/migrate-h2.ps1` 会拒绝在 8080 仍有监听进程时继续。脚本在 `data/backups/<时间戳>/aitome.mv.db` 生成一致性离线副本并核对 SHA-256，然后用只在本机运行的一次性 PostgreSQL profile 导入。源文件不移动或删除。

```powershell
./scripts/migrate-h2-dry-run.ps1
./scripts/migrate-h2.ps1
```

导入器保留实体 ID、时间与密码哈希，先导用户，再按外键顺序导文章、评论、专题和 VPS 条目；唯一的显示名映射才回填旧专题 owner。大小写邮箱冲突会停止事务。旧 H2 首次导入时 `collection_posts` 和用户自选关系没有旧数据可映射，不制造记录。Flyway V6 会为已有 PostgreSQL 专题创建默认章节，并把当时已有的 `collection_posts` 按原显示顺序回填成 POST 条目；旧表保留为回退数据，应用新写入只使用章节/条目模型。`data_migration_batches` 记录批次键、源文件、各表行数与未判定归属；`h2-aitome-v1` 已完成的批次重复运行会跳过。

## 备份与恢复

REQ-012 新增 V7（Java migration）发布快照和幂等导入记录，回填当时已发布专题；V8 允许工作稿保存未完成条目。公开内容由快照读取，不能通过直接修改工作表更新公开版本。先在 `finns_test_topic_import_20261002` 恢复库验证 V1–V8，随后于 2026-10-03 按用户重新部署请求备份/恢复演练并应用到主库 `finns`。当前主库 V1–V8 全部成功，11 张既有业务表数据校验不变；备份及证据见 AI_HANDOFF 最新部署记录。

本地开发库备份到项目忽略的 `data/backups/`：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
& 'E:\tools\finns-runtime\postgresql-17.11\pgsql\bin\pg_dump.exe' -h 127.0.0.1 -p 5432 -U finns_app -d finns -Fc -f "data/backups/finns-$stamp.dump"
```

在空的隔离数据库恢复自定义格式备份：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$restoreDb = "finns_restore_check_$stamp"
& 'E:\tools\finns-runtime\postgresql-17.11\pgsql\bin\createdb.exe' -h 127.0.0.1 -p 5432 -U postgres -O finns_app $restoreDb
& 'E:\tools\finns-runtime\postgresql-17.11\pgsql\bin\pg_restore.exe' -h 127.0.0.1 -p 5432 -U postgres -d $restoreDb --no-owner --role=finns_app --exit-on-error 'data/backups/finns-<timestamp>.dump'
./scripts/test-postgres.ps1 -DatabaseName $restoreDb
```

不要在真实库运行 `clean`、删 schema 或从备份覆盖原库。恢复演练要检查 Flyway 历史、表行数与登录后内容。`test-postgres.ps1` 会写入测试记录且不会自动删除数据库；检查数据库名、owner、活动连接为零并确认备份校验值已记录后，才可手工删除本次创建的确切隔离库。2026-09-28 的恢复副本已完成验证并清理；备份保存在 `data/backups/finns-validation-20260928-133853.dump`，SHA-256 为 `B71CD851B2E18EF4840D1252E31246607171ABA81D65F0E1BBAD9472C2777E61`。

## PostgreSQL 集成测试

业务写入测试会创建账号、文章与专题，只能对明确命名的本机隔离库运行。`scripts/test-postgres.ps1` 固定连接 `127.0.0.1:5432`，只接受 `finns_test_*` 或 `finns_restore_check_*` 数据库，并校验其 owner 为 `finns_app`；直接指定主库 `finns` 会在启动测试前被拒绝。测试会保留写入在指定隔离库中，不会自动删除或重置数据库。

在上文创建并恢复隔离库的同一 PowerShell 会话中执行：

```powershell
./scripts/test-postgres.ps1 -DatabaseName $restoreDb
```

该库应从备份恢复或经批准创建，不得把含真实数据的主库改名后作为测试库。测试覆盖注册/登录、会话资料、文章与讨论、结构化专题草稿/发布/revision、专题关联、自选排序/取消、跨用户隔离及 CSRF。2026-10-02 隔离库 `finns_test_public_pagination_20260929` 已验证 Flyway V1–V6 与 14 项行为测试；该库保留测试数据，不得作为主应用数据库。
