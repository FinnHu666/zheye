# FINNS-24 整理仓库结构与版本控制基线：概要设计

日期：2026-10-25；设计修订：**3**（吸收第一、二轮设计评审意见）；关联需求：[需求修订 6](../../requirements/REPO_BASELINE_REQUIREMENTS.md)（G1 已通过，用户确认）、[决定记录](../../requirements/REPO_BASELINE_DECISIONS.md)；目标版本：0.8.0（0.7.0 封板后执行）；状态：**概设草稿；两轮设计评审（只读子代理，独立性有限）均为“退回”，本修订 3 已吸收，修订 3 未复核（用户授权的两轮已用完）；G3 未通过**。评审记录：[DESIGN_REVIEW_REPO_STRUCTURE.md](DESIGN_REVIEW_REPO_STRUCTURE.md)。

本文只做设计与实测，**没有改动业务源码、`pom.xml`、`scripts/`、`data/`、Git 或 Plane**。实测只用临时项目、H2 内存库、临时 PostgreSQL 集群和临时 Git 仓库，均在会话临时目录；**没有向真实 `data/` 写入、没有启动真实集群**（第一轮评审者指出，旧实验的伪造 pid 文件复用了真实 pid 文件的路径与启动时间，说明作者读过真实 pid 文件，措辞据此更正）。

## 1. 设计目标与边界

把后端收进 `backend/`，使根目录只剩项目级内容；保持运行时行为不变（需求 R10）；`data/` 迁入 `backend/data`（U2=B）；`static/` 不入库（U3=B）；补换行与字符集规则、提交约定、BOM、PostgreSQL 初始化守卫。不新增产品功能，没有用户界面，G2 已豁免（ENG-D09）。

## 2. 目标结构

```
AI-tome/
├── backend/                 ← 新增：Maven 项目根（pom.xml、src/、.mvn/）
│   ├── pom.xml  .mvn/  src/
│   ├── data/                ← 运行时数据（迁入，忽略）
│   └── target/              ← Maven 输出（忽略）
├── frontend/                ← 位置不变（含被忽略的 frontend/target、node_modules）
├── docs/  scripts/  .agents/  .codex/  .claude/（忽略）
├── VERSION  CHANGELOG.md  README.md  AGENTS.md  .env.example  DEVELOPMENT_PLAN.md
├── .gitattributes  .editorconfig        ← 新增
├── data.pre-move-<日期>/    ← 迁移后的旧数据（位置见 T6；须被忽略）
└── target/                  ← 根 target：证据、前端测试产物、原型产物、隔离集群（忽略，内容不迁移不清理）
```

前端构建输出改为 `../backend/src/main/resources/static`（`frontend/vite.config.js:40`），该目录整体忽略。`pom.xml:78` 的 `frontend/src/lib/topic-builder` 改为 `../frontend/src/lib/topic-builder`。根 `target/` 下未入库的入口脚本（如 `target/finns-19-implementation/start-verification.ps1`）**不迁移、不清理，但其中的路径就地修改**（先备份原文件，属 R02 点名项）。

## 3. 实测证据

所有实验可重跑：脚本 `target/repo-structure-g3-evidence/run_experiments.sh`，原始输出与 SHA256 清单在同目录（`MANIFEST.sha256`；该目录在已忽略的根 `target/` 下，**可能被清理，不是正式交付物**；运行于 2026-10-25 21:16，环境见 `env.txt`：Maven 3.9.6、JDK 17.0.12、Git 2.52.0.windows.1、PostgreSQL 17.11）。上一版设计引用的首次实验输出未保留，本节以重跑结果为准。

| 编号 | 问题 | 结果 | 限制 |
| --- | --- | --- | --- |
| E1 | 从根目录用 `mvn -f backend/pom.xml` 时 `.mvn/maven.config` 是否生效 | **生效，但相对的 `-s` 按当前目录解析**：从根目录运行报“settings 文件不存在”；在 `backend/` 内运行成功；显式 `-s backend/.mvn/settings.xml` 成功；`-Dfoo.bar=hello` 也被继承（测试输出里 `foo.bar=hello`，上一版误写“未测 `-D`”）。另据 Maven 3.9.6 启动器脚本（`mvn.cmd:86-111`、`mvn:141-163`，第一轮评审者核对）按 `-f` 的 pom 目录查找 `.mvn` | 临时项目，非真实 pom |
| E2 | `spring-boot:run` 与 surefire 的默认工作目录 | 从根目录用 `-f backend/pom.xml`：两者的 `user.dir` **都是 `backend/`**；`-Dspring-boot.run.workingDirectory` 可覆盖。插件元数据（`spring-boot-maven-plugin 3.3.8` 的 `plugin.xml`、`maven-surefire-plugin 3.2.5` 的 `plugin.xml`）写明默认 `${basedir}`；真实 `pom.xml` 未配置 `workingDirectory` | 极简主类，未对真实应用实跑 |
| E3 | Flyway 校验和是否对换行/BOM 敏感 | Flyway **10.20.1**（日志首行 `flyway version: 10.20.1`）对仓库当前 19 个迁移脚本（V1–V20，缺 V7，含未提交的 V19/V20）的 LF、CRLF、BOM 三份副本：`crlfDiff=0 bomDiff=0`；源脚本均无 CR、无 BOM | 只比较校验和计算，未对真实 PostgreSQL 的 `flyway_schema_history` 做 validate |
| E4-0 | 配置与 pid 格式 | 新建集群 `postgresql.conf`、`postgresql.auto.conf` 无绝对路径设置；Windows 下运行中的 `postmaster.pid` 为 8 行（PID、数据目录、启动时间、端口、空行、监听地址、空行、`ready`），**没有共享内存键** | 真实集群配置只有第三轮需求评审者读过，我未复核 |
| E4-1 | 干净停止后**改名**移动 | 启动成功，1000 行 | 空集群，数据量小 |
| E4-2 | 干净停止后**复制**（DS5 的方式）并启动 | 启动成功，1000 行 | 同上 |
| E4-3 | 杀 postmaster（残留 pid）后**复制**并启动 | 启动成功，**redo 3 行日志**，`1001｜9999` 行（含检查点后的插入）；新 `postmaster.pid` 数据目录行指向新位置；杀进程后另有 1 个 postgres 进程句柄遗留（无命令行、无端口，疑为已终止进程句柄） | redo 量仅约 3.8 KB，未测更大的 WAL |
| E4-4 | 残留 pid 文件指向存活的无关进程（explorer，用真实格式样本，数据目录行写真实路径） | **启动成功**，1000 行 | 一次；无关进程为 explorer；Windows 特有 |
| E5 | `.gitattributes` 对 LF 索引/CRLF 工作树文件的影响 | 样本：CRLF 文档、LF 文档、混合换行文档、CRLF 代码、带 BOM 的 `.ps1`、二进制；`autocrlf=input`。S1 无属性、S2 `* text=auto eol=lf`、S4 只列代码扩展名且 docs 不设属性：**`git add` 后无内容差异**；S3 `docs/** -text`：**CRLF 与混合换行文档各产生整文件 diff（2/2）**，clone 后 `docs/crlf.md` 为 CRLF。**所有无差异方案下 clone 后的 docs 都是 LF**（S1 基线即如此）；`check-attr`：S2 对 docs 显示 `text: auto`、`eol: lf`，S1/S4 显示 `unspecified` | 样本很小；**未测** `autocrlf=true` 的机器、切分支、`git revert`、`git stash` |

| E6 | `pg_controldata` 如何可靠判断“已干净关闭”；`pg_ctl status` 在残留 pid 场景的表现 | 默认语言环境下输出为 GBK 中文（本机），**按英文关键字解析会失败**（初次实验的 grep 无输出即此因）；设 `LC_ALL=C LC_MESSAGES=C` 后：崩溃遗留目录为 `Database cluster state: in production`，干净停止目录为 `shut down`，并可读 `Database system identifier`。`pg_ctl status`：残留 pid 指向存活无关进程与无 pid 文件的目录**都返回退出码 3**（无服务器），不会被残留 pid 误导 | 一次；输出见 `E6_controldata_status.log` |

附带发现：`pg_ctl start | tail` 这类管道会挂起（服务进程继承管道），脚本必须把输出重定向到文件。

## 4. 设计决定与被否决的备选

| 编号 | 决定 | 被否决的备选与原因 | 用户可感知 |
| --- | --- | --- | --- |
| DS1 | 所有 Maven 入口统一在 `backend/` 内执行（`Push-Location $backendRoot`），保留 `maven.config` 的相对 `-s` | 从根目录用 `-f backend/pom.xml`：E1 证明相对 `-s` 失败，要改绝对路径或每处显式写 `-s`，更脆弱 | 否（U12 已决定） |
| DS2 | 默认工作目录 `backend/` 即 `./data` 的基准（E2），**不改 `application*.yml` 默认值**（R10） | 写绝对或可配置路径：改默认配置，违反 R10，U2-C 未选 | 否 |
| DS3 | 换行规则不影响 Flyway（E3）。**第二轮评审建议保持需求 A13 原文**（在恢复库上用新 jar 启动并确认 Flyway 校验通过）：副本演练的恢复库本来就存在，多启动一次边际成本低，且对应 `DEPLOYMENT.md:39` 的“在另一端口启动并确认 Flyway”；不论取舍，都要把“核对 `flyway_schema_history`”写成有期望值的核对（§7：W2 基线，W5 逐项比对）。是否降级由用户决定（T4） | 降级为字节哈希核对（成本低，但无期望值时替代不了应用级启动） | 是（T4） |
| DS4 | `.gitattributes` 有三个备选（见 T3）：**A** 只列代码扩展名（`*.java *.vue *.js *.ps1 *.sql` 等设 `text eol=lf`，二进制显式 `binary`），docs 与未列类型不设属性；**B** `* text=auto eol=lf`（docs 不单列）；**C** 在 B 上加 `docs/** -text`。实测：C 破坏已登记证据（E5-S3）；A 与 B 对 docs 都无内容差异；**A 使 `check-attr` 对 docs 为 `unspecified`，字面符合 U4 与 A12**，B 不符。推荐 A | 见 T3 | **是** |
| DS5 | 数据迁移采用“**复制到临时名 → 校验和比对 → 改名为 `backend/data`**，旧 `data/` 改名保留”（E4-2、E4-3 证明复制后可启动） | 原地改名（E4-1 证明可行，瞬时、不占空间，但旧位置消失，只剩外部备份可回退）；与“旧位置保留”冲突；当前 `data/` 约 770 MB | 是（T2） |
| DS6 | `postgres.ps1` 守卫：①目标目录无 `PG_VERSION` 时**拒绝 `initdb`**，除非显式 `-InitNew`；②若根 `data/postgres`、`data.pre-move-*`、非空的 `backend/data`、或备份目录标记存在，**`-InitNew` 也拒绝**；③根 `data/postgres` 存在而 `backend/data/postgres` 不存在时报“数据尚未迁移”；④新增 `-Port`（默认 5432）与 `-DataRoot`：**带非默认 `-DataRoot` 必须显式传 `-Port` 且不得为 5432**，拒绝路径含 `pre-move` 的目录；日志与 pid 全部跟随 `-DataRoot`；⑤自动创建 `finns` 角色与库只在 `-InitNew` 下进行；⑥`stop`/`status` 在目录缺失时**退出码非 0** 并提示；⑦所有 `pg_ctl` 调用输出重定向到文件；⑧存在迁移窗口标记（§7）时拒绝启动。`start-local-main.ps1` 同样：`-ReleaseDirectory` 必须位于 `backend/data/deployments` 下；新增 `-PgPort`、`-AppPort`（默认 5432、8080，演练用）；启动前核对库内 `flyway_schema_history` 非空，空库则拒绝；`FINNS_DB_URL` 随 `-PgPort`（`:28` 原写死 5432） | 只靠文档提醒（不可接受）；单独初始化脚本、不拒绝 initdb 只在“根有集群而 backend 没有”时拒绝（比 R02 弱，见 T5） | 是（T5） |
| DS7 | `static/` 移出跟踪并整体忽略；`scripts/test.ps1` 缺 `static/index.html` 时**自动先构建前端**（`npm ci`），构建失败则带明确提示地失败 | 缺失时只报错；每次都构建 | 是（T1） |
| DS8 | 脚本改动与数据迁入属**同一个受控窗口**（§7）；窗口外不得出现“脚本已改、数据未迁”的可运行状态；DS6 守卫作为兜底 | 脚本先合并、数据后迁：窗口内任何入口都可能运行 | 否 |
| DS9 | 根 `target/` 内容不迁移不清理；脚本找 jar 只认 `backend/target/finns-<version>.jar`，找不到报错；**启动前打印 jar 的 SHA256 与修改时间**，由使用者与最新构建比对（`backend/target` 里同名旧构建同样可能过期，版本号不变） | 找不到时回退根 `target/`：会无声使用旧包（现存 10-24 的旧 jar）；仅靠版本号区分：不足 | 否 |
| DS10 | 守卫与新增提示一律用**英文/ASCII**，避免新增中文使脚本必须带 BOM；`.editorconfig` 对 `*.ps1` 要求 `utf-8-bom`，是否给 3 个纯 ASCII 脚本（`postgres.ps1`、`test-postgres.ps1`、`start-topic-builder-test.ps1`）也补 BOM 留 G4 决定 | 中文提示：增加受 BOM 约束的脚本 | 弱 |

## 5. 入口×启动目录×落点表（R03 遗留项交付）

所有脚本以 `$PSScriptRoot` 的上一级为 `$projectRoot`（不变），新增 `$backendRoot = Join-Path $projectRoot 'backend'`。

| 入口 | 现状 | 新启动目录/落点 | 需改动 |
| --- | --- | --- | --- |
| `package.ps1` | 根目录进 `frontend/` 构建，再根目录 `mvn package` | 前端仍在 `frontend/`；`mvn` 在 `backend/`；jar 在 `backend/target` | `Push-Location $backendRoot`；vite 输出落到 `backend/.../static` |
| `test.ps1` | 根目录 `mvn test` | `backend/` | 同上；加 DS7 |
| `test-postgres.ps1` | 调 `test.ps1`，只连 `finns_test_*` | 随 `test.ps1` | 无路径改动，复核其库名守卫保留 |
| `run.ps1` | 先 `postgres.ps1 start`；`frontend/` 内 `npm install` 与构建；`mvn spring-boot:run` 在 `Push-Location` 块之外 | `backend/` | `mvn` 移入 `Push-Location $backendRoot`；`npm install` 与 `package.ps1` 的 `npm ci` 不一致，已知差异，不改；**受 DS6 影响：新机器须 `-InitNew`（T5）** |
| `postgres.ps1` | `$dataRoot=<根>/data/postgres`；日志 `data/postgres-server.log`；5432 硬编码（`:44,48-59,61`） | `backend/data/postgres`、`backend/data/postgres-server.log` | 改路径；加 DS6（守卫、`-Port`、`-DataRoot`、`stop` 提示、输出重定向） |
| `start-local-main.ps1` | `$pgData=<根>/data/postgres`；jar 来自 `data/deployments/<目录>`；`Start-Process -WorkingDirectory $projectRoot`；5432 硬编码（`:19,22`） | `$pgData=backend/data/postgres`；jar 随部署目录迁入 `backend/data/deployments`；**工作目录改为 `backend/`** | 改 `$pgData`、日志、`-WorkingDirectory`；加 `-PgPort`（默认 5432）；主站启动命令随迁移同步改（受控窗口） |
| `migrate-h2.ps1` | `$sourceFile=<根>/data/aitome.mv.db`；**备份目录 `data\backups\<时间戳>`（`:14`）**；`mvn` 在根目录（`:38-40`）；只设 `FINNS_H2_SOURCE_FILE`，不设 URL | `backend/data/aitome.mv.db`；备份 `backend/data/backups/...`；`mvn` 在 `backend/` | 改三处路径与 `Push-Location`；**补设 `FINNS_H2_SOURCE_URL` 与 `FINNS_H2_SOURCE_FILE` 指向同一绝对路径** |
| `migrate-h2-dry-run.ps1` | 同上；默认连主库 `127.0.0.1:5432/finns` | 同上 | 同上；加默认库守卫（拒绝 `finns`，除非显式传入测试库） |
| `start-account-preview.ps1` | `target\finns-<ver>.jar`、`target\account-preview.pid/log`（根）；`Start-Process -WorkingDirectory $projectRoot`；脚本内提示文字写旧路径（`:10,13,55`） | jar 取 `backend/target`；pid、log 仍写根 `target`；**工作目录改为 `backend/`** | 改 jar 路径、`-WorkingDirectory`、提示文字；jar 缺失报错 |
| `start-topic-builder-test.ps1` | `target/finns-<ver>.jar`；以 `&` 调用 java（`:27`），**不是 `Start-Process`，无 `-WorkingDirectory`** | jar 取 `backend/target`；用 `Push-Location $backendRoot` 包住调用 | 改 jar 路径；不改调用方式（避免改变阻塞与退出码语义） |
| `show-dev-mail.ps1`、`lib/dev-signup.mjs`、`seed-user-admin-preview.mjs/.ps1` | 读 `<根>/data/dev-mail/outbox.jsonl`（含脚本头注释 `show-dev-mail.ps1:2`、`dev-signup.mjs:2` 的旧路径描述） | `backend/data/dev-mail/outbox.jsonl` | 改相对路径与注释 |
| `test-topic-builder-ai.mjs`、`-iteration2.mjs` | 写根 `target/`；**`import './lib/dev-signup.mjs'`（出信箱路径随之改变）** | 写根 `target/` 不动；读出信箱经 `dev-signup.mjs` 随其改 | 无直接改动，验证依赖 `dev-signup.mjs` |
| `topic-builder-fixture-server.mjs` | 未发现 `data/`、`target/` 路径引用 | 不动 | 无 |
| `data/backups`、`data/deployments`、`data/lexicons`（目录） | 除 `migrate-h2.ps1` 构造 `data\backups` 与部署命令引用 `data/deployments` 外**无代码消费者**；`data/lexicons` 是 REVIEW-D23 下载的词库，未入库，`data/` 是唯一副本 | 随 `data/` 一并复制到 `backend/data`；`lexicons` 迁移前后校验和须一致 | 无代码改动 |
| 应用本身（`application.yml:5`、`application-postgres.yml:26-27`、`AccountMailConfig.java:28`） | 相对 `./data/...` | cwd=`backend/` 即 `backend/data/...` | **不改**（DS2） |
| IDE 运行配置、`.claude/launch.json`、`target/finns-19-implementation/start-verification.ps1` | 写死根路径或根 `target/` | 工作目录/jar 路径改到 `backend/` | 逐个更新；Project Runner 等外部工具的路径是技术对接，不在本任务范围（ENG-D15） |
| 前端 `vite.config.js`、`frontend/tests`、原型 `vite.config.mjs` | 输出到 `../src/main/resources/static`；原型引用 `../../../frontend/...` | vite 输出改 `../backend/src/main/resources/static`；其余不变 | 只改 vite 一处 |
| 文档中的手工命令（`README.md:17,42,46,60`、`DEPLOYMENT.md:3,10,12,36,37,38`、`DATABASE.md`、`TECHNICAL_HISTORY.md:11,34,36-40`、`.env.example` 注释） | 含 `data/...`、`target\...`、`src/main`、`pom.xml` | 更新到新位置；历史记录不批量改 | R07、A10 |

## 6. 提交序列（实施阶段，均需用户逐次授权；分组须用户确认，见 T8）

0. （不属本任务）0.7.0 封板，U1 的“先提交”完成。
1. 纯 `git mv`：`pom.xml`、`src/`、`.mvn/` → `backend/`（只含重命名，A01 的 R100 对比）。
2. 引用同步：vite 输出、`pom.xml:78`、`scripts/`（含 DS1/DS6/DS9）、文档现行路径。
3. `.gitignore` 锚定规则（含 `/data.pre-move-*/`）；`static/` 移出跟踪（独立提交，不计入 R100；删除数量以 U1 的提交结果为准）。
4. `.gitattributes`、`.editorconfig`（DS4）。
5. 6 个 `.ps1` 补 BOM（R12）。
6. 文档与版本位置（R07）、路径对照（R08）、提交约定（R05）。
7. **非 Git 操作**：数据迁移（§7），与第 2 步合并后的脚本在同一窗口内完成。

## 7. 数据迁移受控窗口（DS5、DS6、DS8）

原则：**任何时刻新旧位置都不得同时是“可被入口启动的完整集群”**；一旦主站在新位置接受过用户写入（“不可回头点”），旧副本即不再是回退基线。脚本改动与数据迁入属同一窗口，窗口期间存在**窗口标记**（`target/migration-window.lock`，新脚本检查，存在即拒绝启动，迁移脚本除外）。除窗口外不得出现“脚本已改、数据未迁”的可运行状态。

**P0 崩溃遗留集群的处置（单列授权，T11；不属于窗口，先于一切）**：现状 `data/postgres/postmaster.pid` 为 2026-10-21 的陈旧文件。首次启动会让它执行崩溃恢复，**会改写数据目录**，所以：
1. 用户授权，且确认所有其他会话、预览实例及由外部工具（如 Project Runner）启动的进程都未在使用 `data/`；
2. **先冷拷贝**整个 `data/`（含 `data/postgres`）到备份落点（T7；`data/` 与 `backend/data` 之外），记录 `LC_ALL=C pg_controldata` 的 `cluster state`（预期 `in production`）与 `Database system identifier`，并计算校验和（E4-3 证明崩溃态的拷贝可启动）；
3. 再用旧路径的直接 `pg_ctl` 启动让其恢复，随后 `pg_ctl -m fast stop`，`LC_ALL=C pg_controldata` 确认 `shut down`；
4. 恢复失败时：**停止，禁止 `pg_resetwal`，不删除任何文件**，以冷拷贝为回退，向用户报告。

**P1 副本演练（先于真实窗口，A04）**：副本取自 P0 完成后（干净停止之后）的 `data/`，放在 `target/repo-structure-migration-rehearsal/`；PostgreSQL 用 **54398 端口**、应用用非 8080 端口（`-PgPort`、`-AppPort`、`-DataRoot`）；走改后的脚本；演练覆盖 W2–W6 全部步骤，并含“**移回再核对**”与“迁移后写入再回移”。演练副本是完整生产数据，同样按敏感数据处理并计入保留期（T7）。

**真实窗口前置（W0）**：0.7.0 已封板；用户授权并确认窗口时长；磁盘空间预检（`data/` + 备份 + 临时副本 + 恢复校验库 + 新位置启动后的 WAL 与日志增长；现 `data/` 约 770 MB，**该数字来自作者会话早期的目录大小，窗口前复核**）；建立窗口标记；确认无其他进程占用 `data/`（H2 文件锁、Project Runner 子进程、预览实例、其他会话；根 `target/account-preview.pid` 存在则核对）；预检 `data/postgres` 下无表空间链接（`pg_tblspc` 为空）、`pg_wal` 无外链、`postgresql.conf` 与 `postgresql.auto.conf` 无外部路径和 `include`；备份落点已按 T7 就绪；复制工具已钉死并验证（见 W3）；**放弃套件**已备好（见下）。

| 步骤 | 动作 | 失败情形与处置 | 此时状态 |
| --- | --- | --- | --- |
| W1 | **先停主站**（按 `DEPLOYMENT.md:49` 只停精确进程：核对 `main.pid`、8080 归属、命令行），**后** `pg_ctl -m fast stop`；`LC_ALL=C pg_controldata` 为 `shut down`、`postmaster.pid` 不存在、`pg_ctl status` 返回 3 | 进程被杀/断电：回到崩溃态，按 P0 步骤 2–4 处置；认错进程：停止并报告 | 旧位置完整，主站离线，脚本已改（受窗口标记保护） |
| W2 | **备份与基线**：用旧路径直接 `pg_ctl` 在**非 5432 端口**（如 54397）启动 → `pg_dump` 逻辑备份 → `pg_restore -l` → 恢复到 `finns_restore_check_*` 并逐表行数比对 → **记录基线**（逐表行数、`flyway_schema_history` 的版本与校验和、`Database system identifier`）→ **删除恢复校验库** → 再次 `pg_ctl -m fast stop` → 再核 `shut down`；H2 文件、`deployments`、`backups`、`lexicons` 记录校验和 | 校验不一致：停止；备份失败：停止，不进入 W3。备份落点必须在 `data/` 与 `backend/data` 之外 | 旧位置完整且干净 |
| W3 | 复制 `data/` → `backend/data.incoming`（指定工具并验证：`robocopy /E /COPY:DAT /DCOPY:DAT /R:1 /W:1`；先扫描有无重解析点/超长路径，复制后核对文件数、总字节、逐文件 SHA256） | 中断、磁盘满、杀毒或索引器占用句柄：`data.incoming` 是半成品，旧位置完整；重做前先把半成品**改名隔离**（不删除）；校验不一致先区分“源被并发写入”（有写入则视为 W0 排他失败，停止）还是“拷坏”（重拷） | 新旧各一份，**新位置名为 `data.incoming`，不是入口认得的路径**，旧位置仍是唯一可被入口启动的数据 |
| W4 | 校验通过后，**先**把旧 `data/` 改名 `data.pre-move-<日期>`，**再**把 `data.incoming` 改名为 `backend/data`；改名失败（目标已存在、句柄占用、当前目录在其中）立刻把已做的改名**反向还原**，停止 | 旧目录改名失败：停止，不继续；`incoming` 改名失败：把旧目录改回原名；改名成功后重算 `data.pre-move-*` 的校验和并扫描修改时间，发现变化说明有进程仍持句柄写入，停止并报告 | 此刻**没有任何入口能启动的数据**（旧名已不存在，新名尚未首次启动），避免分裂脑 |
| W5 | 首次启动新位置：先在 **54398 端口**启动并核对（`system identifier` 与 W2 一致，证明不是新建的空簇；逐表行数、`flyway_schema_history` 与 W2 基线逐项相同），再 `pg_ctl -m fast stop`；通过后用新脚本在 5432 启动 PG，再启动主站，核对 `/api/stats` | 启动失败或任何比对不符：把 `backend/data` 改名为 `data.failed-<日期>`（不删除）、旧目录改回 `data/`，按**放弃套件**恢复，向用户报告 | 通过后新位置才可接受写入 |
| W6 | 迁移后检查：根目录不再有 `data/`（出现说明有过期入口，须查出）；`data.pre-move-*` 校验和与 W4 一致；T13 的占位对象（若采用）已就位 | 根目录出现 `data/`：停止主站，查入口，禁止合并 | 新位置运行 |
| W7 | **窗口收尾**：撤销窗口标记；按 `DEPLOYMENT.md:40-41` 的环境变量注入与发布后核对，确认主站可访问；写迁移记录（脱敏，不写站长邮箱、路径中的用户名等敏感项）并落入 `docs/iterations/0.8.0/` 的验证记录；**完成定义**：W5–W6 全部通过且主站按新布局运行；**不可回头点**：主站在新位置首次接受用户写入 | — | 旧 `data.pre-move-*` 此后只是历史副本，不是回退基线 |

**放弃套件（W0 前备好）**：①窗口开始前导出的**未改动版**旧脚本集（`target/repo-structure-abort-kit/`），使中途放弃后仍能用旧布局启动主站；②旧 `.claude/launch.json` 条目的原值；③各步“允许动作”清单。放弃的标准动作：停止当前步骤 → 按上表“失败情形与处置”还原改名 → 用放弃套件启动 → 回读 `/api/stats` 与 W2 基线。窗口中途用户要求放弃，同此。

**迁移成功后需要回退**：顺序固定为“先回移数据，再回退代码”，回移同样先在副本演练（含“迁移后写入再回移”）；若 W7 之后主站已接受写入，回移须以新位置为准、旧副本仅作历史参考。旧脚本没有 DS6 守卫，会静默 `initdb` 建空库，是已知风险（§10、T10）；**迁移后热修复 0.7.0**路径见 T12。

**窗口前验收不得污染 `backend/data`**：A03、A08、A15 的预览启动须显式把出信箱目录指向临时目录（`finns.mail.outbox-dir`），否则 `AccountMailConfig.java:28` 的相对 `data/dev-mail` 会在迁移前创建 `backend/data`，触发停止条件 (b) 并占用 W4 的改名目标。

## 8. 遗留项交付核对

| 编号 | 原文位置与承诺 | 状态 |
| --- | --- | --- |
| L1 | 需求 §7、R03：“入口×有效启动目录×落点”表，须覆盖默认 H2、PG 库及日志、开发出信箱、H2 迁移源、`data/backups`、`data/deployments`、`data/lexicons` | 已交付于 §5（含上一轮缺项：`migrate-h2.ps1` 的备份目录与 URL/FILE、`data/lexicons`、`test-topic-builder-*.mjs` 对 `dev-signup.mjs` 的依赖、脚本提示文字与文档手工命令） |
| L2 | Maven 对 `.mvn`、`workingDirectory` 的实测 | 已交付于 §3 E1、E2（原始输出已留证） |
| L3 | `.gitattributes` 属性实测 | 已交付于 §3 E5、DS4（样本、限制已写） |
| L4 | 无 `static/` 时 `test.ps1` 的行为 | 已交付于 DS7，选择交 T1 |
| L5 | PostgreSQL 目录移动后启动行为 | 已交付于 §3 E4-1–E4-4（含复制式迁移，原始输出已留证） |
| L6 | Flyway 对换行是否敏感 | 已交付于 §3 E3（原始输出已留证） |
| L7 | （已撤销）Project Runner 条目的核对方式 | **不属本任务**：用户 2026-10-26 澄清 FINNS-24 与 Project Runner 无关，最多是技术对接（ENG-D15）；路径变化后其条目由使用者另行更新，设计只在迁移说明里提示，不作交付项、验收项或评审条件 |
| L8 | A04 副本与隔离端口的准备方法 | 已交付于 §7（P1：位置、54398 端口、非 8080 应用端口、取副本时机、回移演练、`-PgPort`/`-AppPort`/`-DataRoot`） |
| L9 | 需求评审记录 C-4：U9 分支基点与命名、U1 提交分组与信息（含 `static/` 的删除、修改与新资产是否进封板提交） | **已列为用户决定（T8）** |
| L10 | 需求评审记录 C-6：概设待实测项 | 同 L2、L3、L5、L6 |

## 9. 需用户决定（含推荐，不由 AI 默认）

| 编号 | 事项 | 备选与后果 | 推荐 |
| --- | --- | --- | --- |
| T1 | **`scripts/test.ps1` 缺前端产物时**（DS7） | A 自动先构建前端（首次慢，需 Node 与网络，用 `npm ci`）；B 报错并提示先构建（快，但新克隆首次测试必失败一次） | A，与 `package.ps1` 一致 |
| T2 | **数据迁移方式**（DS5） | A 复制+校验和+旧目录改名保留（多占约 770 MB，回退最稳，E4-2/E4-3 已验证复制后可启动）；B 原地改名（E4-1 已验证可启动，瞬时，无额外空间，但旧位置消失，回退只能靠外部备份和改名回去） | A，符合已确认的“旧位置保留” |
| T3 | **`.gitattributes` 方案**（DS4，用户已选 U4=A “docs/** 不转换”） | A 只列代码扩展名，docs 与未列类型不设属性（需随新增文件类型维护列表，`check-attr` 对 docs 为 `unspecified`，字面符合 U4/A12）；B `* text=auto eol=lf`（兜底所有文本，对 docs 无内容差异，但 `check-attr` 显示 `text: auto`，与 A12 字面不符）；C B 加 `docs/** -text`（E5-S3 实测会让 CRLF 文档产生整文件 diff，破坏证据，**不建议**） | A |
| T4 | **A13（Flyway 验收）是否降级**（DS3） | A 降为“迁移脚本字节哈希整理前后相同”，以 §7 W2/W5 的 Flyway 历史比对作替代；B 保持需求原文“在恢复库上用新 jar 启动，Flyway 校验通过”（副本演练的恢复库已存在，多启动一次） | **B**（第二轮评审建议；E3 已证明校验和与换行无关，但替代方案缺少期望值）；改变已确认验收，须你决定 |
| T5 | **`postgres.ps1` 的初始化守卫形态**（DS6） | A 加 `-InitNew` 开关，`run.ps1` 透传，新机器首次 `run.ps1 -InitNew`，README 说明；B 单独的初始化脚本；C 不拒绝 initdb，仅在“根有集群而 backend 没有”时拒绝（比 R02 弱，新机器首次启动无感，但无法阻止 `backend/data` 被意外新建） | A；C 须你明确接受比 R02 更弱 |
| T6 | **旧 `data/` 迁移后放哪**（§7 步骤 5） | A 仓库根下 `data.pre-move-<日期>/` 并加忽略规则（一眼可见，易误提交需靠规则）；B 移到仓库外（如 E: 盘某目录；不会进 `git status`，但路径脱离项目） | A，加 `/data.pre-move-*/` 忽略 |
| T7 | **备份落点、保留期与副本管理**（W2；备份含密码哈希、加密后的 AI Key、邮箱、草稿；`FINNS_AI_CREDENTIAL_SECRET` 在环境变量不在备份里，**备份与密钥不得同处**） | 须你指定：备份目录（不放 C: 盘）、访问权限、是否加密、保留多久；**至少同时存在四份完整副本**（外部备份、P0 冷拷贝、演练副本、`data.pre-move-*`）各自的保留期与删除时点；迁移记录须脱敏 | 无默认值，请你指定 |
| T8 | **U9 与 U1 的细节，以及提交分组**（§6） | 分支基点与命名（默认 `codex/<task>-<purpose>`）；“先提交”的分组与信息；`static/` 的 2 个删除、1 个修改、3 个新资产与 `vendor/` 是否进 0.7.0 封板提交；§6 步骤 1–6 的分组是否接受 | 请你指定 |
| T9 | （已撤销）原“L7 延期” | 用户 2026-10-26 澄清 Project Runner 不属本任务，该决定项不再需要 | — |
| T10 | **确认 §10 里“做不到”的项**：回退旧程序包；直接运行 `mvn` 不受保护；旧 jar 直接 `java -jar`、非默认 `-DataRoot`、IDE 与外部工具的旧条目不受守卫保护；迁移后回退代码须先回移数据；窗口中止后的状态按 §7 表；迁移中途失败的各状态；IDE 工作目录未验 | 接受 / 要求补救措施 | 接受 |
| T11 | **P0 的授权与主站离线时长**：首次启动崩溃遗留集群是对生产数据目录的写操作（崩溃恢复） | 你授权后才做；需确认窗口期间所有其他会话、预览、Project Runner 都已停；主站离线时长（P0 + 窗口，演练通过后真实窗口按实测估计） | 授权前先做冷拷贝；请你确认 |
| T12 | **迁移后 0.7.0 热修复路径** | A 在新布局上摘取提交并用新脚本部署；B 保留旧布局分支，每次先回移数据（代价大，易出错） | A |
| T13 | **根目录占位对象**：迁移后在根放一个名为 `data` 的**文件**，使旧脚本 `initdb`、默认 H2 在根目录失败而不是静默建库 | A 采用（拦住过期入口；代价是 W6“根目录无 `data/`”口径改为“无 `data/` 目录”，且须调整 `.gitignore`）；B 不采用（靠迁移后检查与 T10 接受风险） | A |

## 10. 能力边界表

| 场景（具体输入） | 结果 | 属于 | 处理 |
| --- | --- | --- | --- |
| 从根目录执行 `mvn -f backend/pom.xml test` | 报“settings 文件不存在” | 做不到（E1） | 所有脚本统一 `Push-Location backend`；README 写明在 `backend/` 内执行 |
| 在 `backend/` 内执行 `mvn test` / `spring-boot:run` | 正常，`user.dir`=`backend` | 能做到（E2） | `./data` 即 `backend/data` |
| IDE 的运行配置 | 工作目录由 IDE 决定；若 cwd=根且用默认 H2，会静默在根新建 `data/aitome` | 取决于配置（**未验**） | README 写明工作目录须设为 `backend/`；迁移后检查根目录不应出现 `data/`（§7 步骤 6） |
| 新克隆没有 `static/` 直接 `mvn test` | `AiTomeApplicationTests` 失败 | 做不到 | DS7：脚本自动构建；直接 `mvn` 不受保护，README 说明 |
| 整理后用旧脚本、旧文档里的 `data/...` 命令、外部工具（如 Project Runner）的旧条目 | 找不到数据；**旧 `postgres.ps1` 会静默建空库，默认 H2 入口会在根重建 `data/aitome`** | **做不到**（守卫只覆盖新 `postgres.ps1`） | 窗口内一并更新所有入口；迁移后检查根目录无 `data/`；回退代码前先回移数据 |
| 新机器或新克隆首次 `run.ps1` | 因 DS6 默认拒绝 initdb 而在第一步失败 | 做不到（按设计） | T5：`-InitNew`；README 与 A08 同步 |
| 数据迁移前运行新 `postgres.ps1` | 拒绝新建，报“数据尚未迁移” | 能做到（守卫实现后） | DS6 |
| Flyway 因换行改变校验和 | 校验和计算不受影响（E3，输出已留证） | 能做到；真实历史的 validate **未验** | A13（T4）；§7 步骤 4 |
| 移动运行中的 PostgreSQL 集群 | 失败或损坏（首次实验中曾误移运行中集群，日志 `pg_wal` 报错） | 做不到 | 必须先干净停止（§7 步骤 1） |
| 移动异常终止遗留的集群 | 可 redo 恢复（E4-3，仅约 3.8 KB WAL） | 能做到，**不当作备份** | 先干净停止再备份 |
| 残留 `postmaster.pid` 指向存活的无关进程 | 仍可启动（E4-4，一次，Windows） | 取决于（单次实测） | 迁移前 `pg_controldata` 与进程核对，不依赖此行为 |
| `git add` 时 `docs/**` 带 `-text` | CRLF 文档产生整文件 diff | 做不到（E5-S3） | T3 不选 C |
| `data.pre-move-*` 留在仓库根 | `git add -A` 会暂存约 770 MB | 做不到（若无忽略规则） | `.gitignore` 加 `/data.pre-move-*/`（T6） |
| 复制 `data/` 中途中断 | 半成品目录 | 做不到（直接复制到 `backend/data`）；**能做到**（先复制到 `data.incoming` 再改名） | §7 步骤 3 |
| 磁盘空间不足 | 复制失败 | 做不到 | §7 前置：空间预检 |
| 备份文件泄露 | 含密码哈希与加密后的 AI Key | 做不到（由存放位置与权限决定） | T7 |
| 回退到旧程序包运行 | 不支持 | 做不到（既有限制，`DEPLOYMENT.md`） | 只能用 Git 回退代码与外部备份回退数据 |
| 旧 jar 直接 `java -jar`（根 `target/finns-0.7.0.jar`、`data.pre-move-*/deployments/*`） | `postgres` profile 默认连 5432 主库；更老的 jar 走 H2 会在当前目录静默建库 | 做不到（脚本守卫管不到） | T10 接受；`start-local-main.ps1` 限定部署目录 |
| 非默认 `-DataRoot` 指向副本或 `data.pre-move-*` 且端口为默认 | 陈旧簇占 5432，应用连上它 | 做不到（DS6④ 强制显式端口并拒绝 `pre-move` 路径后能拦住脚本路径） | 直接 `pg_ctl` 仍无法拦，T10 |
| 移动异常终止遗留的集群 | 可恢复（E4-3，仅约 3.8 KB WAL，一次） | **取决于**（未测大 WAL；恢复本身会改写目录） | P0：先冷拷贝，恢复失败不得 `pg_resetwal` |
| P0 首次启动崩溃遗留集群 | 改写数据目录；恢复失败则只能靠冷拷贝 | 取决于 | T11 授权；P0 步骤 2 |
| 窗口中任一步被杀、断电、磁盘写满 | 见 §7 状态表 | 取决于步骤；W3 前后旧位置均完整，W4 之后以放弃套件还原 | §7 |
| 复制校验和不一致 | 区分“源被并发写入”与“拷坏” | 能做到（流程）；并发写入视为 W0 排他失败 | §7 W3 |
| 窗口期间另有会话或预览实例写 `data/` | 数据不一致或双写 | 做不到（排他靠窗口标记与用户确认，不是强制） | W0 确认 + 标记；W4 后复核旧副本 |
| 用 `pg_controldata` 判断是否干净关闭 | 默认语言环境输出为 GBK 中文，英文关键字解析失败 | 做不到（直接解析）；**能做到**（`LC_ALL=C`，E6） | §7 一律 `LC_ALL=C` |
| 迁移后用 `-InitNew` | 建空簇和空库，主站带空库启动，Flyway 从 V1 建表，表面“能用” | 做不到（DS6② 拦住脚本路径；`start-local-main.ps1` 的空库检查兜底） | T5 |
| 含中文 `.ps1` 无 BOM、Windows PowerShell 5.1 | 可能乱码 | 做不到 | R12 补 BOM；DS10 新增提示用 ASCII；A09 验证 |

## 11. 需求与验收映射

R01→§2、§6 步 1；R02→§5；R03→DS1、DS2、DS5–DS9、§5、§7；R04→DS4、DS3；R05→§6 步 6；R06→DS7、§6 步 3；R07、R08→§6 步 6；R09→§6 步 3；R10→DS2、§6（只重命名与引用）；R11→§7 与需求停止条件；R12→§6 步 5、DS10。A01–A15 的具体命令与判据留 G4 详设。

## 12. 风险与未验项

- 未对真实应用在 `backend/` 下实跑（E2 用极简主类，由插件元数据补强）；未实测 IDE 工作目录；未在 `autocrlf=true` 机器上测 E5；E5 未测切分支、`git revert`、`git stash`；E4 的 redo 量小、E4-4 只测了 explorer 一种无关进程；E3 未对真实 PostgreSQL 的 Flyway 历史做 validate；真实集群配置文件与表空间链接未由我复核。
- 实验证据在已忽略的 `target/` 下，可能被清理，不是正式交付物（见 §3）。
- 本设计尚未经过第二轮设计评审。
