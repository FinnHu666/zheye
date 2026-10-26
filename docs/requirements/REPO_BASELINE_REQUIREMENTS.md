# FINNS 工程化基线（FINNS-23 及子项 FINNS-24/25/26）需求草稿

日期：2026-10-25；文档修订：**6**（草稿；修订 3 吸收两轮评审意见，修订 4、5 写入用户 2026-10-25 的决定，修订 6 吸收第三轮复核意见，见[决定记录](REPO_BASELINE_DECISIONS.md)）；目标产品版本：**0.8.0**（用户决定 U9/U6：0.7.0 先发布封板，本任务在新周期 0.8.0 完成；Plane 周期“FINNS 0.8.0｜项目质量建立”；本任务不升级应用版本号）；当前源码/部署版本：0.7.0；本轮模式：需求评审。

状态：**G0 已登记；G1 已通过（2026-10-25，用户明确确认评审后的修订号为修订 6）**。三轮只读子代理评审（独立性有限）：第一、二轮“退回”，第三轮（修订 3–5 复核）“带条件通过”；修订 6 吸收第三轮意见，**用户明知修订 6 未经复核仍确认以它为评审后的版本**（ENG-D12、ENG-D14）。仍待：Plane 同步检查点（PLANE 门禁）；U9 分支基点与命名、U1 提交分组与信息按 §9 作为实施期细节转入后续门禁。本文是需求，不是原型、设计或实施；评审记录：[REQUIREMENT_REVIEW_REPO_STRUCTURE.md](REQUIREMENT_REVIEW_REPO_STRUCTURE.md)。

## 1. 来源与分层

| 条目 | 分类 | 内容 |
| --- | --- | --- |
| FINNS-23 原正文 | 用户明确要求 | “针对前后端分别建立仓库，进行版本控制和提交；对目前代码做代码审查，建立好审查的规则” |
| 2026-10-25 对话 | 用户明确要求 | 主要目的是目前代码结构比较混乱，`src` 直接放在 AI-tome 根目录 |
| 同上 | 用户已作决定 | 后端目录名定为 `backend` |
| 同上 | 用户明确要求 | 还要固定前后端代码规范（例如后端 SQL 格式、代码规范）并包含审查 |
| 一个仓库、目录拆分结构 | 用户已作决定（ENG-D01） | 2026-10-25 用户对 U8 选 A：“一个仓库，但是将结构拆分进行”，即单仓库、后端移入 `backend/`、前端留 `frontend/`；原要求“分别建立仓库”据此收窄为目录拆分 |
| 五类内容与三个子项的划分 | AI 方案假设 | 类别：1 仓库结构与版本控制；2 代码与数据规范；3 审查与质量门禁；4 安全与依赖；5 可复现与文档。FINNS-24=类别 1；FINNS-25=类别 2 及类别 3 的规则；FINNS-26=类别 3 首轮审查及类别 4、5 |
| 部署仍是前端产物打进同一个 jar | AI 方案假设 | 用户未回答，列为 U7 |
| 根级文件归属、根 `target/` 用途 | AI 默认 | 见 U14 |

## 2. 现状事实（2026-10-25 核对。“评审者”＝评审子代理核对；其中我自己复核过的标“已复核”）

1. 根目录同时放着后端（`pom.xml`、`src/`、`.mvn/`）、前端（`frontend/`）和项目级内容（`docs/`、`scripts/`、`.agents/`、`VERSION`、`CHANGELOG.md`、`README.md`、`AGENTS.md`、`DEVELOPMENT_PLAN.md`、`.env.example`、`.codex/`、`.claude/`）。
2. 前端构建输出到 `../src/main/resources/static`，`emptyOutDir:true`（`frontend/vite.config.js:40-41`）；`pom.xml:78` 把 `frontend/src/lib/topic-builder` 的 `html-policy.json`、`html-fixtures.json` 当作后端资源。**资源目录写错时 Maven 不报错，失败出现在测试和运行时**（消费端：`TopicHtmlPolicy.java:27`、`TopicBuilderTests.java:115`）。后端因此依赖 `frontend/` 的位置。
3. `src/main/resources/static` 被跟踪 3 个文件：`index.html` 与 2 个哈希资产；工作树现状：2 个哈希资产已删、`index.html` 已改；3 个新哈希资产和 `static/vendor/` 未跟踪且未忽略。
4. 默认 H2 `jdbc:h2:file:./data/aitome`（`application.yml:5`）相对**启动目录**，`ddl-auto: update`（`:10`），用默认配置打开真实文件可能改动其结构。`.gitignore` 的 `data/`、`target/` 不带锚点，也会匹配 `backend/data`、`backend/target`（已复核）。
5. 其他相对启动目录的位置：`application-postgres.yml:26-27`（H2 迁移源 URL 与 source-file）、`AccountMailConfig.java:28`（`data/dev-mail`）；读取出信箱的 `scripts/lib/dev-signup.mjs:5`、`seed-user-admin-preview.mjs:9`、`show-dev-mail.ps1` 按根目录解析。**`H2ToPostgresMigration.java:41` 用绝对 source-file 判断源文件存在，`:51` 用相对 URL 连接，二者可能指向不同文件**（已复核）。
6. Maven 的 `spring-boot:run` 与 surefire 的有效启动目录是 pom 所在目录（据 Spring Boot Maven 插件 `workingDirectory` 默认值，评审者推断，**未实测**）。因此即使从根目录执行 `mvn -f backend/pom.xml spring-boot:run`，`./data` 也会落到 `backend/`。入口启动目录不一致：`start-topic-builder-test.ps1:20` 无 `-WorkingDirectory`（用调用者目录），`start-account-preview.ps1:52`、`start-local-main.ps1:44` 用 `$projectRoot`；`run.ps1:30` 的 mvn 在 `Push-Location` 块之外。
7. `package.ps1`、`test.ps1`、`migrate-h2*.ps1` 先进入根目录再执行 `mvn -s .mvn/settings.xml ...`；`.mvn/maven.config`（`-s`、`.mvn/settings.xml`）相对启动目录；`mvn -f` 时 Maven 是否从 pom 目录查找 `.mvn` 未实测。README 没有单独的“构建”步骤，只有会启动 PostgreSQL 与服务、使用 `npm install` 的 `scripts/run.ps1`。
8. **`migrate-h2-dry-run.ps1:11` 先启动本机 PostgreSQL，`:24-26` 用 `postgres` profile 运行；`application-postgres.yml:3` 默认库是 `127.0.0.1:5432/finns`，Flyway 启用；`V19__collection_creation.sql`、`V20__collection_activity.sql` 为未跟踪文件。按静态阅读，不覆盖 `FINNS_DB_URL` 就跑这个脚本，可能把未提交的迁移应用到主库**（已复核脚本与配置，未实跑）。
9. 根 `target/` 现存 `finns-0.7.0.jar`（10-24 构建）、未入库的验收脚本与备份（`target/finns-19-implementation/`、`target/reverted-session-20261024/`）、隔离 PostgreSQL 集群（`target/topic-builder-pg`，见 `docs/TECHNICAL_HISTORY.md:63,70`）；`frontend/tests/*.test.js`、`scripts/test-topic-builder-*.mjs`、`docs/prototypes/*/vite.config.mjs` 读写根 `target/`。搬迁后脚本若仍指 `target/`，会无声使用旧包。
10. 原型依赖 `frontend/` 位置：`docs/prototypes/*/vite.config.mjs:1` 引用 `../../../frontend/node_modules/vite/...`，`site-access` 的 alias 指向 `../../../frontend/src`；`.claude/launch.json` 同样写死。原型 `site-access` 此前已知因组件被删而无法构建（见交接记录），基线本身可能失败。
11. 未入库的 `.claude/launch.json` 未被忽略（误提交风险），引用陈旧的 `target/finns-0.6.0.jar`、`@target/c5-args.txt` 及 `D:/java/...`；`.codex/config.toml` **已入库**（Plane MCP 连接，无本机路径）。Project Runner 是独立的本机工具，配置在仓库之外，**不属本任务**（用户 2026-10-26 澄清：与 FINNS-24 无关，最多是技术对接，见 ENG-D15）。
12. 现行规则文件含后端路径：`AGENTS.md:22`、`README.md:17,42,46,60`、`docs/DEPLOYMENT.md:3,10,12,36,37`、`docs/DATABASE.md`（多处 `data/`）、`docs/TECHNICAL_HISTORY.md:11,34,36-40`（`:11` 的 `../pom.xml` 是相对链接，搬迁后成死链）、`docs/NEXT_ITERATION_PLAN.md`（`AGENTS.md:7` 的首读文件，含 `src/main`）、`.env.example` 注释。另有 38 个 `docs/` 文件提到 `src/main`、`src/test` 或 `pom.xml`，多为历史交接与证据，约 15 处是 `](../../src/main/java/...)` 相对链接。
13. `docs/delivery-progress.json` 约 150 条证据（去重约 80 个路径，数字随登记变化），**全在 `docs/` 下**；哈希按文件原始字节计算（`check_progress.py:36,41`）；约 22–23 个登记文件工作树是 CRLF 且登记的是 CRLF 字节。**既有失配 1 项（已复核）**：`docs/AI_HANDOFF.md`（foundation-collection G9，早于本轮，我核对过本轮改动前即已失配）。另有本任务自己的文档（本草稿、决定记录）作为证据，每次修订都会漂移，整理前需刷新登记，不计入既有失配。检查器每个任务只报第一个未满足门禁，不能用其输出当基线。
14. 无 `.gitattributes`、`.editorconfig`；索引为 LF，工作树 CRLF 约 112、混合 7（评审者）；换行取决于机器配置（系统级 `core.autocrlf=true`，用户级 `input`）。Flyway 对换行是否敏感**未核实**。已被跟踪的 `.agents/skills/.../__pycache__/*.pyc` 有 1 个，检查器运行可能写脏它。
15. `docs/DEPLOYMENT.md:51` 规定含中文的 `.ps1` 必须带 UTF-8 BOM（并在 Windows PowerShell 5.1 验证）；6 个含中文脚本无 BOM：`migrate-h2.ps1`、`migrate-h2-dry-run.ps1`、`package.ps1`、`run.ps1`、`start-local-main.ps1`、`test.ps1`（已复核）。
16. `AiTomeApplicationTests.java:32-34` 要求 `/index.html` 返回 200 且含 `id="app"`；`test.ps1` 不构建前端；H2 测试 `flyway.enabled:false`，迁移脚本只在 PostgreSQL 路径被验证（`scripts/test-postgres.ps1`）。
17. 提交历史 27 条，范围是功能名（`content`、`review`、`skills`），没有分层范围，另有 2 条不是 conventional 格式。
18. 工作区约 138 项未提交改动（67 修改、5 删除、66 未跟踪条目，含上述 V19/V20，数字随工作变化）；分支 `release/0.7.0`，远程 `origin` 为 `FinnHu666/zheye`；0.7.0 G8 已发布到本机，G9 待办；`docs/DEPLOYMENT.md` 写明**不支持回退到更早的程序包**（发布层限制，与本项的 Git 回退是两回事）。
19. 评审者查询：8080、5173、4175、8091、8094、5200、55439、5432、17890 当时无监听，无 java/postgres 进程；node 进程用途未核实；这只是当时状态，整理前须复核。
20. **主站 PostgreSQL 集群当前不是干净停止状态**：`data/postgres/postmaster.pid` 存在（2026-10-21 10:50，写着旧绝对路径与 PID 29264），而当前没有 java/postgres 进程（已复核）；评审者读 `pg_controldata` 得到“在运行中”、最后修改 2026-10-21 11:20，我未复核该输出；`AI_HANDOFF.md` 记录过同类“中断后的自动恢复”。因此下次启动会做崩溃恢复，此时做文件级拷贝或移动不是干净备份。
21. **`scripts/postgres.ps1:35-39` 以目标目录里没有 `PG_VERSION` 为条件执行 `initdb`，随后在 `:48-59` 创建 `finns_app` 角色和空的 `finns` 库**（已复核）；`run.ps1:11`、`migrate-h2-dry-run.ps1:11` 都调用它。所以脚本改到 `backend/data` 而真实数据尚未迁入的任何时刻，运行这些脚本都会在 `backend/data/postgres` 里静默建出一个空主库。
22. `data/` 里除 PostgreSQL 与 H2 外还有 `backups/`、`deployments/`、`lexicons/`（REVIEW-D23 下载的词库，未入库，`data/` 是唯一副本，见 `AI_HANDOFF.md:221`，评审者）；PostgreSQL 的配置文件未发现绝对路径设置（评审者读 `postgresql.conf`、`pg_hba.conf`），PostgreSQL 在目录移动后的启动行为**未核实**。

## 3. FINNS-24 需求项（整理仓库结构与版本控制基线）

| ID | 需求 | 说明 |
| --- | --- | --- |
| ENG-R01 | 后端移入 `backend/` | `pom.xml`、`src/` 移入；`.mvn/` 见 U12；`frontend/` 位置不变；**纯 `git mv` 单独一个提交，引用同步另起提交**；独立分支；**建分支、提交、`git revert` 演练均需用户逐次授权** |
| ENG-R02 | 引用同步 | `vite.config.js` 输出路径、`pom.xml` 对前端共享文件的引用、`scripts/` 全部脚本（修改后保持显式检查 `LASTEXITCODE`；已有 BOM 的脚本仍带 BOM）、`.claude/launch.json`、`target/finns-19-implementation/start-verification.ps1` 等未入库入口；`scripts/postgres.ps1` 在目标 `PG_VERSION` 不存在时必须拒绝 `initdb`（仅显式传参才允许新建），调用它的脚本继承该保护；`migrate-h2-dry-run.ps1` 增加默认库守卫（拒绝连接 `finns`）。原型配置因 `frontend/` 不移动无需修改，由 A11 验证结果不变 |
| ENG-R03 | 运行时位置 | **`data/` 整体迁入 `backend/data/`（U2=B，ENG-D07）**；启动目录统一为 `backend/`（`spring-boot:run`、surefire、各启动脚本），使默认的 `./data` 即 `backend/data`；概设须列“入口×有效启动目录×落点”表，覆盖默认 H2、PostgreSQL 便携库及日志、开发出信箱（含 `seed-user-admin-preview.mjs`、`show-dev-mail.ps1`、`dev-signup.mjs`）、H2 迁移源（`FINNS_H2_SOURCE_URL` 与 `FINNS_H2_SOURCE_FILE` 必须指向同一个绝对路径）、`data/backups`、`data/deployments`、`data/lexicons`；所有引用 `data/` 的脚本与现行文档同步。**真实数据的迁移是最后一步，且脚本改动与数据迁入属同一个受控窗口**：脚本合并后到数据迁入前，`postgres.ps1` 因守卫而拒绝启动。迁移前置（均需用户授权）：0.7.0 已封板；经授权先启动并 `pg_ctl -m fast stop` 一次，用 `pg_controldata` 确认“已关闭”且 `postmaster.pid` 不存在；按 `DEPLOYMENT.md:49` 只停止精确进程（核对 `main.pid`、8080 归属与命令行）；备份落在 `data/` 与 `backend/data` **之外**，含 `pg_dump` 逻辑备份、`pg_restore -l` 校验、恢复到 `finns_restore_check_*` 并逐表行数比对，另备 H2 文件、`deployments`、`backups`、`lexicons` 的校验和。迁移后旧 `data/` **改名**为 `data.pre-move-<日期>`（不原地保留，防止旧脚本静默启动过期集群），由用户确认后才删除。jar 位于 `backend/target`；根 `target/` 只放证据、前端测试产物、原型产物和隔离集群；脚本找不到 jar 时报错，不回退到根 `target/`；整理期间不清理根 `target/`；0.8.0 周期重建出的 `finns-0.7.0.jar` 不用于部署、不覆盖已发布的包。不得静默新建空库 |
| ENG-R04 | 换行与字符集 | 增加 `.gitattributes`、`.editorconfig`，只管**换行与字符集**（`*.ps1` 为 `utf-8-bom`），`.editorconfig` 中不得出现缩进、行尾空白类键（归 FINNS-25）。不变量：被登记为证据的 `docs/` 文件，工作树字节在整理前后逐个相同（本需求文档与 `AI_HANDOFF.md` 例外，见 A07）；`.gitattributes` 不对 `docs/**` 做换行转换（具体属性如 `-text` 或 `eol` 由概设实测确定，判据：登记证据文件的字节与 `git add` 后的 blob 都不变，且 `git status` 对 `docs/` 不新增改动）；整理中不得执行 `git add --renormalize` |
| ENG-R05 | 提交与分支约定 | 现有历史没有分层范围，这是**新约定**；至少区分 `backend`、`frontend`、`docs`、`scripts`；写成文档；归属见 U11 |
| ENG-R06 | 构建产物不入库 | 按 U3 的决定（ENG-D03）：`static/`（含 `static/vendor/`）整体移出跟踪，以锚定路径加入忽略（`/backend/src/main/resources/static/`）；`data/`、`target/` 规则同样锚定为 `/data/`、`/target/`、`/backend/data/`、`/backend/target/`、`/frontend/target/`，避免误伤 `frontend/src/data` 等；`static/` 移出跟踪（3 个删除）放在**独立提交**，不计入 A01 的 R100 对比；后端测试与启动前须先有前端构建产物，由脚本先构建或在 README 明示，具体方式留概设 |
| ENG-R07 | 版本号与现行文档更新 | 四处版本位置变为 `VERSION`、`backend/pom.xml`、`frontend/package.json`、`frontend/package-lock.json`；更新 §2 第 12 条列出的现行文档；记 `CHANGELOG.md` Unreleased 与交接；更新交接前先按 A07 冻结证据基线 |
| ENG-R08 | 路径对照与链接 | 新增“旧路径→新路径”对照；历史交接与验收证据不批量改写，约 15 处历史相对链接死链列为**已知豁免**并列清单 |
| ENG-R09 | 本机工具目录 | `.codex/` 保持入库；`.claude/` 见 U5 |
| ENG-R10 | 无行为变化 | 业务 Java/Vue 源码、测试、迁移脚本、接口行为的内容零改动，除路径与引用外只有重命名；不改 `application*.yml` 的默认值 |
| ENG-R11 | 停止与回退条件 | 见下方“停止条件”；不提供回退到旧程序包的运行能力（`DEPLOYMENT.md` 既有限制） |
| ENG-R12 | 脚本 BOM | U10=A：给 6 个含中文无 BOM 的 `.ps1`（`migrate-h2`、`migrate-h2-dry-run`、`package`、`run`、`start-local-main`、`test`）补 UTF-8 BOM，保证 Windows PowerShell 5.1 可运行 |

**停止条件与处置**（任一发生即触发）：
(a) 整理后用例数或结果与基线不一致（先分辨基线不稳定还是搬迁导致）；(b) **数据迁入前**根 `data/` 出现非预期变化（比较范围：H2 文件、`deployments`、`backups`、`lexicons` 的哈希，不含 `data/postgres` 内部 WAL 等易变文件，快照取在干净停止之后），或**数据迁入前**真实路径下出现 `backend/data`（副本演练在隔离目录进行，不算）；(c) `git diff -M` 显示已跟踪后端文件有 R100 重命名以外的变更（`static/` 移出跟踪的独立提交除外）；(d) 历史追踪抽样失败；(e) 证据失配集合新增；(f) 任一入口启动了根 `target/` 的旧 jar 或连接了默认主库 `finns`；(g) 脚本在 `PG_VERSION` 缺失时新建了集群。
**处置**：停止后续步骤，保留现场，不删除任何文件，不执行 `git reset --hard/checkout/stash`（工作区含未提交改动），向用户报告，由用户决定放弃分支（放弃=不合并并删除该分支，需授权）。已合并后用 `git revert`；数据迁移失败时：停止，**不删除**任何一侧，旧位置（已改名的 `data.pre-move-<日期>`）与新位置各自核对校验和，用户决定回移（回移同样先在副本上演练并校验）还是继续。

**可观察验收**

| ID | 验收 | 方式 |
| --- | --- | --- |
| ENG-A01 | 整理前后两个提交对比：已跟踪的后端文件全部是 R100 重命名，没有 A/D/M（`static/` 移出跟踪的独立提交不在对比内）；根目录无 `src/`、`pom.xml`、`.mvn/`；完整“前端构建 + 后端测试”之后根目录仍无 `src/`；构建后 `backend/.../static/index.html` 存在，且其引用的哈希资产都在 | `git diff -M --name-status`，目录检查，自动 |
| ENG-A02 | **基线先行**：整理前在记录了提交哈希、工作树是否脏、环境前置（如清除 `FINNS_MAIL_*`、使用的 PowerShell 版本）的状态下，实测后端 H2 全量、前端 `npm test`、原型 `site-access` 测试的用例数与失败数（surefire 报告为准；并发类用例多次运行并记录不稳定性；基线有失败须先报告用户，不以“一致”掩盖）；另在隔离库 `finns_test_*` 用 `scripts/test-postgres.ps1` 跑一次，证明迁移脚本被打包并可应用。整理后逐项相同。因 `static/` 不入库（ENG-D03），基线与整理后的后端测试**都须先执行前端构建**并记录该步骤 | 自动 |
| ENG-A03 | 用**隔离库**启动（不得使用默认配置打开 `data/aitome` 或主库）；启动的 jar 路径在 `backend/target`，且 SHA256 与本次构建输出一致；jar 内含 `static/index.html` 与 `topic-builder/html-policy.json`；首页与 `/api/stats` 返回 200；`/me/posts` 直达与刷新返回含 `id="app"` 的前端入口；浏览器人工检查一次 | 自动加人工 |
| ENG-A04 | **先在数据副本上演练**“`data/` 迁入 `backend/data`”：副本放在隔离目录，PostgreSQL 用**非 5432 端口**，演练可重复；H2 文件哈希一致，PostgreSQL 集群可启动，关键表行数迁移前后一致（只读查询），备份落在 `data/` 与 `backend/data` 之外，`pg_dump` 逻辑备份可 `pg_restore -l` 并恢复到 `finns_restore_check_*` 逐表比对，各文件校验和已记录；**回移演练**：把演练结果移回再核对。通过后，经用户授权、0.7.0 封板、主站按精确进程停机、PostgreSQL 干净停止之后，才对真实 `data/` 执行同样步骤，旧 `data/` 改名为 `data.pre-move-<日期>`，根目录不再有 `data/`。其余入口按 R03 的“入口×有效启动目录×落点”表逐个验证：运行后 `backend/data` 内容与迁入前一致；H2 迁移 dry-run 只可对 `finns_test_*` 与 H2 副本执行（覆盖 `FINNS_DB_URL`，源 URL 与源文件指向同一副本绝对路径），不连接默认库 `finns`；负向用例：根 `target/` 有旧 jar 时脚本报错而不是启动它；负向用例：`backend/data/postgres` 不存在时运行 `postgres.ps1`（不带显式参数）报错而不新建集群 | 自动加人工 |
| ENG-A05 | 历史追踪抽样不少于 5 个已跟踪文件（含 `pom.xml`、一个迁移 SQL、一个测试类、一个 Java 源文件、一个小文件），`git log --follow` 追到整理前历史；未跟踪文件（如 V19、V20）不在抽样内，其归属依 U1 | 自动 |
| ENG-A06 | 在搬迁提交紧邻的临时分支上，经用户授权演练 `git revert`，回退后已跟踪文件与搬迁前提交的 `git diff` 为空；记录“被忽略目录（`backend/target`、`backend/data`）不会被恢复” | 人工记录 |
| ENG-A07 | **整理前**导出基线清单（登记路径→登记哈希→当前哈希→是否匹配）并冻结其文件哈希；既有失配仅 `AI_HANDOFF.md`（本任务文档的哈希在冻结前先刷新）；整理后：除本需求文档、`AI_HANDOFF.md` 和经批准更新的现行文档外，登记文件字节与基线逐个相同，失配集合不新增；检查器运行须设 `PYTHONDONTWRITEBYTECODE=1`，避免写脏已跟踪的 pyc | 自动 |
| ENG-A08 | **衔接项（与 FINNS-26 可复现验收共用，本项只验证路径）**：在本地仓库路径的干净克隆（非 GitHub）按 README 构建；U1 已决定先提交（ENG-D02），改动提交后可比；步骤须含“先 `npm ci && npm run build`”（U3 已决定不入库） | 人工 |
| ENG-A09 | 已有 BOM 的 `.ps1` 修改后仍有 BOM；R12 的 6 个脚本补 BOM 后以 `0xEF 0xBB 0xBF` 开头，且能被 Windows PowerShell 5.1 与 pwsh 7 各解析一次；R02 改动的每个脚本里外部命令后的 `LASTEXITCODE` 检查数量不少于改动前；`.editorconfig` 与 `DEPLOYMENT.md:51` 不冲突 | 自动 |
| ENG-A10 | 现行规则文件（`AGENTS.md`、`README.md`、`DEPLOYMENT.md`、`DATABASE.md`、`TECHNICAL_HISTORY.md`、`NEXT_ITERATION_PLAN.md`、`.env.example`、`.agents/skills`）中没有指向旧位置（`src/main`、`src/test`、根 `pom.xml`、根 `.mvn`、根 `data/`、根 `target\finns-`）的引用或死链，含 `scripts/*.mjs` 对 `../data/` 的引用；历史文档死链的豁免清单**在整理前由扫描生成并存档**，整理后出现不在清单内的死链即失败。`.agents/skills` 下检查器脚本只依赖 `--root`，确认无需改 | 自动加人工 |
| ENG-A11 | `frontend` 构建与测试、原型 `site-access` 的构建与测试结果搬迁前后一致（基线中已知失败原样记录） | 自动 |
| ENG-A12 | 文档与配置类需求项：`.gitattributes`、`.editorconfig` 存在，`git check-attr` 证明 `docs/**` 不转换，且登记证据文件整理前后字节相同；提交约定文档存在且含四个范围；四处版本一致；`CHANGELOG.md` Unreleased 与交接已更新；路径对照存在；`.codex/config.toml` 未变；`.claude/` 依 U5 用 `git check-ignore` 或内容清理验证；U3 落实后 `git status` 无遗留未跟踪构建产物（含 `static/vendor/`） | 自动 |
| ENG-A13 | **Flyway 与换行（U4=A）**：整理后的 jar 在已迁移（V1…）的主库恢复副本（`finns_restore_check_*`）上启动，Flyway 校验通过；每个 `V*.sql` 在新旧 jar 内的字节哈希相同。**主库本身不用于此验证** | 自动 |
| ENG-A14 | **无构建产物时的行为**（U3=B）：在没有 `static/` 的干净树上运行 `scripts/test.ps1`，要么先自动构建前端，要么以明确提示失败；两种之一由概设选定，验收只检查行为与提示可观察 | 自动 |
| ENG-A15 | **R02 点名入口**：`.claude/launch.json`、`start-verification.ps1` 在整理后各被打开或解析一次，路径均指向新位置 | 人工 |

**需求项↔验收覆盖**：R01→A01、A05、A06；R02→A02、A03、A04、A09、A11、A15；R03→A03、A04；R04→A07、A09、A12、A13；R05→A12；R06→A12、A14；R07→A10、A12；R08→A10；R09→A12；R10→A01、A02；R11→A04、A06 与停止条件；R12→A09。

## 4. 非目标

- 不拆成两个仓库（U8 已决定）；不新建远程仓库，不推送，不设置分支保护。
- 不做独立部署，不改跨域、Cookie、CSRF、SPA 回退等机制（U7）。
- 不改业务功能、数据库结构与数据**内容**，不升级应用版本号（数据的**位置**按 U2=B 迁移，属 R03 的受控步骤）。
- 不批量改写历史文档和验收证据里的旧路径，不重登已失配的证据哈希（U13）。
- 不提供回退到旧程序包的运行能力。
- 不覆盖已发布的 0.7.0 程序包，0.8.0 周期重建的同名包不用于部署。
- 不安装工具到 C 盘；缩进、行尾空白等代码格式规则归 FINNS-25。

## 5. 风险与前置条件

- **前置**：约 138 项未提交改动需先按 U1 提交（分组与信息由用户指定并授权，须同时问明 `static/` 的删除、修改与新资产是否进封板提交）；整理期间不得有其他会话写入工作树；运行实例整理前须再核对并停止；任何迁移相关验证只用 `finns_test_*` 库与 H2 副本。
- 搬目录后若 `mvn` 在 `backend/` 下启动，`./data` 解析到 `backend/data`，**可能静默新建空开发库，且被 `.gitignore` 掩盖**；开发出信箱落 `backend/data/dev-mail`，读取脚本仍读根目录；H2 迁移的存在性检查与连接可能指向不同文件；迁移 dry-run 脚本默认连主库并带未提交迁移（§2 第 8 条）。
- `static/` 不再入库时，新克隆必须先构建，**连后端 H2 全量测试都过不了**。
- 换行规则会使大量文件在 diff 中显示为已修改，并可能改动已登记证据的字节。
- 搬迁与 0.7.0 发布（G8/G9）的先后影响发布前后的热修复路径与回退演练。
- 按 U9，本任务在 0.7.0 发布封板之后、在 0.8.0 周期内执行。Plane 上 FINNS-24 的 10-25 至 10-26 日期与三个子项标题里的“0.7.0”已与该决定不符，待你确认后更新（见 [Plane 记录](REPO_BASELINE_PLANE_P01.md) P02–P05）。0.7.0 封板本身（G8/G9 及 FINNS-18/19 的发布）不属于本任务。
- **U2=B 的专属风险**：移动主站 PostgreSQL 集群（`data/postgres`）、`data/deployments`、`data/backups`、`data/lexicons` 与开发库需要主站停机和备份核对；主集群当前处于“异常中断后遗留”状态（§2 第 20 条），必须先干净停止；脚本改动与数据迁入之间的窗口会静默新建空主库（§2 第 21 条），故两者属同一个受控窗口；`DEPLOYMENT.md`、`README.md`、`DATABASE.md`、`.env.example`、交接记录里的启动命令（例如 `-ReleaseDirectory data/deployments/...`）须同步（Project Runner 等外部工具的路径更新是技术对接，不在本任务范围），既有历史记录里的 `data/` 路径不批量改写；迁移失败按停止条件处置，不删除任何一侧。

## 6. 需用户决定（每项含备选，不由 AI 默认）

**已决定（2026-10-25，用户，详见[决定记录](REPO_BASELINE_DECISIONS.md)）**：U1=A 先提交；U2=B 数据目录迁入 `backend/data`；U3=B 不入库；U4=A、U12=A、U13=A、U14=默认（四项均经用户单项确认，ENG-D13）；U5、U7、U10、U11：用户答复“其余按推荐”，取值见决定记录 ENG-D08；U6=目标版本 0.8.0（周期“FINNS 0.8.0｜项目质量建立”，日期随该周期排期）；U8=A 单仓库、目录拆分；U9=A 先发布 0.7.0 并封板，再在 0.8.0 完成本任务；G2 已豁免（ENG-D09，仅 FINNS-24）。
**实施期细节（不阻断 G1，转入 G3/G5 门禁）**：U9 的分支基点与命名（默认 `codex/<task>-<purpose>`）；U1 的提交分组与信息（须由用户指定并授权，并问明 `static/` 的删除、修改与新资产是否进封板提交）。下表中已决定项保留原备选供追溯，行首标“✅”。

| 编号 | 事项 | 备选与后果 |
| --- | --- | --- |
| U1 ✅A | 约 138 项未提交改动 | A 先提交（需用户指定范围与信息），整理基于干净树，A02/A07/A08 可比；B 保留，在新 worktree 整理：新 worktree 不带这些改动，也不带 `data/`、`target/`、`node_modules/`，A08 无法通过（克隆不含未提交内容），A07 要改按字节比较，整理后需手工合并，且不得在新 worktree 运行 `postgres.ps1` 启动分支。AI 不自行提交 |
| U2 ✅B | 数据目录处理 | A 数据留根目录，显式指定工作目录；B 把 `data/` 挪进 `backend/data`（移动主站 PostgreSQL 集群、`data/deployments`、`data/backups`，需主站停机与备份核对，并同步 `DEPLOYMENT.md`、`README.md`、`DATABASE.md`；失败要有回移与校验和方案）；C 改为显式环境变量路径（改默认配置，属实现层变更，会改默认配置，属实现层变更，未选）。A 改动最小 |
| U3 ✅B | `static/` 构建产物 | A 继续入库（克隆即可测试与运行，每次构建产生噪音，须同时决定 `static/vendor/` 是否入库）；B 移出跟踪并忽略（历史干净，但新克隆必须先构建，A02、A08 须含构建步骤） |
| U4 ✅A（用户单项确认） | 换行与字符集规则 | A 增加规则，统一代码文件为 LF，`docs/**` 不转换，`*.ps1` 为 UTF-8 BOM；B 不加规则，保持现状。选 A 时：6 个无 BOM 脚本与规则冲突，须同时决定 U10；Flyway 对换行是否敏感未核实，需在整理前已迁移的隔离库上用整理后的包启动验证 |
| U5 ✅A（按推荐） | `.claude/` | A 加入 `.gitignore`；B 入库共享（先清除 `D:/` 绝对路径与陈旧条目）。`.codex/` 已入库，不在此选项 |
| U6 ✅0.8.0 | 目标版本、周期与日期（用户选了备选之外的 0.8.0 周期：日期 10-25 至 10-26 作废，随周期排期） | A 挂 0.7.0 周期（周期到 10-31，需另行 Plane 写入确认）；B 暂不分配版本，作为工程任务独立管理。另：FINNS-24 现为 10-25 至 10-26，在 U1、U9 未决、需人工验收的情况下，保持还是顺延 |
| U7 ✅A（按推荐） | 部署形态 | A 前端产物仍打进同一个 jar；B 要独立部署（引入跨域与会话机制变更，另立需求） |
| U8 ✅A | **仓库形态**（原要求“分别建立仓库”） | A 单仓库加 `backend/`（本草稿）；B 两个仓库（需迁移历史、拆分构建与版本同步，`pom.xml:78` 共享文件须定归属）；C 先单仓库，保留日后拆分条件（做法与 A 相同，仅声明意图） |
| U9 ✅A | 搬迁与 0.7.0 发布的先后、分支基点与命名（先发布封板已决定；分支基点与命名仍待定，默认 `codex/<task>-<purpose>`） | A 先发布 0.7.0 再搬迁；B 先搬迁再发布（发布脚本与部署手册路径须先改完并验证）。分支默认命名 `codex/<task>-<purpose>`，是否沿用由用户定 |
| U10 ✅A（按推荐） | 6 个含中文无 BOM 的 `.ps1` | A 本任务补 BOM；B 不处理，仅记录 |
| U11 ✅A（按推荐） | 提交范围（scope）约定归属 | A 在 FINNS-24 定（目录名就是范围）；B 移交 FINNS-25 与代码规范一起定 |
| U12 ✅A（用户单项确认） | `.mvn/` 是否随 pom 移入 `backend/` | A 移入（`mvn` 从 `backend/` 运行，README 启动说明改变）；B 留根目录（`-s` 路径须显式写，`maven.config` 能否被自动加载须实测）。直接改变日常 `mvn` 命令 |
| U13 ✅A（用户单项确认） | 已失配/CRLF 登记的证据哈希 | A 本任务不处理，只记录基线；B 另立工作项统一重登（逐项语义复核，不能批量刷新）。当前已知既有失配 1 项（`AI_HANDOFF.md`）；选 A 意味着该失配永久保留，整理前后只比较“不新增” |
| U14 ✅默认（用户单项确认） | 根级文件与目录归属（AI 默认） | 默认：`VERSION`、`scripts/`、`docs/`、`.env.example` 留根，`frontend/` 不改名，根 `target/` 继续作证据与前端测试、原型产物目录。备选：`.env.example`（主要是后端环境变量）随 `backend/` 走；证据目录另设位置（改动面更大）。影响日常查找产物的位置 |

## 7. 遗留项与给原型的问题

本任务无用户界面，G2 产品原型已由用户豁免（ENG-D09，适用 FINNS-24；FINNS-25/26 进入各自评审时再记录）。无需要靠原型回答的问题。留给概设与详设的条款：R03 的“入口×有效启动目录×落点”表、`.mvn` 位置、各脚本的具体修改清单、`.gitattributes` 属性的实测、Maven 对 `.mvn` 与 `workingDirectory` 的实测、无 `static/` 时 `test.ps1` 的行为（A14）、PostgreSQL 在目录移动后的启动行为、A04 副本与隔离端口的准备方法。

## 8. FINNS-25、FINNS-26 概述

两项的正文已按草稿写入 Plane（见 [同步记录](REPO_BASELINE_PLANE_P01.md)），本轮不评审。FINNS-25 依赖 FINNS-24；FINNS-26 依赖 FINNS-25 的审查规则。FINNS-26 需记录：后端依赖 `frontend/` 共享文件，缺失时构建通过而测试和运行失败；检查器写 pyc 的问题。

## 9. 评审状态

| 轮次 | 角度 | 结论 |
| --- | --- | --- |
| 第一轮（2026-10-25） | 与既有约束、现状源码的冲突 | **退回**：3 条阻断、10 条重要、7 条建议；已吸收进修订 2 |
| 第二轮（2026-10-25） | 可验收性与影响面 | **退回**：3 条阻断（A04 的 dry-run 可能触及主库；A07 基线被本草稿自身污染；R02、R03 缺验收）、9 条重要、若干建议；已吸收进修订 3 |
| 修订 4（2026-10-25） | 仅写入用户决定 U1、U3、U6、U8、U9，并把 U3 带来的 R06、A02、A08 具体化 | 未复核；R06、A02、A08 的改动处需复核 |
| 修订 5（2026-10-25） | 写入 U2=B 与其余按推荐的答复；改写 R03、A04、非目标、风险 | 已由第三轮复核 |
| 第三轮（2026-10-25，用户授权） | 修订 5 复核：6 条阻断的闭环 + U2=B 等新决定的影响 | **带条件通过，不建议此刻标 G1**：6 条阻断大体落实，但 R2-C1、R2-C3 仍不足；U2=B 带来 2 条新阻断（脚本先改会静默新建空主库；主集群并非干净停止）及若干矛盾，**已吸收进修订 6** |
| 修订 6（2026-10-25） | 新增 §2 第 20–22 条；R02、R03、R04、R06、R10 与停止条件改写；新增 R12、A13–A15；U4、U12、U13、U14 改为待单项确认 | **未复核**：用户 2026-10-25 明确不再复核（ENG-D12），并确认以修订 6 为评审后的版本（ENG-D14） |

评审者为同模型只读子代理，**独立性有限**，评审意见经我核对关键条目后采纳。G1 通过条件核对：评审记录存在，三轮发现的阻断均已在修订 3 或修订 6 处理（修订 6 未复核，用户知情接受）；用户 2026-10-25 明确确认评审后的修订号为修订 6；各 U 项均已有用户答复（U9 分支基点与命名、U1 提交分组与信息属实施期细节，不阻断需求）；目标版本已指定为 0.8.0（U6）。**G1 通过**。Plane 同步检查点尚未完成，之后才能进入下一阶段。
