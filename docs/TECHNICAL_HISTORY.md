# FINNS 技术栈历史、架构思路与接手指南

记录日期：2026-10-04

本文整理 FINNS 目前实际使用的技术、方案演进和选择边界，供项目负责人回顾，也供新 AI 在开始设计或编码前快速建立正确的技术上下文。本文不是未来路线的批准书，也不替代当前源码、版本文件、需求状态或最新交接。

## 1. 证据范围与阅读次序

本次基于以下材料整理：

- 当前源码与配置：根目录 [pom.xml](../pom.xml)、[VERSION](../VERSION)、[frontend/package.json](../frontend/package.json)、Spring 配置、前后端入口、安全配置、Flyway 脚本及运行脚本。
- 项目记录：[CHANGELOG.md](../CHANGELOG.md)、[NEXT_ITERATION_PLAN.md](NEXT_ITERATION_PLAN.md)、[AI_HANDOFF.md](AI_HANDOFF.md)、[DATABASE.md](DATABASE.md)、[DEVELOPMENT_REFERENCES.md](DEVELOPMENT_REFERENCES.md)。
- 历史候选方案：[DEVELOPMENT_PLAN.md](../DEVELOPMENT_PLAN.md)。该文件已注明包含被替代或未实施设想。
- Git：截至本次整理，能看到的提交只有 2026-10-03 的初始源码快照和 GitHub 发布说明；功能演进的细节主要来自 CHANGELOG 与方案/交接记录，不应误称为有完整的逐功能提交历史。

本次没有启动应用、连接数据库、做运行态检查或重跑测试。因此下文的源码与配置描述可由当前文件定位；部署和测试结果属于项目文档中记录的历史证据，按“历史记录”表达，不代表本次复验。开始具体任务时，仍须检查当前 Git 状态，避免覆盖既有改动。

建议新 AI 按以下顺序判断信息：

1. 当前业务行为与技术事实：实际源码、配置和迁移文件。
2. 当前产品版本：VERSION、pom.xml、frontend/package.json 与 lock 文件。
3. 已发布或未发布变更：CHANGELOG.md。
4. 需求是否待做、暂缓或完成：NEXT_ITERATION_PLAN.md 中对应需求及验收记录。
5. 当前运行实例、未完成工作及下一步：AI_HANDOFF.md 最新一节。
6. 长期技术背景与旧方案：本文及 DEVELOPMENT_PLAN.md。较早计划不能覆盖当前实现。

## 2. 当前实际技术基线

2026-10-09 原型决策补充：REQ-014 的平台及官方组件继续使用 Vue 3；为支持作者粘贴代码快速添加时间轴等视觉块，独立原型采用静态 HTML + CSS、隔离 iframe、原文/规则版本/布局一起保存。对比任意 Vue 文件导入，该方案无需开放用户脚本编译与依赖；对比原 Markdown 指令建议，可直接表达新视觉样式，但不自动提供逐项表单编辑或业务交互。Markdown/Mermaid 和受控 Vue 插件仍为后续候选。仅 `docs/prototypes/topic-builder/` 已实现，生产编辑器、后端、数据库及技术栈未改变；详见 [自定义模块设计](TOPIC_BLOCK_LANGUAGE_DESIGN.md)。

| 层次 | 当前基线 | 依据与边界 |
| --- | --- | --- |
| 产品与版本 | 产品名 FINNS，本地版本 0.5.1；Maven 与 npm 包版本应同步 | VERSION、pom.xml、frontend/package.json；未远程发布 |
| 后端 | Java 17、Spring Boot 3.3.8、Spring MVC、Spring Data JPA、Bean Validation | pom.xml 与 src/main/java |
| 前端 | Vue 3.5、Vue Router 4、Vite 6，JavaScript ES modules | frontend/package.json、frontend/src/main.js |
| 应用形态 | 单个 Spring Boot 应用提供 REST API 并托管 Vue 构建静态文件；Vue Router 使用 history 路由 | vite.config.js 将产物构建到 src/main/resources/static；SpaController 为已知页面路由提供 SPA 回退 |
| 持久化 | PostgreSQL profile 使用 PostgreSQL JDBC、JPA、Flyway 10.20.1；H2 配置仍保留作默认配置/测试路径 | application-postgres.yml、application.yml、pom.xml、scripts/run.ps1 |
| 数据结构 | Flyway V1–V8 迁移文件在仓库中；PostgreSQL 使用 ddl-auto=validate，H2 默认配置使用 ddl-auto=update 且关闭 Flyway | src/main/resources/db/migration 与两份 application 配置；真实数据库当前状态以 AI_HANDOFF 最新交接为准 |
| 登录与请求保护 | 同源 HttpSession Cookie、BCrypt 密码哈希、Spring Security CSRF Cookie-to-header；私有资源由服务端会话身份和归属校验保护 | SecurityConfig、AuthController、相关 Controller 与前端 api.js |
| 测试 | Spring Boot Test/JUnit、隔离 PostgreSQL PowerShell 测试脚本、Node 内建测试 | pom.xml、scripts/test.ps1、scripts/test-postgres.ps1、frontend/package.json |
| 本地开发 | Windows PowerShell；run.ps1 固定 Java 17、启动本机 PostgreSQL、构建 Vue 后启动 postgres profile；服务限制绑定 127.0.0.1 | scripts/run.ps1、scripts/postgres.ps1、docs/DATABASE.md |
| 外部协作 | Plane Cloud 官方远程 MCP 项目配置与工作流刚进入本地接轨阶段 | .codex/config.toml、docs/PLANE_WORKFLOW.md；OAuth 登录和远程端到端操作在最近记录中仍未验收，不视为已可用能力 |

模块边界目前是 Spring Boot 模块化单体中的 Java package，例如 content、user、vps、stats、tools、config。它不是微服务系统。前端主要页面独立放在 frontend/src/pages，路由集中于 frontend/src/main.js。

### 一个需要保留的配置区别

- 直接使用未指定 profile 的 Spring 配置时，application.yml 指向本地 H2 文件，Hibernate 使用 update，Flyway 关闭。
- 项目约定的开发入口 scripts/run.ps1 会启动 PostgreSQL，并显式选择 postgres profile。docs/DATABASE.md 描述的日常开发流程也是 PostgreSQL。
- 因此“默认配置”要说明调用入口。本文不把 H2 删除，也不把它说成 scripts/run.ps1 的实际数据库。
- README.md 原有概述曾把开发默认数据描述成 H2，并把 PostgreSQL、Flyway、Redis、对象存储一并放到未来生产切换语境。这与 run.ps1 和 DATABASE.md 的当前开发流程不一致。应以具体入口脚本和 profile 为准；Redis、对象存储仍是历史路线设想，不是当前服务依赖。

## 3. 技术演进时间线

### 初始规划：容量导向的候选架构

DEVELOPMENT_PLAN.md 最初提出 Next.js/React/TypeScript、Java 21、PostgreSQL、Redis、OpenSearch、S3 对象存储、可观测性组件、消息队列和容器化部署，也提出较高的早期容量目标。

这些内容表达的是早期候选方案和目标，不是实现承诺。该文件顶部已声明 Next.js 与 Java 21 等方案被后续选择替代。当前 pom.xml、前端依赖及部署脚本是判断实现的依据；Redis、OpenSearch、对象存储、消息队列、Kubernetes 和 SSR 在当前依赖与应用形态中没有落地。

### 0.3.0：统一 FINNS 产品标识与开发基线

2026-09-28 的 CHANGELOG 记录产品名、前端包名、Spring 应用名和 Maven artifact 统一为 FINNS，统一版本文件，并将打包固定在 Java 17。包名仍是 com.aitome，artifact 仍是 finns；品牌统一没有顺带迁移 Java 包名。

### 0.4.0：从 H2 开发数据走向 PostgreSQL 与可审计迁移

同日的版本记录引入本地 PostgreSQL 17、Flyway V1–V3 和 H2 到 PostgreSQL 的迁移脚本。方案强调先备份和校验、迁移可预检、事务执行、批次可识别、重复执行安全、保留原 H2 文件并通过隔离库验证。Flyway 10.20.1 是为 PostgreSQL 17 支持范围而选择，并曾在隔离 PostgreSQL 17.11 环境验证。

这个阶段也形成重要边界：数据库结构以追加迁移为准；PostgreSQL profile 使用 Flyway 和 Hibernate validate；测试及恢复演练只允许使用明确命名的 loopback 隔离数据库；不能为绕过迁移错误直接清库或覆盖主库。细节见 DATABASE.md 和 scripts/test-postgres.ps1。

### 0.5.0：从简单专题到可编辑工作稿与发布快照

2026-09-30 至 2026-10-03 的记录显示，专题成为内容组织工作台后，单一专题表不足以表达章节、条目、私人编辑、发布和归档。演进追加 V4–V8：列表排序索引、结构化专题章节/条目、发布快照与幂等导入记录、允许未完成工作稿。公开内容使用已发布版本，编辑中的私人草稿不进入公共目录；并发保存使用 revision/乐观锁思路，重复导入依赖幂等标识。

导入入口先解析和预览，再允许创建或追加工作稿；支持粘贴、TXT/Markdown、模板或复制本人专题。实现范围仍是本地产品能力，没有表明已有 AI 自动整理、任意复杂格式解析或模板导出。NEXT_ITERATION_PLAN.md 与 CHANGELOG.md 将这些列为后续而非已完成功能。

同阶段，列表逐步采用稳定分页、最大 page size、排序索引和仅返回卡片所需字段的投影，避免全量载入长内容。容量级验收 REQ-008 仍暂缓；本机小数据集串行冒烟结果不能当成并发容量或生产 SLA。

### 0.5.1：围绕真实创建摩擦收敛交互

2026-10-03 的修复沿用 REQ-012，针对入口、首屏密度、首段内容、可选标题、草稿提示和导入预览一致性做小范围交互调整；没有新增数据库迁移。历史文档记有构建、隔离库测试和桌面/移动浏览器验证，但真人可用性尚待试用。该过程体现出先依据实际操作摩擦修改，而不是为原始容量计划一次性引入更多系统。

### 2026-10-04：需求管理外置，Plane 接轨未完成远程验收

需求收集方案从 FINNS 内置功能转向独立现成工具、个人使用、长期远程。当前工作树包含 Plane Cloud 官方 MCP 配置和本地工作流说明；管理需求留在 Plane，技术方案、源码变更和验收证据留在 FINNS 仓库。配置文件不含 OAuth token，写操作需要批准。最近交接记录显示 OAuth 登录尚停在用户授权步骤，不能把远程工具连接或数据闭环写成已验收。

## 4. 关键技术选择及其来由

### 模块化单体，而非早期拆分计划

实际项目由 Spring Boot 单体提供 REST 与 SPA 静态资源，域以 package 和前端 route/page 划分。当前源码没有 Redis、搜索集群、消息队列或多个服务部署单元。继续采用模块化单体能沿用现有运行、数据和测试路径，也减少尚无测量依据的运维组件。若未来要拆分，应由独立部署、团队边界、吞吐或故障隔离等具体需求驱动，并先测量。

### Vue SPA，而非 Next.js/SSR

实际前端是 Vue、Vue Router 与 Vite，生产构建物由 Spring Boot 同源托管；既有路由要求支持直达和刷新，所以 Java 侧有 SPA fallback。Next.js 是早期计划中的替代方案，当前没有 Next.js、React 或 SSR 运行链路。恢复 SSR 或切换框架应先确认 SEO、首屏或部署边界确有需要，再比较迁移成本和可观察收益。

### PostgreSQL 作为项目日常开发目标，H2 保留作轻量配置/测试

运行脚本显式选 PostgreSQL profile，Flyway 管结构，Hibernate 只校验实体与迁移结果；这样本地验收更接近目标数据库，能验证 SQL、约束、分页和恢复流程。H2 配置仍在仓库，适合当前已有的轻量测试路径，但 H2 通过不等于 PostgreSQL 行为验收。改数据库逻辑后应使用受保护的隔离 PostgreSQL 脚本。

### 同源会话与显式数据归属校验

目前浏览器以 HttpSession Cookie 调用同源 API；Spring Security 提供 CSRF filter，而控制器通过会话中 USER_ID 确定用户并做资源归属判断。前端路由守卫是体验层，不是权限边界。SecurityConfig 的 authorizeHttpRequests 当前 permitAll，因此不能推断已建立基于角色的统一 Spring Security 授权策略；增加私有端点必须审查服务端认证和资源所有权，不能信任客户端 ownerId。应用采用 Cookie-to-header CSRF 与登录后的会话 ID 轮换，写请求不能通过关闭 CSRF 来“修复”。

### 发布快照保护公共内容，工作稿承载未完成编辑

专题编辑复杂度增加后，工作稿与公开内容被分离。历史发布版本和幂等导入记录使公开内容不被半成品覆盖，工作稿可保存未完成条目；revision 降低并发覆盖风险。后来扩展导入能力时，应沿用同一生命周期，核对是否操作工作稿还是已发布快照，不要把公开内容直接绑定到正在编辑的数据。

### 先用索引和分页处理可见的列表成本

当前路线是稳定分页、最大页大小、数据库排序索引和卡片投影，并保留详情深链接。更重的 Redis 缓存、OpenSearch、异步队列和分布式计数属于 DEVELOPMENT_PLAN.md 的方案候选；在还没有代表性数据、并发压测、慢查询或运营要求前，不应当成默认技术债立即引入。

### 将需求管理和应用功能分开

Plane 是外部项目协作选择，不是 FINNS 的新业务模块。工作项状态不能替代本地需求定义、实现证据和验收记录；远程服务未完成 OAuth 和工作项闭环之前，状态必须保持待验证。

## 5. 后续 AI 的技术决策检查表

1. 先读取 AGENTS.md、项目 skill、VERSION、CHANGELOG 和当前任务对应的 NEXT_ITERATION_PLAN / AI_HANDOFF，再核对源码与 Git 状态。
2. 对依赖、路由、持久化或身份边界的决策，写清用户需求、源码现状、替代方案、取舍、非目标和验收条件。只引用当前真正存在的能力。
3. 不要把 DEVELOPMENT_PLAN.md 的 Next.js、Java 21、Redis、OpenSearch、对象存储、CDN、消息队列或 Kubernetes 当作批准采用的技术栈。
4. 数据库改动先确认 profile、迁移版本、目标库和回退；Flyway 已执行迁移只追加，不改写。用户真实数据先备份并在隔离库演练。
5. 私人数据接口在服务端以会话确定用户，并验证资源归属；浏览器路由、隐藏按钮和请求参数均不能作为授权。
6. 新一级页面对应独立 Vue Router 路由和页面组件；验证 SPA fallback、直达、刷新、前进后退和移动视口。
7. 先用稳定分页、投影、索引和查询证据解决列表问题。只有测量或明确产品需求支持时，再评估缓存、搜索集群或异步设施。
8. 保持源码版本、Maven/npm/lock 版本、CHANGELOG 和交接一致。区分源码核验、文档报告、推断和未验证事实。
9. Plane 或其他外部工具的凭据保留在授权客户端本地；本地配置存在不表示 OAuth 已完成或远程功能已验收。

## 6. 仍需留意或重新确认的事项

- README.md 与实际入口对数据库“默认值”的描述需要按调用方式理解；本文已记录直接 Spring 默认 H2 与项目 run.ps1 启动 PostgreSQL profile 的区别。
- V1–V9 迁移文件在源码中（V9 本轮仅应用隔离库，未更新主库）；docs/DATABASE.md 记录本地开发库及隔离库曾应用这些版本。本次没有连接数据库核实实际运行库，操作前看最新 AI_HANDOFF。
- docs/AI_HANDOFF.md 与 CHANGELOG.md 当前包含 Plane MCP 的未完成状态；其他电脑、其他 AI 客户端需各自确认工具接入，不可复用或复制本机令牌。
- 需求记录提到真人创建体验、后续导出/复杂文件/AI 整理，以及并发容量基准仍有待确认或暂缓；先检查需求状态，不把候选路线排成开发事实。
- 本文沉淀时仓库已有未提交修改和未跟踪文件。此次只新增本文及入口/文档索引记录；既有工作区内容应由后续操作者逐项审阅，不应被清理或覆盖。


## 7. 2026-10-09：AI 专题私人草稿的实现决定

按用户确认的开发方案，沿用 Java / Vue / PostgreSQL，直接调用 DeepSeek Chat Completions，不引入 Agent 框架。首轮是确认输入后的一次结构化生成，没有需要自主工具规划的执行链；模型输出仍需业务校验，不能把 JSON 合法等同于内容合法。模型只生成普通模块，作者代码独立导入。

V9 增加编辑器类型与版本化页面文档，复用原专题归属、DRAFT、revision 和幂等操作机制。旧记录默认 OUTLINE，新 BUILDER 首轮不发布、不自动转换旧专题。整页文档比拆分多个内容表更适合快速迭代，保留 schemaVersion 为后续演进提供明确边界。

桌面横向位置/宽度用百分比，纵向位置/高度用像素；阅读顺序独立保存。手机不强行压缩桌面构图，而按阅读顺序堆叠。HTML + CSS 用原型规则的共同 JSON 清单、固定 jsoup 1.23.2 服务端复验和空 sandbox/CSP iframe；首轮不执行用户脚本或外部依赖。

技术测试在独立 PostgreSQL 55439 与应用 8082 完成，V9 首次迁移及重复启动通过；主站与主库未更新。随后用户授权配置密钥，真实 DeepSeek 三例共6次调用、内容核对和保存恢复通过；固定模型证据与真实证据分别记录。实际证据与剩余事项见 [验证记录](TOPIC_BUILDER_VALIDATION.md)。


### 2026-10-10：个人凭据与模块快照复用

用户明确选择每人使用自己的 DeepSeek Key，不使用服务端共享 Key 回退。V10 单独保存 AES-256-GCM 凭据及本人模块收藏；随机 nonce，账号/provider AAD 防止密文跨账号交换。主密钥由独立环境配置提供，缺失时明确不可用，不降级明文。

模块复用保持现有四类内容及 HTML 隔离规则，插入时新建 ID 和当前布局；收藏保存独立快照。市场只展示示例，未加入共享或发布权限。编辑后核验提示作为可选文档字段，保留原始模型提示并兼容旧稿。未升级框架、未引入 Agent 或额外第三方依赖。
