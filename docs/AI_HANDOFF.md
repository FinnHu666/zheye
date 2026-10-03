# FINNS 下一位 AI 执行交接

## 最新状态：2026-10-03 首次推送到 GitHub

- 用户要求将当前项目推送到 `https://github.com/FinnHu666/zheye.git`。此前本目录没有 Git 仓库；远端检查确认是空仓库。
- 已在本地创建 `main` 分支并建立初始提交 `2757e86`（`Initial FINNS project snapshot`），推送成功，远端 `origin/main` 跟踪已配置。推送时的源代码快照为 103 个文件。
- `.gitignore` 排除了本地 `data/`（含数据库、备份、部署包和验收产物）、`target/`、`frontend/node_modules/` 与 `frontend/dist/`。只纳入源码、迁移、项目文档、脚本、测试、依赖锁文件及当前打包静态资源；没有真实 `.env` 或单文件超过 50 MB。Maven 设置仅使用公开 Maven Central 镜像。
- 本轮只做 Git 初始化、提交和远端推送，没有修改业务代码、运行构建/测试、连接或迁移数据库，也没有部署服务。推送本身已由 GitHub 返回成功结果；后续可用 `git status --short --branch` 检查工作区，正常开发按 `main` 跟踪分支继续。

## 最新状态：2026-10-03 专题创建体验修复与 0.5.1 本机部署

- REQ-012 创建体验修复已实施，代码版本四处同步为 0.5.1。创作入口可直接创建专题；新建页先收集名称、起点和可选第一段；笔记小标题可空；编辑器按章节折叠、正文优先，发布缺项可定位；改动导入原文后不能提交过期解析结果，重整理在页面内确认。
- 本机主站已运行 `data/deployments/20261003-ux-0.5.1/finns-0.5.1.jar`，SHA-256 `F360DC6AD3422325DECDDD78AD1EDE550E6438E12DD75D92B28EF5D793098D4E`。Java 17 / `postgres` profile，PID **35912**，只监听 `127.0.0.1:8080`。日志 `data/deployments/20261003-ux-0.5.1/main-server.log`，错误日志 `main-server-error.log`，PID 文件 `main.pid`。停止前核对 PID 命令行指向此部署 JAR，并确认 8080 仍由该 PID 监听。
- 主库 `finns` 维持 Flyway V1–V8；此轮没有新增迁移，启动日志显示 8 个迁移验证通过且数据库无待执行迁移。部署前数据库备份 `data/deployments/20261003-ux-0.5.1/finns-before-ux-0.5.1.dump`，SHA-256 `811B27E69B593C09B3ECDEE63C5117A6E26BFFC4B614ACB651E1DF105D24CCE3`，`pg_restore -l` 能读取归档目录。SQL 比较部署前后 11 张业务表的行数与行摘要，一致；没有向主库写验收内容。`GET /`、`/collections`、`/me/collections/new` 均返回 SPA 200，`/api/stats` 为 1 creator / 3 content / 4 public collections，最新资源 `/assets/index-BP6DB2Gq.js`。日志和结果见同目录 `main-before.txt`、`main-after.txt`、`http-checks.txt`。
- 最终 `scripts/package.ps1` 构建通过，H2 21/21；`scripts/test-postgres.ps1 -DatabaseName finns_test_topic_import_20261002` PostgreSQL 17.11 21/21；前端 Node 行为测试 4/4。日志：`data/req012-ux-package-final.log`、`data/req012-ux-postgres.log`。隔离浏览器完成 390px 创建/编辑、无标题笔记发布、缺项定位、原文变化防旧结果提交、页内重新整理确认。合成账号与数据仅在测试库；截图 `data/req012-ux-mobile-create.png`、`data/req012-ux-mobile-editor.png`。
- 8081 隔离预览已在验收后停止，勿将其当成当前站点。主站访问 `http://127.0.0.1:8080/`。本机部署，不代表远程发布。实际非技术用户试用及三分钟目标仍待验证；没有安装工具、初始化/提交/推送 Git。
- 需求与交接已更新：`docs/NEXT_ITERATION_PLAN.md` 第 19 节、`docs/TOPIC_IMPORT_PLAN.md` 第 12 节、`CHANGELOG.md` 0.5.1。

## 历史启动记录：2026-10-03 本地开发实例

- 用户要求启动当前项目。本轮执行 `./scripts/run.ps1`：PostgreSQL 启动成功，Vue/Vite build 成功，Spring Boot 3.3.8 / Java 17.0.12 使用 `postgres` profile 启动成功。
- 当前实例由该脚本的前台会话运行，Web PID 26972 仅监听 `127.0.0.1:8080`；PostgreSQL PID 25180 仅监听 `127.0.0.1:5432`。主库 `finns` Flyway 已验证 V1–V8，未执行新迁移。
- 冒烟验证：`GET /` HTTP 200，标题 `FINNS · 技术人的灵感基站`；`GET /api/stats` HTTP 200，返回 `1 creator / 3 content / 4 collections`。没有运行业务测试或浏览器交互验收，没有写业务数据。
- 访问地址：`http://127.0.0.1:8080/`。开发实例停止方式：在运行 `scripts/run.ps1` 的终端按 Ctrl+C；PostgreSQL 本轮单独启动并继续运行，可按需执行 `./scripts/postgres.ps1 stop`。
- 下一步：用户可直接打开站点；如需继续开发，继续遵循下方 REQ-012 状态。目录不是 Git 仓库；本轮没有提交或推送。

## 历史部署：2026-10-03 0.5.0 本机主站（后由 0.5.1 替换）

- 用户明确要求“重新部署最新的版本”，已将本机主站切换为已验收的 0.5.0 JAR。没有新增业务代码或提升版本号；没有远程发布、安装工具或提交 Git。以下实施轮的“主库 V6 / 未部署”是历史边界，当前事实以本节为准。
- 产物源码时间核对：Java/资源/Vue 源码没有晚于最终 JAR 的更改。使用此前 Vue/Vite、H2 20/20、PostgreSQL 20/20、队列 2/2 的已验收产物，本轮未重跑整套测试。部署副本 `data/deployments/20261003-002454/finns-0.5.0.jar` SHA-256 `A4D39077024E53F788DC525F163DEF146AD9A270E2D86777077569DDA5D7F8B8`，与源产物一致。
- 迁移前备份 `data/deployments/20261003-002454/finns-before-v7-v8.dump`，SHA-256 `F02C964F544682CB7A7F3A41F76A5F5896CB7D00433E01A664FC497B24DD9C3C`。已实际恢复到 `finns_restore_check_deploy_20261003_002454`；11 张既有业务表行数/行内容校验与主库一致。备份目录保留旧 0.4.0 JAR，仅作恢复材料；未执行降级或覆盖主库恢复，演练库保留。
- 已核对并停止旧主站 PID 37704，再以 Java 17 / postgres profile / 显式主库 `finns` 启动部署副本。主库 Flyway V7、V8 成功，Hibernate validate 和服务启动成功，全部 11 张既有业务表迁移前后校验不变（包括账号、密码哈希、内容、专题及关系；未输出原始凭据）。原有 4 个公开专题回填为发布快照，数量与名称核对通过。
- 当前主站 PID **25912**，仅监听 **127.0.0.1:8080**。JAR 位于上述部署目录，不依赖 Maven 开发进程；启动使用 `Start-Process -WindowStyle Hidden`。日志 `main-server.log` / `main-server-error.log`，PID 文件 `main.pid`；重新打包 target 不覆盖这个运行副本。停止前核对该 PID 命令行确实指向部署 JAR，再停该进程。
- 旧隔离预览 PID 35784 / 8081 已停止，测试库保留。不要继续将旧 8081 链接当主站；访问 `http://127.0.0.1:8080/`。PostgreSQL 服务继续运行。
- 运行态核验：主页、专题列表/详情、新建/导入/私人预览 SPA 直达、公开 API/outline/stats、最新 JS 资源均 HTTP 200；未登录模板/私人预览 API 均 401。首页统计 1 创作者、3 内容、4 公开专题，与主库一致。浏览器专题目录、详情直达与刷新通过，实际加载 `index-D4_r52yh.js`，控制台 warning/error 空。没有在主库创建测试账号或写验收内容，登录态完整编辑流程沿用隔离验收，本轮未在主库复跑。
- 证据集中在部署目录：`audit.sql`、`main-before.txt`、`restore-check.txt`、`main-after.txt`、`http-checks.txt`、`private-access-checks.txt`、服务日志与 `main-home.png`。下一步仍是非技术用户试用；个人模板/AI/复杂文件及 REQ-008 不属于本次部署范围。

## 实施基线：2026-10-03 REQ-012 第一阶段 / 0.5.0

- 用户已要求按专题导入方案实施并记录版本。本轮第一阶段已实施，技术验证通过；真人易用性目标未验收。完整方案/证据见 `docs/TOPIC_IMPORT_PLAN.md` 第 10 节、NEXT_ITERATION_PLAN 第 17 节、CHANGELOG 0.5.0。以下规划轮与架构答复均为历史时点。
- 功能：粘贴/UTF-8 TXT/MD、规则预览/长文拆分、新建/追加幂等导入、五个场景模板、本人全文/结构复制、私人阅读预览、未完成草稿保存/发布缺项定位、章节/条目排序/拆分/合并/删除撤销、串行保存及公开快照。原文当前会话内存保留，没有本地永久恢复；只有最新发布快照，没有发布历史库。
- 后端入口：`content/CollectionImportController.java`、`CollectionTemplates.java`、`CollectionWorkspaceController.java`、`CollectionPublicationService.java`、两个新增实体；公开消费者 `ContentController`、`PersonalController`、`SiteStatsController`、DemoData 已接入。新增 Java Flyway V7 与 SQL V8，已应用迁移不可修改。旧文章收录/移除需 revision，只写工作稿；公开 POST 卡片冻结摘要而文章详情仍读取原文章。
- 前端入口：`CollectionImportPage.vue`、共享 `CollectionOutlineEditor.vue`、`CollectionNewPage.vue`、`CollectionEditorPage.vue`、`MyCollectionsPage.vue`、`CollectionDetailPage.vue`、`lib/draftSaveQueue.js`、路由及样式。新增独立 `/me/collections/import`、`/me/collections/:id/preview`，SPA 深链接/刷新已检查。
- 验证：最终 `scripts/package.ps1` Vue/Vite + H2 20/20 + Maven package 成功（`data/req012-package-final.log`）；`scripts/test-postgres.ps1 -DatabaseName finns_test_topic_import_20261002` PostgreSQL 20/20、Flyway V1–V8 重复验证成功（`data/req012-postgres-final.log`）；`node --test frontend/tests/*.test.js` 2/2。新增 6 项导入行为集成测试，旧集成测试适配快照/revision，权限/CSRF 回归保持通过。
- 浏览器使用隔离库验收账号及合成资料，完成粘贴→整理→保存→私人阅读预览/刷新→发布、工作稿修改公开不变、MD 文件追加、空白名称创建、缺项发布阻止、补齐发布、删除撤销与结构复制。桌面 1440×900 / 移动 390×844 已检查，没有横向溢出；最终页 warning/error 空。最终资源 `index-D4_r52yh.js`。截图 `data/req012-desktop-create.png`、`data/req012-mobile-preview.png`；浏览器曾因重启会话失效而保存失败，输入保留，重新登录后继续。5 位真人/三分钟目标、容量压测未执行。
- 数据库：主库 `finns` 只备份与只读核验，V1–V6 成功，未执行 V7/V8、业务写入或清理。`data/req012-pre-migration.dump` SHA-256 `1F24983BE88649F3C62119FE51E9E72F114B68D1166320ECE4AA37108DEC9B1F` 已恢复到 `finns_test_topic_import_20261002` 后迁移；原 ID 1/2/3 的公开专题回填快照已查询确认。隔离库和测试数据保留，不自动 drop。
- 当前运行（本轮核对）：PostgreSQL PID 30072 / loopback 5432；旧 8080 Java PID 37704 仍监听，加载版本未确认，未重启。隔离预览 PID 35784 / `127.0.0.1:8081`，运行 `target/finns-0.5.0.jar` / postgres profile / 测试库，日志 `data/req012-browser-server-final.log`；不可将其当主站。执行会话 77327，停止可 Ctrl+C 或核对 PID/命令后只停此预览进程。
- 四处版本为 0.5.0，最终 JAR 55,796,803 字节。第一次重打包因旧预览 JAR Windows 文件锁失败；已停止旧 PID 36616、成功重打包并启动上述新预览。以后重打包先停止占用同一 JAR 的预览实例。源码并不保证旧 8080 进程已加载新代码。
- 下一步：先实际组织非技术用户试用，观察整理后调整量；主库新版本启动/迁移与远程部署本轮未实施，须按对应授权、备份、隔离验证证据处理。个人模板/导出、富文本、PDF/Word 和 AI 后续单独开展，REQ-008 继续暂缓。没有安装新工具，没有初始化/提交/推送 Git，目录仍不是 Git 仓库。

## 2026-10-03 架构答复与只读核对

- 用户要求介绍当前架构。本轮核对 Maven/npm/Vite、Vue 路由、API/会话安全、数据库配置及运行脚本；没有修改业务代码、版本文件或启动/重启服务。
- 当前 VERSION、pom.xml、frontend/package.json 和 package-lock.json 均为 0.5.0；源码存在 Flyway V7 Java 迁移和 V8 SQL 迁移。以下旧交接的 0.4.0 / REQ-012 仅规划描述不能代替当前源码状态；REQ-012 在 NEXT_ITERATION_PLAN 已标为实施中。本轮未验收其新功能。
- 实测 pg_ctl status：PostgreSQL 17.11 正在运行（PID 30072），仅监听 127.0.0.1:5432，数据目录为本项目 data/postgres；主库 finns 的 flyway_schema_history 只读查询显示 V1–V6 全部成功，尚无 V7/V8。不能把源码中的新迁移视为已部署。
- Get-NetTCPConnection 核对 Web 为 127.0.0.1:8080（PID 37704），无 5173 监听；主页 GET 返回 HTTP 200。未确认该进程加载的应用版本，不能以源码版本推定运行版本。
- 验证仅包括文件核对、进程/端口状态、主页 GET、数据库版本/迁移历史/表名 SELECT；未写业务数据，未运行构建、业务测试或浏览器交互验收。目录仍不是 Git 仓库。下一步如继续开发或部署，应先核对 REQ-012 的实现与验收，再按授权处理 V7/V8；本轮未执行迁移。

## 2026-10-02 电脑目录与通用 Skill（项目外任务）

- 用户要求调查当前电脑的目录并制作通用存放 Skill；进一步明确：先比较实时磁盘剩余空间，再查看已有软件/目录占用，优先选择剩余空间最多的合格盘，软件、资料、数据与 skills 都按用途分类。不能固定优先 D: 或 E:。
- 已创建 `D:/env/.codex/skills/windows-storage-routing/`：`SKILL.md`、`agents/openai.yaml`、目录方案 `references/storage-plan.md`、用途索引 `references/skill-catalog.md`、只读大小脚本 `scripts/inspect_storage.py` 和三份目录调查 JSONL。该规则适用于新增内容；本项目工具不安装 C:、项目既有运行脚本与数据路径的约束继续有效。
- 只读检查时 C:/D:/E: 余量约 17.16/96.21/239.71 GiB，当前新软件倾向 E:；统计为逻辑大小，跳过链接和访问错误的结果已标为部分统计，不能视为精确磁盘占用。确认 `E:/download/nodejs` 是现役 NVM 符号链接；不得作为下载垃圾清理。
- 验证：使用已有 `D:/Program Files/Python/Python3.1.3/python.exe`（实际 Python 3.13.5）运行 `skill-creator/scripts/quick_validate.py` 通过；6 个相对引用、149 条调查 JSONL 和 UI 元数据通过检查。调查脚本验证普通目录 8 字节/2 文件、缺失目录报告不完整、现役 NVM 链接跳过且不遍历。微型验证样本保留在 `E:/Temp/windows-storage-routing-validation-79yoanqd`。
- 本轮仅新增全局 Skill 相关文件并补充本交接；没有修改 FINNS 需求、业务代码、CHANGELOG 或四处版本，没有安装工具、移动旧目录、改变 PATH/下载设置/应用数据配置、连接业务数据库、重启服务或提交 Git。`git status --short` 再次确认本目录不是 Git 仓库。
- 未执行：软件安装/数据迁移验收、FINNS 构建/业务测试/浏览器验证、Skill 在新会话中的自动发现实测。本项目实施状态继续以下记录，运行 PID/服务状态本轮未复核。下一步可直接在用户已授权的安装/下载/保存任务中使用该 Skill；旧环境整理需另按具体请求开展。

## 最新一轮：2026-10-02 REQ-012 仅规划

- 第二轮评审已完成并修订方案第 3–7、9 节：补上追加导入、草稿宽松校验、首期格式边界、原文生命周期、发布全入口隔离和公开计数口径。核对发现 PersonalController 旧收录/移除只 touch 日期而不增加 revision，SiteStatsController 统计所有专题；均为待实施修正，没有修改业务代码。评审结论为设计缺口已处理、真实用户易用性待验证。
- 用户要求设计方便非技术用户导入租房、注册教程、锻炼与经验总结的通用专题方案，没有要求实施。
- 新增 `docs/TOPIC_IMPORT_PLAN.md`，登记 FINNS-REQ-012（待用户确认），同步 NEXT_ITERATION_PLAN 与 CHANGELOG Planned。优先建议粘贴/TXT/Markdown 导入预览、场景模板、本人复制、私人预览及保存/发布隔离；个人模板、AI 与复杂文件解析分期。
- 静态核对 CollectionNewPage、CollectionEditorPage、CollectionDetailPage、CollectionWorkspaceController 和 V6：REQ-011 基础已存在，但当前没有导入功能；已发布内容 saveDraft 会直接改变公开读取的章节；未发布草稿缺少阅读预览入口；保存期间新增输入有被响应覆盖的代码风险。旧验收记录保留为历史证据，不能以之宣称这些新增体验已完成。
- 本轮仅文档变更，检查文档引用、需求 ID 及状态；未运行构建/业务测试/浏览器验证，未连接数据库或重启服务，以下 PID/运行状态均为历史记录、本轮未复核。VERSION 保持 0.4.0。`git status --short` 确认目录仍不是 Git 仓库。
- 下一步：用户要求实施后按新方案第一阶段继续，先明确发布快照与导入文档契约；不要重复建设 REQ-011，也不要把本次规划记为功能上线。

更新：2026-10-02。FINNS 0.4.0 本地开发版 REQ-001–007、009–011 已实施；REQ-008 暂缓。FINNS-REQ-011“结构化专题工作台”已完成 Flyway V6、草稿/发布 API、Vue 创作工作台和公开阅读页。主库已迁移到 V6，Web 正以 Java 17 / PostgreSQL profile 监听 `127.0.0.1:8080`。证据见 `docs/NEXT_ITERATION_PLAN.md` 第 16 节及 `docs/COLLECTION_BUILDER_PLAN.md`。工作区不是 Git 仓库；未提交或推送代码。
工作区：`E:/code/FINNS/AI-tome`。本目录不是 Git 仓库；未提交或推送代码。

## 开始前读取

1. `AGENTS.md`
2. `.agents/skills/finns-development/SKILL.md`
3. `NEXT_ITERATION_PLAN.md` 的第 9–16 节及 `COLLECTION_BUILDER_PLAN.md`（实际完成、待实施方案及验收边界）
4. `VERSION`、`CHANGELOG.md` 与相关代码

## 已实施结果

- PostgreSQL 17.11 本地开发库运行于 `127.0.0.1:5432/finns`，运行时二进制位于 `E:/tools/finns-runtime/postgresql-17.11/pgsql`，不注册系统服务，未向 C 盘安装。持久数据在忽略目录 `data/postgres/`。
- Flyway V1–V6 已在本机开发主库 `finns` 运行；`ddl-auto=validate` 启动校验通过。V6 增加专题格式/状态/revision、章节和条目，并将历史专题保持公开、回填到默认章节；旧 `collection_posts` 保留为回退依据。迁移审计批次 `h2-aitome-v1`：用户 1、文章 3、评论 1、专题 3、VPS 3；ownerName 均唯一回填，未解析归属为空。旧 H2 `data/aitome.mv.db` 保留。
- 最新 H2 备份：`data/backups/final-h2-20260928/aitome.mv.db`，SHA-256 `ED4828DB268C2F0DD65D326759DDB75F6BBF3BEF9F53771DA48E1E8199EB649D`；前一份 `data/backups/20260928-130218/aitome.mv.db` 也保留。
- 新增用户自选专题、排序/取消、公开专题详情、仅本人文章收录；新增个人资料、真实统计、我的文章和专题。VPS 路由保持 `/vps`，页脚可进入 VPS 指南；头部显示“自选专题”。
- Cookie CSRF、防会话固定轮换和服务端归属检查已加入；个人 DTO 不含 passwordHash。`/private/tools` 独立密钥隔离未更改。
- 新增 `scripts/postgres.ps1`、`scripts/migrate-h2.ps1`、`scripts/migrate-h2-dry-run.ps1`；`scripts/run.ps1` 使用 PG profile 并构建 Vue；`scripts/package.ps1` 一并构建 Vue/Maven 且检查退出码。
- `docs/DATABASE.md` 记录本地开发、配置、备份与恢复方法；`.env.example` 是格式示例，不会被 Spring Boot 自动加载。
- 规范开发入口：项目级流程见 `AGENTS.md` 与 `.agents/skills/finns-development/SKILL.md`；需求、设计、实施状态和验收边界见 `NEXT_ITERATION_PLAN.md`；版本变更见 `CHANGELOG.md`。

## 最新验证状态

- 2026-10-02 REQ-011：`./scripts/package.ps1` 完成 Vue/Vite 构建、H2 14/14 与 Maven package；`./scripts/test-postgres.ps1 -DatabaseName finns_test_public_pagination_20260929` 在 PostgreSQL 17.11 / Flyway V1–V6 上 14/14 通过。隔离库保留测试数据，不得当作主库或自动删除。
- 主服务已重启，当前 Java PID 37704（进程号会变化），仅监听 `127.0.0.1:8080`；主页、`/me/collections/new` HTTP 200，主库最新 Flyway 版本为 6。浏览器公开阅读页桌面和 390×844 检查通过，控制台无 warning/error；未向主库创建验收账号或专题。
- REQ-011 主要实现：`V6__structured_collection_workspace.sql`、`CollectionWorkspaceController.java`、专题章节/条目实体、`CollectionNewPage.vue`、`CollectionEditorPage.vue`、重构后的 `CollectionDetailPage.vue`。草稿仅作者可见，旧 revision 返回 409，外链仅允许 http/https。
- `./scripts/package.ps1`：本轮 Vue 3/Vite 生产构建通过；H2 测试 13/13；Maven package 成功，产物 `target/finns-0.4.0.jar`。
- 2026-09-30 用户要求启动项目后执行 `./scripts/run.ps1`：Vue/Vite 构建成功；Java 17 / Spring Boot 3.3.8 成功启动，PID 27676，监听 `127.0.0.1:8080`。启动触发主库 `finns` 的 Flyway V4、V5，日志显示应用 2 migrations 成功，当前 v5；两项仅新增索引。
- 运行态检查：主页、`/collections/1`、`/vps` 均 HTTP 200；`/api/stats` HTTP 200，返回 `1 creator / 3 content / 3 collections` 对应数据。仅回环地址可访问。
- 本次启动请求只运行了 Vue/Vite 生产构建、Maven 编译/启动与 HTTP 冒烟；没有重跑 H2 或 PostgreSQL 行为测试。上一轮对应测试证据仍见方案第 14 节。
- （2026-09-29 已执行）PostgreSQL 17.11 的 `finns_test_stats_20260929_1049` 全新 Flyway 空库迁移后 9/9 行为测试通过，包括评论分页和数据库统计计数对照；owner/无活动会话核验后删除该隔离库，主库 `finns` 保留。
- （2026-09-29 本轮复验）PostgreSQL 17.11 隔离库 `finns_test_public_pagination_20260929` 行为测试 12/12 通过；Flyway V1–V4 验证成功。数据库保留了分页验收用测试数据，不能当作主库，也不要自动删除。
- 首次 PG 空库回归曾因旧测试固定要求至少 3 篇演示文章而失败（实际仅 2 篇）；断言现改为检查公开 API 数组契约，空库重跑通过。
- 根 `VERSION`、Maven、npm package 和 npm lock 均已核对为 `0.4.0`。
- H2 dry-run：核对行数、大小写邮箱冲突、孤儿外键和专题归属，确认 PostgreSQL 与迁移审计计数相符，未修改业务行。
- PostgreSQL profile：恢复库测试前行数与源库一致，Flyway V1–V3 历史一致；Flyway 10.20.1 validate/apply、Hibernate validate、旧账号登录、文章/专题读取和登出通过。测试完成后已删除恢复副本，保留校验 dump；主库未改动。
- 浏览器：登录、个人中心 `/me`、专题详情 `/collections/1`、自选专题 `/my/collections` 验证通过；1440×900 桌面和 390×844 移动视口无横向滚动，顶部导航和个人区导航可用。两个独立账号会话验证个人专题互相不可见；`/`、`/collections`、`/collections/1`、`/vps`、`/me`、`/me/posts`、`/me/collections`、`/my/collections` 直达均返回 SPA。
- 评论分页浏览器验收：隔离 PostgreSQL 的 55 条讨论首次展示 20 条；加载更多后依次 40、55 条，最后隐藏加载按钮；API 和 UI 读取均命中同一测试文章。主应用仍连接主库 `finns`。
- 首页统计浏览器验收：最新 JAR 连接主库，界面显示 `1 创作者 / 3 公开内容 / 3 专题目录`；`GET /api/stats` 返回相同值，测试断言同时逐项对照数据库 `COUNT(*)`。
- 公开分页最终浏览器验收：Edge 使用隔离测试库，发现列表 20→40→59，文章/话题服务端筛选正确，`?post=<id>` 打开第二页内容；专题广场与自选目录 20→40→58，末页加载按钮消失。登录后 `/me` 与私有自选目录可直达；私人本地工具没有出现在个人中心导航。
- 视觉/窄屏复验：Edge 清除旧资源缓存后加载最新 CSS；1440×900 桌面交互通过。390×844 下 `/`、`/collections`、`/my/collections`、`/me`、`/me/posts`、`/me/collections` 的文档和 body 均无横向溢出，导航含“自选专题”。最初测量确实发现长测试标题溢出，增加移动端最小网格轨道与任意断行后复验通过；截图仅用于检查，没有保存到仓库。
- 本机串行只读冒烟：Windows 11/i5-13500H/约 29.7 GB RAM、极小数据集，预热 10 次后每接口采样 40 次；posts p50/p95 9.88/10.83 ms，collections 8.75/10.23 ms，错误数均 0。不是容量测试或 SLA。
- 安全行为测试：拒绝无 CSRF token 写请求；带令牌未登录写请求返回 401；登录用户可更新个人资料/选择专题/发布自己的文章并收录，不能将他人文章加入自己的专题。
- REQ-009 列表/详情浏览器交互复验：`/api/posts?page=0&size=20` 列表 DTO 不含 body；20,000 字正文在详情 API、点击卡片和 `?post=122` 直达时完整返回/显示。此次是功能验证，不构成视觉 QA、容量测试或 SLA 结论。
- REQ-010：隔离库 Flyway V5 与 PostgreSQL 行为测试 13/13 通过；`/api/collections/{id}/posts` 55 条内容的页面交互为 20→40→55，专题及个人内容列表均只返回投影字段。浏览器验证长正文仍经 `?post=` 完整加载；专题和个人内容页 1440×900、390×844 的最终视觉复验通过，长标题换行且无可滚动横向溢出。
- 2026-09-29 用户对性能参数问题回复“先不处理这个内容”：REQ-008 状态改为暂缓；不再发起参数追问，不对主库压测。当日 8081 隔离库临时应用已停止，主库未连接/迁移；PostgreSQL 行为测试只在隔离库写入并保留测试数据。2026-09-30 用户另行要求启动项目，主库启动状态见下方及方案第 15 节。

## 下一轮 AI 执行清单

开始前阅读 `AGENTS.md`、`.agents/skills/finns-development/SKILL.md`、`NEXT_ITERATION_PLAN.md` 第 9–14 节、`VERSION` 和 `CHANGELOG.md`。REQ-001/002/003/004/007/009/010 已有交付；只在源码或行为有回归时修复。

1. **FINNS-REQ-008 暂缓**：用户明确要求先不处理性能条件；不要重复追问或开展负载测试。仅用户明确恢复时再制定隔离测试方案；任何情况下不得对 `finns` 主库施压。
2. **FINNS-REQ-011 已完成**：不要重复创建迁移或回到旧文章集合页面。后续 AI 大纲、读者进度、协作编辑、图片上传和他人内容收录必须另立需求并单独评估。
3. 对用户之后提出的新功能追加稳定需求 ID、验收条件与非目标；沿用项目 Skill 的规划/实施/诊断边界。REQ-009/010 已完成，避免重复施工。

### 2026-10-02 REQ-011 实施交接

- 用户希望方便制作租房心得步骤、动漫阅读路线等专题。本轮将需求统一抽象为“模板 + 章节 + 有序条目 + 草稿/发布”的专题工作台，而不是为领域分别建功能。
- 条目第一期支持 NOTE、作者自己的 POST、LINK；模板为 GUIDE、READING_PATH、RESOURCE_LIST、CUSTOM。公开页面按路线显示，现有专题回填到默认章节并保持当前顺序。
- append-only Flyway V6 已实施；整个草稿文档事务保存和 `revision` 乐观锁已落地。草稿仅作者可见，发布后才进入广场与自选专题；归档会停止公开并移除自选关系。
- 新工作台以 Vue 3 独立路由实现模板、元数据、章节和 NOTE/POST/LINK 条目，上下移动是跨桌面/移动端的基础排序方式；保存状态可见，公开页按目录和编号路线显示。
- 版本仍为 0.4.0 / Unreleased；CHANGELOG 已记录。测试、数据库和运行证据见“最新验证状态”。

### 2026-09-29 本轮变更与验证边界

- 实现 `/api/posts` 与 `/api/collections` 分页，更新发现页、专题广场和自选专题目录；新增 append-only Flyway V4 排序索引，以及移动端文章标题断行。
- 主要代码文件：`src/main/java/com/aitome/content/ContentController.java`、`ContentPostRepository.java`、`ContentPost.java`、`CommunityCollection.java`、`src/main/resources/db/migration/V4__public_list_pagination_indexes.sql`、`src/test/java/com/aitome/AiTomeApplicationTests.java`、`frontend/src/pages/DiscoverPage.vue`、`CollectionsPage.vue`、`SelectedCollectionsPage.vue`、`frontend/src/assets/styles.css`。
- 更新 `.agents/skills/finns-development/SKILL.md`：明确重复需求识别，并要求实质改动用正反例检查 Skill 的触发/边界；增加 OpenAI 官方 Skill 设计与评估资料。`quick_validate.py -X utf8` 通过。没有运行 Codex 引擎级正反例 eval。
- 更新 `docs/DEVELOPMENT_REFERENCES.md`、`docs/NEXT_ITERATION_PLAN.md`、本交接和 CHANGELOG `Unreleased`；版本文件保持 0.4.0。
- 应用构建、H2/隔离 PostgreSQL 测试与 Edge 浏览器验收证据如上。仅隔离测试数据库应用 Flyway V4；主库只读核对为 V1–V3、1 用户/3 内容/3 专题，没有主库迁移或业务数据写入。
- 没有在 C 盘安装任何程序；没有初始化 Git、提交或推送。

### 2026-09-29 REQ-009 本轮交接

- `ContentController.java` 的公开文章分页列表只序列化卡片摘要字段，详情接口完整正文不变；`DiscoverPage.vue` 点击卡片后请求详情，支持正文加载失败重试并保留文章深链接。
- 本轮补充长正文行为测试并修正分页测试对“空数据库”的固定假设；打包/H2 12/12，隔离 PostgreSQL 12/12，Edge/CDP 点击与 `?post=122` 直达均验证 20,000 字正文显示。测试数据保留在 `finns_test_public_pagination_20260929`。
- 用户将 REQ-008 性能条件收集明确暂缓；不得再次追问，也没有做容量测试。无新迁移，主库未访问；测试应用 8081 已关闭，8080 未监听。仍为 0.4.0 / Unreleased。

### 2026-09-29 REQ-010 本轮交接

- `ContentPostSummary` 和 `ContentPostRepository` JPQL 投影用于公开与个人文章列表；专题关联查询直接选择卡片列。查询没有物化 `body` 字段。
- 专题 API 默认 20、最大 50，返回 items/page/size/total。`CollectionDetailPage.vue` 加载更多和重试；`MyPostsPage.vue` 加载更多、文章标题直达正文。V5 添加专题分页及个人列表排序索引。
- 验证命令：`./scripts/package.ps1` H2 13/13、Vue/Vite build 和 Maven package 成功；`./scripts/test-postgres.ps1 -DatabaseName finns_test_public_pagination_20260929` PostgreSQL 17.11/Flyway V1–V5 13/13。浏览器检查专题详情20→40→55、列表无 body、个人列表20条/hasMore、点击专题卡片显示20,000字正文。
- 本轮追加修复桌面长标题最小宽度与越界装饰光晕；绕过浏览器缓存/Service Worker 后，专题详情和个人内容页面在 1440×900、390×844 均无横向滚动，首屏各 20 条。视觉截图保存在 `E:/Temp/FINNS-REQ010-final-*.png`。最终 `scripts/package.ps1`：Vue/Vite build、H2 13/13、Maven package 均通过；本次 CSS-only 变更后未重跑 PostgreSQL 测试（之前 PostgreSQL 17.11 / Flyway V1–V5 13/13 已通过）。
- 未执行：外部性能负载/SLA测试（REQ-008 按用户要求暂缓）。测试库 `finns_test_public_pagination_20260929` 保留用户级验收行；不要自动删除。该段原属 REQ-010 的 V1–V5 历史证据；当前主库与隔离库均已由 REQ-011 验证到 V6。不要将测试库用于主应用。

## 重启与访问

```powershell
./scripts/postgres.ps1 status
./scripts/run.ps1
```

主 PostgreSQL 开发库监听 `127.0.0.1:5432`，当前主应用监听 `127.0.0.1:8080`；临时隔离测试服务 `8081` 已关闭。主库 Flyway V1–V6；隔离测试库也为 V1–V6，并保留验收数据。默认启动脚本可直接重用：

```powershell
./scripts/run.ps1
```

本轮启动已按用户请求应用 V4–V5 索引迁移；服务保持运行。停止应用可在对应运行终端按 Ctrl+C。不要重复启动第二个 8080 实例或把隔离测试库伪装为主站。

## 版本和流程

版本文件/CHANGELOG 已记录 0.4.0，根 `VERSION`、Maven、npm package/lock 均一致。`DEVELOPMENT_PLAN.md` 内 Java 21/Next.js 是历史内容；当前约定继续 Java 17 + Vue 3。缺少工具时优先用户指定的 D/E 盘方案，勿在 C 盘安装。后续每项开发按 FINNS skill 执行：先记录需求和验收，再按小步完成 API/数据库迁移/页面/测试/版本记录，并把实测证据及未验证项更新到交接文档。

