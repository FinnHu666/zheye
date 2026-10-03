# FINNS 下一阶段需求与实施方案

记录日期：2026-09-29；最近更新：2026-10-03。FINNS-REQ-001–007、009–011 已实施；REQ-012 第一阶段及 0.5.1 创建体验修复已实施，技术验证通过、真人易用性待验证；REQ-008 暂缓。当前本地版本 0.5.1，已部署到本机 8080，未远程发布。最新 REQ-012 验证见第 19 节，运行边界以 AI_HANDOFF 最新记录为准。

## 1. 需求登记

2026-10-03 REQ-012 创建体验修复（已实施，0.5.1）：用户反馈创建专题不友好。本轮沿用 REQ-012，修复创作入口缺少专题、创建表单被大幅说明挤出首屏、空章节操作堆积、正文仍强制小标题、发布提示难定位及导入预览与原文不同步。采用紧凑创建页、可选首段内容原子创建、章节折叠、可选笔记标题、就地发布缺项提示与导入状态校验。保持既有归属/快照/保存协议，无新数据库迁移；已验收入口直达、名称/首段创建、缺项定位、导入新旧内容一致及桌面/390px。真实用户可用性仍待试用。

| ID | 用户要求 | 本轮形成的执行定义 | 状态 |
| --- | --- | --- | --- |
| FINNS-REQ-001 | 连接真实数据库 | 从现有 H2 文件库迁移到独立 PostgreSQL 服务，连接外置、迁移可追踪、旧数据可恢复 | 验收通过；隔离恢复、行数比对、应用读取和 PostgreSQL 写入测试证据见第 9 节 |
| FINNS-REQ-002 | 参考截图 | 顶部 VPS 指南入口改成自选专题；已有专题广场保留 | 验收通过；截图对应入口与路由见第 9、12 节 |
| FINNS-REQ-003 | 新增个人页面 | 登录后的个人中心，资料、我的内容、我的专题、自选专题入口 | 验收通过；私有 /me 页面及账号隔离证据见第 9 节 |
| FINNS-REQ-004 | 开发始终规范且能交给下一 AI | AGENTS.md + 项目 skill + 本方案 + 交接 + 版本记录 | 验收通过；2026-09-29 补充统一状态与交接协议，Skill 格式校验通过 |
| FINNS-REQ-005 | 讨论列表有界读取 | 单篇文章评论支持稳定分页，默认每页 20、最大 50；同步更新 Vue 加载更多交互与 PG 行为测试 | 验收通过；H2/PG 测试及 55 条评论浏览器交互证据见第 10 节 |
| FINNS-REQ-006 | 首页社区数字真实 | 首页创作者、内容和专题计数从数据库聚合，不显示硬编码伪统计 | 验收通过；接口/数据库对照测试和主库页面证据见第 11 节 |
| FINNS-REQ-007 | 公开内容及专题目录不能被固定条数截断 | `/api/posts` 与 `/api/collections` 统一稳定分页；更新发现页、专题广场和自选专题目录消费者 | 验收通过；H2、隔离 PostgreSQL、桌面/移动浏览器证据见第 12 节 |
| FINNS-REQ-008 | 验证正式性能需求 | 明确目标机器、数据规模、并发模型、请求分布及 SLA 后设计可复现负载测试 | 暂缓：用户回复“先不处理这个内容”；不开展负载测试、不再追问，详见第 12 节 |
| FINNS-REQ-009 | 降低公开文章列表响应体 | 列表仅返回卡片所需摘要字段；点击或直达文章时再获取完整正文 | 验收通过；H2/隔离 PostgreSQL 与浏览器交互证据见第 13 节 |
| FINNS-REQ-010 | 专题内容支持继续浏览并减少列表正文传输 | 专题详情内容按页读取；文章列表使用数据库投影，只取卡片字段；详情仍按需加载 | 验收通过；H2/隔离 PostgreSQL/浏览器交互通过；2026-09-30 启动时主库按默认配置应用 V4–V5，仅增加索引 |
| FINNS-REQ-011 | 方便制作租房步骤、动漫阅读路线等结构化专题 | 将专题升级为模板化、有章节和有序条目、可保存草稿与预览发布的专题工作台 | 验收通过（2026-10-02）；详见第 16 节 |
| FINNS-REQ-012 | 不懂编程也能快速导入经验方案、创建自定义专题 | 新建/追加导入预览、场景模板、本人专题复制、私人预览及保存/发布全入口隔离 | 第一阶段与创建体验修复已实施；技术验证通过，真人易用性待验证。详见第 17、19 节及 `docs/TOPIC_IMPORT_PLAN.md` 第 12 节 |

“真实数据库”目前指本机 PostgreSQL 17.11 开发实例，不代表已连接远程生产数据库。若用户指的是托管/生产数据库，需另行提供目标环境并确认迁移与凭据管理方案；不得由下一位 AI 猜测连接地址或搜集凭据。

截图来源：用户附件 codex-clipboard-64dd7729-cdf3-47a3-a14c-28d8cc3d9822.png。本方案已将必要信息转为文字，不依赖下一 AI 访问临时图片路径。红框定位顶部 VPS 指南区域，红字为“改为自选专题”。保留 FINNS、发现、专题、搜索、账号和开始创作的布局及绿色视觉风格。

“自选专题”暂定义为用户从专题广场选取关注的专题，可取消并排序，汇总到专属页面；不是管理员配置公共导航。VPS 保留 /vps 地址与内容，在资源入口提供访问。个人页面暂为私有 /me，不新增公开个人主页。实施前重述这两个默认解释，若用户纠正则只调整相关任务。

## 2. 实施前基线快照与风险（2026-09-28）

- verified：pom.xml 为 Java 17、Spring Boot 3.3.8；frontend 是 Vue 3 + Vite + Vue Router；VERSION 为 0.3.0。
- verified：application.yml 连接 jdbc:h2:file:./data/aitome，使用 ddl-auto=update。H2 也是实际数据库，当前缺少的是独立数据库服务与受控迁移。
- verified：路由只有 /、/collections、/vps、/private/tools。SpaController 明确枚举深链接回退，新增路由必须同步。
- verified：UserAccount 有 id、email、passwordHash、displayName、createdAt；认证使用 HttpSession。
- verified：CommunityCollection 只有 ownerName 和存储的 itemCount，没有 ownerId 或内容关联表。不能直接支持可靠的“我的专题”和真实数量。
- verified：专题接口只取前 8 条，文章接口只取前 30 条；专题页面宣称最近更新但后端按创建时间排列。新接口需要明确分页与排序。
- verified：DemoData 在空用户库写入演示账号和样例内容，真实环境必须明确关闭。
- verified：只有 spring-security-crypto，未看到 Spring Security Web 过滤链；不能假设已有完整 CSRF/接口授权保护。
- verified：Docker CLI 存在但 Engine 未运行，未发现本机 PostgreSQL/psql 服务；用户要求缺少工具时不要在 C 盘安装。
- verified：本地工具接口用独立密钥，与普通账号隔离。
- verified：package.ps1 固定 JDK 17，但不构建 Vue；run.ps1 会构建 Vue。脚本缺少显式原生命令退出码检查。
- verified：当前目录未初始化 Git。DEVELOPMENT_PLAN.md 仍含 Java 21/Next.js 等历史设想，不能作为当前重建指令。
- reported：上一轮前端构建、5 项测试、打包和登录会话检查成功；本轮未重跑业务验证。
- unverified：PostgreSQL/Docker 是否已安装，目标地址与凭据、真实数据规模、生产部署和可用性要求。

## 3. 架构决定

沿用模块化单体 Java + Vue。数据库推荐 PostgreSQL；与既有规划一致，避免引入第二种生产数据库。具体 PostgreSQL 版本在实施时核对 Spring Boot 所管理的 JDBC/Flyway 兼容性，固定镜像版本或安装版本，不能使用浮动 latest。此轮不顺带升级 Spring Boot/Java/Vue，也不新增 Redis、搜索集群或微服务。

数据库结构使用 Flyway，应用配置 ddl-auto=validate；迁移脚本在 src/main/resources/db/migration。Boot 3.3 系列使用受其依赖管理的 flyway-core、flyway-database-postgresql 和 PostgreSQL JDBC 驱动，不能照新版本文档直接复制 starter 名称。依赖版本以实际 Maven 解析为准。

保留服务端 Session 身份机制。第一期接受服务重启后重新登录；多实例会话共享另列需求。前端路由守卫只改善体验，所有私有接口仍须后端认证和归属校验。

## 4. FINNS-REQ-001：数据库落地

### 环境与配置

优先复用用户明确提供的 PostgreSQL 实例；否则为开发环境准备本地 PostgreSQL Compose 方案，先检查 Docker 可用性，也提供外置实例方式。不要扫描私人配置搜集凭据。

提供 application-postgres.yml、无真实密码的 .env.example 和连接说明。建议变量 FINNS_DB_URL、FINNS_DB_USERNAME、FINNS_DB_PASSWORD；区分开发/生产配置，密码不进源码/日志。Spring Boot 不会自动加载任意 .env 文件：Compose 用 env_file，PowerShell 启动说明用进程环境变量。接入脚本显式启用 postgres profile。

保留旧 H2 的路径和回退配置。PostgreSQL 不可用时应清晰启动失败，不静默换回 H2。H2 可留为特定测试依赖，但数据库集成验收必须运行 PostgreSQL。

### 结构与迁移顺序

1. 检查所有实体与源库实际结构、行数、字段长度和空值；形成迁移映射与冲突清单。
2. 新空 PostgreSQL 上用 V1 建立现有领域表，保留主键和密码哈希语义；避免直接把 H2 SQL dump 当作 PostgreSQL SQL 执行。
3. 后续版本迁移新增个人资料、专题 owner_id、收藏关系及内容关联，分别记录。建立 user_id/collection_id/post_id 外键及联合唯一约束。
4. emails 做一致化校验与大小写冲突排查后再加唯一约束；不自动覆盖冲突用户。
5. ownerName 不能可靠确定 ownerId。唯一且核实的映射才回填；未解决记录保留 nullable owner_id 并登记，暂不可作为任何人的可编辑专题，不分配给首个账号。
6. 新专题强制由服务端会话写 owner_id。旧 itemCount 不是实际关联数据，不合成虚假内容关系；真实数量从关联表派生，历史未知数显示未知或 0 并说明。
7. 对导入后的 identity/sequence 调整到现有最大 ID 之后；验证新建记录不冲突。
8. Flyway 已执行迁移不可改写；后续修订追加版本。不要自动 baseline、clean 或删除真实表绕过错误。

### 数据保留与切换

先保存 H2 一致备份：受控停写/停服务复制数据库文件，或使用数据库支持的在线备份；不要直接拷贝正在写入的文件并宣称可恢复。记录文件校验值与恢复演练结果。

在隔离 PostgreSQL 演练导入：用户 → 文章/专题 → 评论/资源 → 新关联；最终顺序按实际外键拓扑确定。保留 ID、时间、哈希；逐表行数、关联完整性和抽样内容校验。迁移工具要有 dry-run、单次批次标识和重复运行保护。

切换前停写、做最终备份和差异校验，再切连接。回退前明确切换后新写入如何处理：无新写入可回旧库，有新写入需反向同步或人工合并，不能直接丢弃。为 PostgreSQL 提供 pg_dump/pg_restore 的备份恢复说明并完成隔离恢复演练。

验收：PostgreSQL 空库首次启动成功、重复启动不重复初始化、迁移历史可查询；注册/登录/发布/评论/专题在重启后可读；旧账号可登录；无明文密码/默认生产演示账号；缺少连接配置报错清晰；迁移失败可按演练方案恢复。

## 5. FINNS-REQ-002：自选专题

独立页面 /my/collections；公共专题广场 /collections 保留。头部原 VPS 入口替换为“自选专题”，点击后进入专属路由；未登录触发登录并在成功后回到该路由。/vps 保持直接可达，并在资源入口可发现。

专题广场卡片增加选入/取消；专属页面显示已选专题、真实内容数、最近更新，支持调整顺序、取消和空态引导。首期用上下移按钮即可，不强制引入拖拽库。保存后刷新及另一浏览器登录同账号结果一致。不同账号互不串数据。

推荐表 user_collection_selections：user_id、collection_id、sort_order、created_at；主键 (user_id, collection_id)，外键关联用户/专题。关联表 collection_posts：(collection_id, post_id)、created_at；允许管理者将自己的已有内容加入专题，服务端验证专题与内容归属。更新排序使用事务，整批校验不能包含他人收藏；版本号或 updatedAt 条件防止并发覆盖。

| 接口草案 | 用途与边界 |
| --- | --- |
| GET /api/me/collections/selected?page=0&size=20 | 当前用户已选列表，稳定按 sort_order/id 排序 |
| PUT /api/me/collections/selected/{collectionId} | 幂等选入，不接收 userId |
| DELETE /api/me/collections/selected/{collectionId} | 幂等取消 |
| PATCH /api/me/collections/selected/order | collectionIds + version，事务保存本用户完整顺序；冲突返回 409 |
| GET /api/collections?page=0&size=20 | 升级分页响应时同步现有 Vue 页面 |
| POST /api/collections/{id}/posts/{postId} | 归属校验后收录，重复请求不重复计数 |
| DELETE /api/collections/{id}/posts/{postId} | 仅管理者可移除关联，不删除原文 |

统一分页返回 items、page、size、total；限制 size 最大 50。未登录 401、越权 403（或统一隐藏资源存在性的 404）、不存在 404、版本冲突 409。决定后固定到接口文档和测试。

验收：截图区域文案与链接改变；不是只改名称仍指向 VPS；选取/取消/排序后刷新保留；公共专题和 VPS 可用；移动端导航能访问这些页面；直达与刷新不 404；加载、失败重试和空态均可操作。

## 6. FINNS-REQ-003：个人中心

范围：私有 /me 概览、/me/profile 编辑资料、/me/posts 我的文章话题、/me/collections 我的专题。自选列表复用 /my/collections。头部昵称改为个人中心入口，退出作为独立按钮或菜单动作，避免点击名字直接退出。

资料包含昵称（1–40 字）、简介（最多 500 字）、加入时间和真实统计；邮箱仅当前用户可读，本期不开放修改邮箱/密码。首期头像用昵称首字母占位；头像上传、公开主页、关注用户另列后续需求。

| 接口草案 | 数据规则 |
| --- | --- |
| GET /api/me/profile | 仅当前用户资料与统计 DTO，不返回 passwordHash |
| PATCH /api/me/profile | 只允许 displayName、bio；空白/长度校验；忽略或拒绝身份与权限字段 |
| GET /api/me/posts?page=0&size=20&type=ARTICLE | authorId 必须由会话确定，按 createdAt/id 稳定排序 |
| GET /api/me/collections?page=0&size=20 | 按 ownerId 查询，不能按同名昵称匹配 |

UserAccount 新增 bio；已有 denormalized authorName/ownerName 的展示语义固定为发布时昵称快照，个人页展示当前昵称，首期不全表级联改旧内容署名。如需动态署名，应单独记录决策并统一 API，避免部分页面更新部分不更新。

认证初始化封装成共享状态，路由守卫等待 /api/auth/me 完成后再判断；401 清理用户状态和私有缓存，登录后仅允许站内 returnTo。退出清理个人数据；用浏览器后退不能再次展示上一用户私有缓存。

补齐 Cookie 写请求的 CSRF 策略。若引入 Spring Security Web，明确公共/私有端点、JSON 401/403、CSRF token 获取与前端请求头，并回归现有登录/注册/退出；不能把禁用 CSRF 当作功能修复。

验收：用户 A 不可查询或修改用户 B 的个人资源；刷新后资料持久；非法字段不能更改用户身份；统计来自数据库；已有登录流程不回归；私人 /private/tools 仍需独立密钥。

## 7. 分步执行与文件落点

| 顺序 | 目标候选版本 | 工作与主要文件 | 完成条件 |
| --- | --- | --- | --- |
| P0 | 0.4.0 | 环境核对、脚本退出码、前端构建和版本同步 | 已完成 |
| P1 | 0.4.0 | PostgreSQL、Flyway、保留 ID 的 H2 导入与备份 | 已完成；隔离恢复/行数核对通过，Flyway 10.20.1 + PG17 验证无支持范围告警 |
| P2 | 0.4.0 | 专题归属/关联/自选表及 API、Vue 页面和路由 | 已完成；桌面登录/目录/私有页验收，行为测试通过 |
| P3 | 0.4.0 | 个人资料、我的内容/专题、CSRF 和会话测试 | 已完成；PostgreSQL 写入回归 7/7 通过，浏览器双用户专题隔离通过 |
| P4 | 0.4.0 | CHANGELOG、VERSION、Maven/npm 版本、运行说明与交接 | 已完成；生产构建、Maven 打包/测试、运行态 API 与页面复核均通过 |

按功能拆分 Controller/Service/Repository，数据库事务置于服务层，避免继续把所有新功能放进 ContentController 或 App.vue。现有 Java 包名 com.aitome 暂保留，品牌与包名迁移不要混为一次无关重构。

每步更新任务状态和证据，再进入下一步；不用一次大改后才验收。计划版本可随实际范围调整，但不得预先把未实现版本写入 VERSION。

## 8. 验收记录与性能

每项记录：需求 ID、场景/前置数据、操作、预期、实际、命令/截图、结果、待办。后端身份、迁移、幂等与事务使用行为集成测试；数据库测试使用与部署一致的 PostgreSQL，Docker 不可用时可用隔离测试实例，并写明条件。

浏览器至少验证桌面 1440×900、移动 390×844，包含登录、个人路由、导航、直达刷新、空态、失败和错误提示。API 成功与构建成功均不能代替页面交互验证。

性能验收先定义数据量与机器再测；本方案暂建议开发环境 4 CPU/8 GB、1 千用户/1 万内容/1 千专题、20 并发只读请求，记录 p50/p95/错误率，候选目标 p95 ≤ 500ms、无错误。此为待确认基准，不是当前性能承诺。检查 SQL 分页、索引、N+1 和连接池，截图中统计数字不得用硬编码伪装真实数据。

完整容量目标仍需用户确认机器、数据规模、并发和服务等级目标。FINNS-REQ-005 已实施：`GET /api/posts/{id}/comments?page=0&size=20` 返回 `{items,page,size,total}`，页码规范到 0–10000、size 限制在 1–50，按 `createdAt,id` 正序稳定排序；缺少文章返回 404。讨论弹窗每次只读 20 条，支持加载更多、失败重试和发帖后本地更新。当前 `/api/posts` 仍返回最新 30 项、`/api/collections` 仍返回最新 50 项，个人列表已有分页。依据与研究边界见 DEVELOPMENT_REFERENCES.md。

## 9. 本轮实际实施状态（2026-09-28）

- PostgreSQL 17.11 使用 EDB 官方 Windows 二进制归档，SHA-256 已核验；只解压 `pgsql/bin`、`lib`、`share` 到 `E:/tools/finns-runtime/`，没有向 C 盘安装，也没有注册系统服务。数据库只监听 `127.0.0.1`，数据位于项目忽略目录 `data/postgres/`。
- 新增 Flyway `V1__baseline.sql`、`V2__profiles_and_collection_ownership.sql`、`V3__data_migration_audit.sql`；PostgreSQL profile 为 `ddl-auto: validate`，默认启动脚本切至 PostgreSQL，H2 配置及源文件保留。
- H2 导入批次 `h2-aitome-v1` 完成：users=1、posts=3、comments=1、collections=3、VPS=3；无未解析的 ownerName。ID、密码哈希、时间及内容保留。历史 item_count 未伪装成内容关联；collection_posts 初始 0 条。
- 已验证源 H2 离线备份 `data/backups/final-h2-20260928/aitome.mv.db`，SHA-256 `ED4828DB268C2F0DD65D326759DDB75F6BBF3BEF9F53771DA48E1E8199EB649D`。前一份 cutover 备份另存于 `data/backups/20260928-130218/`。H2 原文件未删除。
- `scripts/migrate-h2-dry-run.ps1` 已完成只读业务行预检；`scripts/migrate-h2.ps1` 在停服、备份校验后执行导入；同批次检测到已导入时安全跳过。
- PG profile 首次 Flyway V1–V3、Hibernate validate、正式启动通过。登录旧账号成功；个人资料、我的专题、自选专题和公开专题接口读取成功；直达 `/me`、`/me/posts`、`/me/collections`、`/my/collections`、`/collections`、`/vps` 均返回 SPA。路由 `/collections/:id` 展示专题内容；文章可收录到本人专题。
- Cookie-to-header CSRF、会话 ID 轮换和服务端所有权校验已加入；密码哈希不进入个人资料 DTO。`src/test/.../AiTomeApplicationTests` 覆盖注册、登录态发文、讨论、资料更新、自有内容入专题、拒绝他人内容、收藏与真实计数。
- 已新增 PostgreSQL 本地脚本、`.env.example`、`docs/DATABASE.md` 备份/恢复命令和 AGENTS/项目 skill 的 D/E 盘约束。本地信任认证仅开发可用；共享/生产须配置密码/证书。
- 最终验证：`npm run build` 通过；`./scripts/test.ps1` 的 7 项测试全部通过；`./scripts/package.ps1` 完成 Vue 生产构建及 Maven package（含测试）。`VERSION`、Maven、npm package/lock 均为 `0.4.0`。
- 最终产物 `target/finns-0.4.0.jar` 使用 PostgreSQL profile 启动；Flyway V1–V3 与 Hibernate validate 通过，`/api/collections` 返回 HTTP 200。页面 `/`、`/collections`、`/collections/1`、`/vps`、`/me`、`/me/posts`、`/me/collections`、`/my/collections` 均返回 SPA；浏览器完成登录、个人中心与自选专题页面检查（1280×720）。
- PostgreSQL 17.11 隔离恢复演练：从 `data/backups/finns-validation-20260928-133853.dump` 恢复到独立 `finns_restore_check_20260928_133853`，测试前与源库逐表行数一致（用户 1、文章 3、评论 1、专题 3、VPS 3），Flyway V1–V3 历史一致；克隆应用 Hibernate 校验、旧账号登录、文章/专题读取与登出通过。备份 SHA-256：`B71CD851B2E18EF4840D1252E31246607171ABA81D65F0E1BBAD9472C2777E61`。克隆库上的 PostgreSQL 行为集成 7/7 通过；演练及写入测试完成后，在确认 owner 为 `finns_app`、无活动连接后，仅删除上述隔离副本，保留 dump；`finns` 主库未被清理或覆盖。
- Flyway 已由 10.10 升至 10.20.1；在 PostgreSQL 17.11 隔离副本重新 validate / 测试、Hibernate validate，并用正式产物启动通过，未再出现先前的版本支持范围告警。
- 浏览器验收扩展到桌面 1440×900 和移动 390×844：顶部“自选专题”导航可见；个人页面导航改成窄屏两列且无横向溢出；账号操作不折行。两个独立浏览器会话分别创建账号后，A 创建的个人专题在 B 的“我的专题”不可见，A 刷新后仍可见；主站 `/me`、`/my/collections` 登录和退出流程通过。
- 本机只读性能冒烟（Windows 11、i5-13500H 12 核/16 线程、约 29.7 GB RAM；小数据集：3 篇文章/3 个专题/1 条评论）：每个接口预热 10 次、串行采样 40 次，`GET /api/posts` p50 9.88 ms / p95 10.83 ms / 0 错误，`GET /api/collections` p50 8.75 ms / p95 10.23 ms / 0 错误。该数字包含本机客户端开销，不是并发测试、生产 SLA 或容量承诺。

### 尚未完成的独立验收

- 正式容量/并发验收仍待产品侧确认机器档位、数据量、并发模型及 SLA；本机串行冒烟不可替代该测试。后续性能迭代应评估公开列表分页、相关索引和慢查询。
- 本轮不发布新的容量承诺；只读冒烟的时间值是当前小数据集参考点，环境和测量方法变化后需要重测。

## 10. 评论分页实施记录（2026-09-29）

- `GET /api/posts/{id}/comments` 已由无上限数组改为分页 DTO `{items,page,size,total}`；默认每页 20、硬上限 50、页码范围 0–10000；排序键为 `createdAt ASC,id ASC`。Flyway V1 已含 `(post_id,created_at,id)` 索引，本次没有改真实数据库结构或新增迁移；JPA 实体索引声明与该索引同步。
- Vue 发现页讨论弹窗按页加载、保留既有时间正序、合并重复 ID、显示总量，提供加载更多与错误重试；成功发表的评论立即追加到当前内容，不再重新下载整条讨论。
- 自动化行为验证：6 项应用测试 + 2 项本地工具测试，H2 共 8/8；同样 8 项对 PostgreSQL 17.11 隔离库通过。专门构造 55 条同时间戳评论，验证 size=100 被限制为 50、结果跨页为 50+5、页面无重叠且同时间戳按 ID 稳定排序；也覆盖负页码、空页、404。首次 PG 测试发现原公开列表测试假定至少 3 篇演示文章，空库仅有 2 篇；断言改为验证公开 API 数组契约后，在全新 PG 隔离库重跑通过。
- 浏览器对隔离测试数据库中的 55 条讨论做了真实页面验收：初始显示 20 条，点击一次“加载更多讨论”显示 40 条，再点击显示 55 条并隐藏按钮。测试账号与数据在隔离库；FINNS 主库没有用于造测试评论。临时克隆服务已停止；当前用户服务仍在 `127.0.0.1:8080`。
- 本轮生产构建通过，Vue bundle JS 128.93 kB（gzip 47.62 kB），最终产物仍为本地开发版本 `target/finns-0.4.0.jar`。容量 SLA 仍待确认，不以本功能验收代替正式并发测试。

## 11. 首页统计数据真实化（2026-09-29）

- 新增公开只读 `GET /api/stats`，仅返回 `creatorCount`、`contentCount`、`collectionCount` 聚合数字；不返回用户资料、邮箱或任何身份数据。统计来自 PostgreSQL 当前表，不使用硬编码常量；无需迁移或新增依赖。
- 发现页并行加载首页内容和统计，加载未完成显示 `—`；将虚构的 2.4k/8.7k/16 改为数据库真实值，标签调整为“创作者 / 公开内容 / 专题目录”。
- 自动化测试逐项将 `/api/stats` 与同一数据库 `COUNT(*)` 对照；最终 `scripts/package.ps1` 的 H2 测试 9/9 和全新 PostgreSQL 17.11 隔离库测试 9/9 通过，Flyway V1–V3 与 Hibernate validate 均通过。验证中数据库主库计数为 1 用户、3 内容、3 专题；浏览器实际显示一致。
- 最终 JAR 启动后，`http://127.0.0.1:8080/` 返回 200；`/api/stats` 返回 `{"creatorCount":1,"contentCount":3,"collectionCount":3}`，`/api/posts` 与评论分页接口正常。服务只监听 `127.0.0.1:8080`，数据库只监听 `127.0.0.1:5432`；最新首页浏览器已重新加载并显示真实统计。
- 隔离 PostgreSQL 测试库经 owner/活动会话核验后已移除；主库 `finns` 保留。最终产物仍记录在 `Unreleased`，版本文件维持 `0.4.0`，未宣称正式发布。

## 12. 用户需求复核与下一 AI 执行计划（2026-09-29）

用户重申“真实数据库、参考截图、新增个人页面、固定规范开发 Skill，并为下一位 AI 写方案”时，核对源码与第 9–11 节确认前三项已属于 FINNS-REQ-001/002/003，不能重复实施或重新标成待开发。随后按仍在执行的总体开发目标完成既有 FINNS-REQ-007。本次页面/分页验收只使用隔离 PostgreSQL 测试库；主库 `finns` 仅做只读版本与计数核对，保持 Flyway V1–V3 和原业务行不变。

| 本轮重申内容 | 既有需求 | 当前基线与本轮处理 |
| --- | --- | --- |
| 连接真实数据库 | FINNS-REQ-001 | 已有本机 PostgreSQL 17.11 + Flyway V1–V3；恢复演练及 PostgreSQL 行为测试证据见第 9–11 节。本轮只读核对主库版本与计数，没有执行主库迁移或写入。 |
| 参考截图 | FINNS-REQ-002 | 截图红框/注释指向顶部“自选专题”；当前 `frontend/src/App.vue` 已将入口指向私有 `/my/collections`，公开 `/collections` 与 `/vps` 保留。截图作为入口及信息架构依据，不要求逐像素重做整页。 |
| 新增个人页面 | FINNS-REQ-003 | 当前已有登录保护的 `/me`、`/me/posts`、`/me/collections` 和 `/my/collections`；本地工具仍在独立密钥保护的 `/private/tools`，不得合并进普通个人中心。 |
| 固定规范开发 Skill 并交接下一位 AI | FINNS-REQ-004 | 强化 `.agents/skills/finns-development/SKILL.md` 的规划/实施/诊断边界、重复需求识别、统一状态、验证闭环和交接证据；补充 OpenAI Skill 设计与评估资料，格式校验通过。 |

### FINNS-REQ-007：公开文章与专题目录分页

**状态：验收通过（2026-09-29）。**

**实施前基线**：`GET /api/posts` 固定取 30 条，文章/话题筛选在前端；`GET /api/collections` 固定取 50 条。公开专题详情内容接口另有 100 条上限，本需求不调整该上限。

**执行方案**

1. 将 `/api/posts` 和 `/api/collections` 统一改为兼容的分页响应 `{items,page,size,total}`；默认 `size=20`、最大 `50`，页码限制与评论分页约定一致。文章筛选 `type=ARTICLE|TOPIC` 由服务端分页；无筛选时统计总量。
2. 文章排序稳定为 `createdAt DESC,id DESC`；专题按 `updatedAt DESC,id DESC`。核实 Spring Data / SQL 查询与 count 查询的实际行为。仅当查询分析证明必要时追加 Flyway V4 索引，不改写 V1–V3，也不对主库直接试验索引或造数。
3. 更新全部公共专题目录消费者：`CollectionsPage.vue` 与个人自选页中的“发现更多专题”目录均须读取分页 DTO 并能继续浏览后续页；不得因响应从数组变对象而破坏页面。个人已选专题接口及排序/移除语义保持不变。
4. 更新 `DiscoverPage.vue` 的分类筛选、加载更多、页切换后的状态/错误重试与加载反馈；防止切换筛选时旧响应覆盖新数据。列表分页之外，保留 `?post=<id>` 深链接能力：若目标内容不在当前已加载页，应按 ID 获取详情，不得静默打不开。
5. 评估全局排序与专题计数查询的索引、查询计划和 N+1 风险；若需数据库结构调整，仅新增 append-only Flyway 迁移，并在隔离 PostgreSQL 测试库验证。

**验收标准**

- API 默认页、显式页、非法/越界 page、`size` 上限、空页、总数和类型过滤正确；多页无重复/漏项，时间戳相同仍按 ID 稳定排序。
- 后端行为测试在超过一页的数据集上验证文章、话题及专题目录；断言响应字段和结果，不依赖 DemoData 的固定数量。H2 通过之外，还须用 `scripts/test-postgres.ps1 -DatabaseName finns_test_<唯一后缀>` 在隔离 PostgreSQL 17.11 上通过。
- Vue 发现页、专题广场和自选页都可继续加载、切换分类及重试失败；空态和 `total` 边界正确。验证文章详情深链接、相关一级路由直达/刷新，以及 1440×900 和 390×844 视口无横向溢出。
- 保持 FINNS 0.4.0 作为当前未发布开发基线：本次实现记录到 CHANGELOG `Unreleased`；若没有正式发版指令，不改四处版本文件，不提交/推送 Git。

**非目标与安全边界**：不改专题详情的 100 条内容上限（另立需求）；不触碰主库或导入/清理其数据；不改登录、个人资料、私人本地工具和 VPS 功能；不宣称已完成容量测试或达到 SLA。先检查工作区、脚本参数与数据库目标，再按项目 Skill 执行。

**实际完成与验收证据**

- `/api/posts`、`/api/collections` 返回 `{items,page,size,total}`，默认每页 20、最大 50；文章支持服务端类型筛选，排序为 `createdAt DESC,id DESC`；专题排序为 `updatedAt DESC,id DESC`。页码、非法 size、空页、重复时间戳与 ID 稳定性有行为测试覆盖。
- 新增 `V4__public_list_pagination_indexes.sql`：文章 `(created_at DESC,id DESC)` 与专题 `(updated_at DESC,id DESC)` 索引；未改写 V1–V3。隔离库 `finns_test_public_pagination_20260929` 的 Flyway V1–V4 校验/启动通过；该库保留测试数据，后续不得误当作主库或自动删除。
- `DiscoverPage.vue`、`CollectionsPage.vue`、`SelectedCollectionsPage.vue` 已迁移到分页 DTO，具备筛选、加载更多、失败重试、总数反馈及去重；文章 `?post=<id>` 深链接在目标不在首屏时仍打开详情。
- `scripts/package.ps1`：Vue/Vite 生产构建通过；H2 测试 11/11 通过；Maven 打包成功，产物 `target/finns-0.4.0.jar`。隔离 PostgreSQL 测试 `scripts/test-postgres.ps1 -DatabaseName finns_test_public_pagination_20260929`：11/11 通过；该 PG 测试在最后一次仅 CSS 的改动之前运行，之后 Java/API/迁移源码未改。
- Edge 浏览器对隔离数据库进行交互验收：发现列表 20→40→59，文章/话题筛选分别只显示匹配类型（测试集为 3 篇文章、56 个话题）；专题广场与自选专题目录 20→40→58，末页加载按钮消失；第 2 页文章深链接可打开；登录态 `/me` 与私有目录直达正常。
- 最终构建 CSS 清缓存后复验 390×844；`/`、`/collections`、`/my/collections`、`/me`、`/me/posts`、`/me/collections` 均未出现横向溢出，移动导航显示“发现 / 专题 / 自选专题”。桌面交互在 1440×900 检查通过。临时 8081 测试服务已关闭；页面截图仅用于视觉检查，未写入仓库。
- 主库只读核验为 Flyway V1–V3、1 用户/3 内容/3 专题；本次没有对其执行迁移、测试写入、导入或清理。没有初始化 Git、提交或推送；版本仍为 0.4.0，改动记在 `Unreleased`。

### FINNS-REQ-008：正式容量与性能验收

**状态：暂缓（2026-09-29，用户明确回复“先不处理这个内容”）。** 暂不收集目标 CPU/内存与部署拓扑、数据规模、读写比例、并发/持续时间、缓存和连接池前提、错误率及 p95/p99 SLA；不开展正式负载测试，也不再追问。第 8–11 节的机器信息和小数据集串行冒烟仅为历史基线；文中的候选 p95 数值不是用户承诺。仅在用户重新提出时，再先形成可重复的基准方案并选择隔离环境；不得把正式压力施加到本机主库。

## 13. FINNS-REQ-009：公开文章列表只传输摘要，详情按需读取

**状态：验收通过（2026-09-29）。**

**源码事实与范围**：`/api/posts` 由发现页用于卡片列表；卡片展示 id、类型、标题、摘要、作者、时间和点赞数，不读取正文。原接口列表页每条仍序列化完整 `body`（最长 20,000 字符）。仓内搜索确认该列表接口的唯一 Vue 消费者是 `DiscoverPage.vue`。`GET /api/posts/{id}` 和发布/点赞等详情响应保持原语义与完整正文；本需求不涉及正式 SLA、数据库结构/索引、缓存、正文编辑或外部 API 客户端兼容承诺。

**执行方案**：列表使用 `PostSummary` DTO，返回卡片所需字段并省略 `body`；发现页打开卡片时请求对应详情，显示加载中/错误重试；`?post=<id>` 直达链接仍获取完整正文，支持首屏外文章。

**验收标准**：

- 文章列表 JSON 不包含 `body`，文章详情仍逐字保留完整正文；长正文列表响应字节数小于详情响应。
- 点击卡片后加载完整详情；使用 `?post=<id>` 直接打开时亦显示完整正文。
- H2 与隔离 PostgreSQL 行为测试通过；PostgreSQL 测试只能对既有隔离库运行，不能对 `finns` 主库运行。
- 保持开发版本 0.4.0；不新增迁移、不改主库、不宣称完成容量或 SLA 验收。

**实际完成与验收证据**：

- `ContentController` 的 `/api/posts` 映射为 `PostSummary`（id/type/title/summary/authorName/createdAt/likes）；`GET /api/posts/{id}` 保持返回完整 `ContentPost`。`DiscoverPage.vue` 的卡片交互异步获取详情，包含加载态、错误态与重试；查询参数直达路径兼容。
- `publicPostListOmitsLongBodyUntilDetailIsRequested` 以 20,000 字符正文验证列表省略正文、详情内容一致且列表响应更小。分页行为测试改为根据 API 实际报告的 total 遍历各页，确保可复用的隔离 PG 测试库不会因既有测试行数导致脆弱失败；未清理该库中的历史测试数据。
- `scripts/package.ps1`：Vue/Vite 构建成功，H2 12/12，Maven 打包成功。`scripts/test-postgres.ps1 -DatabaseName finns_test_public_pagination_20260929`：PostgreSQL 17.11 / Flyway V1–V4、12/12 通过。
- Edge/CDP 浏览器检查：列表首屏所选卡片记录不含 body，详情 API 正文长 20,000；点击卡片与 `?post=122` 直达均完整显示 20,000 字正文。该次是功能交互检查，不是容量测试或视觉回归测试。
- PostgreSQL 隔离测试应用无新迁移（仍 V4）。主库未连接、迁移、压测或写入；8081 临时测试服务已关闭。版本文件未改，Unreleased 已记录。

## 14. FINNS-REQ-010：专题内容稳定分页与数据库摘要投影

**状态：验收通过（2026-09-29）。**

**源码事实与用户意图**：REQ-009 使公开文章列表的 HTTP JSON 不再包含正文，但原 JPA 查询仍读取完整 `ContentPost` 实体。专题详情 `/api/collections/{id}/posts` 固定最多返回 100 个完整实体，个人 `/api/me/posts` 也虽分页但取回正文；对应 Vue 页面均只显示卡片字段。专题详情 100 条上限在 REQ-007 中已明确列作另立需求。本次依据“内容可持续浏览且满足性能需求”的总体目标，按数据库层投影消除列表正文读取，并解除专题详情固定条数截断。

**执行结果**：

- 新增共享 `ContentPostSummary`。`/api/posts` 与 `/api/me/posts` 使用 JPQL 构造器投影，只选择 id/type/title/summary/authorName/createdAt/likes，不将 body 从数据库加载到实体；`GET /api/posts/{id}` 仍返回完整正文。
- `/api/collections/{id}/posts` 改为 `{items,page,size,total}`，默认 20、最大 50，按关联 `created_at DESC,post_id DESC` 稳定分页；SQL 只取卡片字段。追加 Flyway V5：`collection_posts(collection_id,created_at DESC,post_id DESC)` 和 `content_posts(author_id,created_at DESC,id DESC)` 索引。
- `CollectionDetailPage.vue` 支持总数、加载更多和失败重试，文章卡片继续跳转至正文详情；`MyPostsPage.vue` 支持继续加载和文章直达链接。修正其 API hasMore 查询原先以 `size+1` 做 PageRequest 页大小造成的跨页偏移，避免边界文章被跳过。

**验收证据**：

- 新增行为测试构造 55 条同关联时间内容，确认专题页按 20/20/15 返回、总量正确、ID 倒序稳定、页间无重叠/遗漏，且列表不返回 body；个人内容分页同样按 20/20/15 完整覆盖、hasMore 正确并省略 body；详情接口仍完整返回 20,000 字正文。还覆盖 size 最大值 50、无效专题 404。
- `scripts/package.ps1`：Vite 构建、H2 13/13 和 Maven 打包通过。`scripts/test-postgres.ps1 -DatabaseName finns_test_public_pagination_20260929`：PostgreSQL 17.11、Flyway V1–V5、13/13 通过；分页数据库只在隔离库应用 V5，测试行保留、不删除。
- Edge/CDP：隔离测试专题内容在页面由 20→40→55 条增长、计数一致；首屏 API 项无 body；点开长正文专题卡片，经 `?post=` 加载并显示 20,000 字正文。登录测试账号的 `/me/posts` 首屏显示 20 条、API hasMore=true、摘要项无 body，标题保留直达详情链接。
- 最终视觉复验使用禁用缓存并绕过旧 Service Worker 的本轮生产 CSS：专题详情与个人内容页在 1440×900、390×844 均无可滚动横向溢出（`scrollWidth == clientWidth`，尝试横向滚动后的 `scrollX=0`）；各页首屏 20 条。长标题能断行，桌面专题卡片网格不被最小内容宽度撑开。修正绝对定位背景光晕越过视口的布局；检查截图保存在 `E:/Temp/FINNS-REQ010-final-*.png`，不在仓库。
- 视觉修正后的最终 `scripts/package.ps1` 再次通过：Vue/Vite 生产构建、H2 13/13、Maven package。隔离库上的 PostgreSQL 13/13 已在本轮纯 CSS 调整之前通过；本轮仅改 CSS/文档，Java/API/迁移未改，不重复运行数据库测试。
- 上述 REQ-010 功能验收时主库保持 V1–V3，隔离测试库为 V1–V5。后续用户于 2026-09-30 明确要求启动项目，默认启动在本机主库 `finns` 应用 V4–V5（仅新增排序索引，不改业务行），现 Flyway V1–V5；启动与访问证据见第 15 节。未做性能压测/SLA测量；REQ-008 按用户明确回复继续暂缓。版本保持 0.4.0 / Unreleased；未向 C 盘安装程序、未初始化或提交 Git。

## 15. 2026-09-30：启动 FINNS 本地开发服务

**用户请求**：启动项目并提供访问链接。按 `scripts/run.ps1` 默认 PostgreSQL 配置启动，不使用隔离验收库冒充主站。

**启动及数据库结果**：

- 启动前确认 `FINNS_DB_URL` 未设置，默认目标为 `127.0.0.1:5432/finns`；Java 17 固定路径为 `D:/java/jdk/jdk-17.0.12`，PostgreSQL 17.11 运行时在 `E:/tools/finns-runtime`，前端 `node_modules` 已存在，未安装依赖。
- 当次启动前主库为 Flyway V1–V3，启动脚本应用 V4、V5；两项迁移内容均为新增 B-tree 索引（V4 公开文章/专题列表，V5 专题内容及个人内容排序），不修改业务记录。该记录已被第 16 节的 REQ-011 V6 启动结果取代。
- Spring Boot 3.3.8 + Java 17 启动成功，进程 PID 27676，监听 `127.0.0.1:8080`；PostgreSQL 仍只监听回环 `127.0.0.1:5432`。
- 浏览器/API 冒烟：主页 `GET /`、`/collections/1` 与 `/vps` 返回 200；`GET /api/stats` 返回 `{"creatorCount":1,"contentCount":3,"collectionCount":3}`。首页标题为 `FINNS · 技术人的灵感基站`。
- 本轮启动流程没有重跑 H2 或 PostgreSQL 行为测试；已执行 Vue/Vite 生产构建、Maven `spring-boot:run` 编译/启动及上述 HTTP 冒烟。
- 当前版本 0.4.0 / Unreleased；服务保持运行。停止时在 `scripts/run.ps1` 对应终端按 Ctrl+C；不需要停止 PostgreSQL。

## 16. FINNS-REQ-011：结构化专题工作台

**状态：验收通过（2026-10-02）。** 用户已明确要求完成方案，数据、API、Vue 工作台、公开阅读页和隔离验收均已完成。

用户希望更方便地制作“租房心得步骤”“动漫阅读路线”等专题。当前专题只有名称、简介和无章节的站内文章集合，无法表达明确顺序、阶段、作者说明或外部资料。方案不为每个领域制作独立功能，而是提供统一的结构化专题模型：

- 创建时选择步骤指南、阅读路线、资源清单或空白模板；模板生成可编辑的章节骨架。
- 专题由有序章节和有序条目组成；条目支持作者笔记、自己的站内文章和外部链接。
- 增加草稿、已发布、已归档状态；专题工作台支持自动保存、预览、发布以及 revision 冲突保护。
- 新增 `/me/collections/new` 和 `/me/collections/:id/edit` 独立路由；公开 `/collections/:id` 按章节和编号路线展示。
- 实施时使用 append-only Flyway V6，新表回填现有 `collection_posts`，保持旧专题公开状态、数量和当前显示顺序；迁移验证只在隔离 PostgreSQL 进行，再按明确启动/迁移授权处理主库。
- 第一阶段不做 AI 自动生成、多用户协作、读者进度、图片上传或收录他人文章。AI 大纲建议可在核心编辑器稳定后另立需求，并必须只生成可编辑草稿。

完整字段、API、权限和迁移兼容见 `docs/COLLECTION_BUILDER_PLAN.md`。实际实现新增 append-only V6、完整草稿 API、Vue 新建/编辑工作台和有序公开阅读页。H2 与隔离 PostgreSQL 17.11 均为 14/14 测试通过；Vue/Vite 与 Maven package 通过。主库已应用 V6，服务以 Java 17 / Spring Boot 3.3.8 监听 `127.0.0.1:8080`。浏览器完成桌面和 390×844 公开阅读页检查，控制台无 warning/error；登录态编辑流程由自动化测试覆盖，未在主库创建验收数据。

## 下一位 AI 执行顺序

REQ-012 第一阶段设计/交付见第 17 节；当前修复与运行状态见第 19 节。以下第 9–16 节中的版本与 PID 为当时记录。

1. 阅读 `AGENTS.md`、项目 Skill、本文第 9–16 节、`docs/COLLECTION_BUILDER_PLAN.md`、`docs/AI_HANDOFF.md`、`VERSION` 和 `CHANGELOG.md`；再以当前源码确认状态，不重复实施 REQ-001–007、009–010。
2. FINNS-REQ-008 按用户要求暂缓；不要重复询问或自行开展性能负载测试。只有用户明确恢复时再拟定隔离方案。
3. FINNS-REQ-011 已验收通过，后续不要重复实现。AI 大纲、读者进度、协作编辑等增强必须另立需求，不得顺带接入外部服务或改变当前发布边界。

## 17. FINNS-REQ-012 第一阶段交付（2026-10-03）

状态：已实施，技术验证通过；非技术用户三分钟导入目标未实测。用户已要求按方案实施并记录版本。本地开发版 0.5.0 同步 VERSION、Maven/npm/lock；CHANGELOG 按版本归档本地迭代，未远程发布。

已完成三种起点、五个场景模板、UTF-8 TXT/MD 与粘贴导入预览、新建/追加幂等提交、本人完整/结构复制、私人预览、未完成草稿保存、发布定位、保存队列和公开快照全入口隔离。Java 17、Spring Boot 3.3.8、Vue 3/Vite 保持不变。

最终验证：Vue/Vite build + H2 20/20 + Maven package；隔离 PostgreSQL 17.11 / V1–V8 20/20；Node 保存队列 2/2；桌面/390px 浏览器检查导入、文件追加、私人预览与刷新、保存/发布隔离、空白创建、缺项发布、删除撤销、结构复制。日志及截图、源码入口见 `docs/TOPIC_IMPORT_PLAN.md` 第 10 节和 `docs/AI_HANDOFF.md` 最新记录。

实施验收时主库 `finns` 为 V6，V7/V8 仅在隔离库应用；随后按用户部署请求切换主站；0.5.1 创建体验修复和最新部署状态见第 19 节。真人试用、容量压测、远程部署尚未执行。个人模板/导出、富文本、复杂文件与 AI 属后续阶段；REQ-008 继续暂缓。

## 18. 0.5.0 本机重新部署（2026-10-03）

用户已明确要求重新部署最新版本。使用最终已验收 0.5.0 产物的独立部署副本，停止旧 8080 进程后启动 Java 17 / postgres profile；主库 `finns` 已应用 V7/V8，现 V1–V8 全成功。主站为 `127.0.0.1:8080` / PID 25912；隔离预览 8081 已停止。

已备份主库并实际恢复到独立演练库，11 张既有业务表校验一致；迁移后再次核对所有既有业务表内容未变，4 个既有公开专题成功回填快照。HTTP 页面/API/前端资源核验、未登录私有接口 401、浏览器目录/详情/刷新和控制台检查通过。具体备份、摘要、启动方式、日志和截图路径见 AI_HANDOFF 最新部署记录。没有在主库运行写入集成测试或创建验收数据，没有远程发布；版本号仍为 0.5.0。

## 19. 0.5.1 创建体验修复与本机部署（2026-10-03）

已把创作入口和专题广场按钮直接接入新建页；新建表单收紧为名称、起点和可选首段正文，并在同一事务中创建；笔记小标题变为可选。编辑器按章节折叠，正文优先填写，发布缺项可点击定位；导入原文改变会阻止提交过期整理结果，并要求在页面内明确确认重新整理。

验证：Vue/Vite 生产构建通过；H2 集成测试 21/21、隔离 PostgreSQL 17.11 集成测试 21/21、Node 行为测试 4/4。浏览器在隔离测试库验证移动端创建与章节编辑、无标题笔记发布、缺项定位、原文变化保护及页面内重整理；截图 `data/req012-ux-mobile-create.png`、`data/req012-ux-mobile-editor.png`。数据库无新迁移，主库维持 V1–V8。

0.5.1 已部署到 `127.0.0.1:8080`，Java 17 / PostgreSQL 主库 `finns`，PID 与日志见 `docs/AI_HANDOFF.md` 最新记录。首页、专题广场、新建路由与最新 JS 资源返回 HTTP 200。部署前后 11 张业务表行数/摘要相同；部署备份已创建，未做主库业务写入，隔离库浏览器数据不进入主库。未远程发布。非技术用户真实试用和三分钟目标仍未验证。

