# FINNS 社区平台开发计划

> 历史初始规划：包含尚未实施及已被后续选择替代的技术设想（如 Next.js、Java 21）。当前基线为 Java 17 + Vue 3。后续实施以 [新需求方案](docs/NEXT_ITERATION_PLAN.md)、[AI 交接](docs/AI_HANDOFF.md) 和根目录 AGENTS.md 为准。

> 目标：构建一个面向技术实践与资源分享的内容社区。用户可登录、发布文章和话题、创建专题、围绕内容讨论，并提交和浏览 VPS 推荐。项目以 AI 辅助开发为默认工作方式，后端采用 Java；首期设计应能平滑支撑内容增长与高并发读取。

## 1. 产品定位与首期范围

FINNS 是一个以“可沉淀的技术内容 + 可交流的话题 + 可比较的资源推荐”为核心的社区平台。文章适合完整经验与教程；话题适合短讨论与提问；专题把相关内容组织起来；VPS 推荐以结构化信息帮助用户比较服务。

### 首期（MVP）必须交付

| 模块 | 用户能力 | 首期约束 |
| --- | --- | --- |
| 账号与身份 | 邮箱注册、密码登录、退出、找回密码、个人资料编辑 | 预留 OAuth 登录接口；首期至少完成邮箱方式 |
| 文章 | 草稿、发布、编辑、删除、Markdown 内容、标签、封面、点赞、收藏 | 编辑后保留修订记录；删除采用软删除 |
| 话题与讨论 | 发布话题、回复、楼中楼回复、点赞、@ 提及 | 支持按最新和热度排序 |
| 专题 | 创建/维护专题、专题简介、封面、收录文章与话题、关注专题 | 创建者可设置协作者，首期可先只实现创建者管理 |
| VPS 推荐 | 提交服务商/套餐、地区、价格、线路、配置、标签、评分和使用体验 | 推荐先进入待审核状态，避免垃圾和失实内容 |
| 发现与检索 | 首页信息流、专题页、标签页、全文搜索、筛选 | 文章/话题/VPS 推荐统一搜索 |
| 治理与通知 | 举报、内容审核、后台基础管理、站内通知 | 敏感操作须记录审计日志 |

### 首期不做，但必须为后续保留空间

- 支付、广告、会员订阅和联盟佣金结算。
- 实时私信、直播、复杂积分体系和移动原生 App。
- 多语言、多租户、复杂推荐算法；先用可解释的热度规则。
- 用户自行上传大文件；首期只支持图片，且走对象存储。

## 2. 推荐技术方案

### 架构原则

从**模块化单体**开始，而不是一开始拆微服务：它能让 AI 生成和维护代码更快、部署和调试更简单，同时通过明确模块边界、异步事件和独立缓存层，保留将热点服务拆出的条件。

```text
浏览器 / CDN
      │
Next.js 前端（SSR/静态渲染、客户端交互）
      │ HTTPS / REST（后续可补 BFF）
Spring Boot 模块化单体
 ├─ identity  身份、权限、会话
 ├─ content   文章、话题、评论、专题、标签
 ├─ resource  VPS 服务商、套餐、测评
 ├─ search    搜索索引与查询
 ├─ moderation 审核、举报、审计
 └─ notification 站内通知、异步任务
      │             │
PostgreSQL  Redis  OpenSearch  对象存储（S3 兼容）
      │
消息队列（按需引入，用于索引、通知、计数聚合）
```

### 建议的具体技术栈

| 层级 | 选择 | 原因 |
| --- | --- | --- |
| 前端 | Next.js + React + TypeScript + Tailwind CSS + TanStack Query | SSR/静态页面利于首屏和 SEO；生态成熟，AI 辅助生成组件与页面效率高 |
| 富文本 | Markdown 为主，编辑器采用 Milkdown 或 Tiptap，并保存 Markdown 源文 | 内容可迁移、便于版本化、减少 HTML 安全风险 |
| 后端 | Java 21 + Spring Boot 3.x + Spring Web + Spring Security + Bean Validation | LTS Java、企业级能力成熟，开发及运维人员易招募 |
| 数据访问 | Spring Data JPA（复杂查询使用 jOOQ 或原生 SQL）+ Flyway | 简单领域模型快，复杂列表性能可控，迁移可审计 |
| 主数据库 | PostgreSQL 16+ | 全文能力、JSONB、索引和事务可靠，适合关系型社区数据 |
| 缓存/限流 | Redis | 热门内容缓存、验证码/会话、计数、分布式限流 |
| 搜索 | OpenSearch | 中文全文检索、筛选、排序和后续搜索分析；MVP 也可先用 PostgreSQL FTS |
| 文件 | S3 兼容对象存储（MinIO / 云对象存储）+ CDN | 图片不进入数据库或应用磁盘，易扩展 |
| 可观测性 | OpenTelemetry + Prometheus + Grafana + Loki | 从第一天具备请求链路、指标和日志定位能力 |
| 部署 | Docker Compose（开发）→ Kubernetes 或托管容器（生产） | 本地简单，生产可横向扩容 |

## 3. 性能与容量设计

内容社区通常是“读多写少”。首期按 **10 万注册用户、日活 1 万、峰值 500 RPS、95% 为读取请求** 设计；这是容量目标而不是固定上限。上线前以压测结果校正。

- 内容详情和首页由 CDN 缓存静态资源；SSR 页面设置短缓存与失效策略。
- Redis 缓存热门文章、专题聚合、标签页与 VPS 排行；缓存穿透采用空值缓存/布隆过滤器，热点失效使用互斥或逻辑过期。
- 浏览、点赞、收藏等计数先写 Redis 或事件流，再批量聚合入 PostgreSQL，避免单行更新热点；用户操作真相仍应有可追溯记录。
- 所有列表必须使用游标分页（`created_at + id` 或热度游标），避免深度 `OFFSET`。
- PostgreSQL 为常用过滤和排序建立组合索引；慢查询纳入监控，避免在 API 层循环查询。
- 搜索、通知、索引更新、图片处理和内容审核均异步化；若尚未启用 MQ，则使用可靠 outbox 表 + 定时投递。
- 登录、发布、评论、投票、搜索均按 IP、用户和设备实施 Redis 限流；发布接口配置幂等键。
- 正文、图片和附件走对象存储，不经应用服务器长时间中转；上传使用预签名 URL。

## 4. 领域模型与关键关系

### 核心实体

| 实体 | 核心字段 | 关键关系 |
| --- | --- | --- |
| `users` | id、email、password_hash、display_name、avatar_url、status、role | 1:N 内容、评论、互动与通知 |
| `articles` | id、author_id、title、slug、summary、markdown、html、status、published_at | N:M 标签、N:M 专题、1:N 评论 |
| `topics` | id、author_id、title、body、status、published_at、last_activity_at | N:M 标签、N:M 专题、1:N 评论 |
| `comments` | id、author_id、target_type、target_id、parent_id、body、status | 通用于文章、话题、VPS 推荐；`parent_id` 支持楼中楼 |
| `collections` | id、owner_id、name、slug、description、cover_url、visibility | N:M 文章/话题；即“专题” |
| `tags` | id、name、slug、description | N:M 文章、话题、VPS 推荐 |
| `vps_providers` | id、name、website、description、status | 1:N 套餐 |
| `vps_offers` | id、provider_id、name、region、cpu、memory_mb、disk_gb、bandwidth、traffic、price、currency、billing_cycle | 1:N 用户测评；可作为推荐主体 |
| `vps_reviews` | id、author_id、offer_id、rating、body、status | 1:N 评论 |
| `reactions` | id、user_id、target_type、target_id、reaction_type | 唯一约束 `(user_id, target_type, target_id, reaction_type)` |
| `bookmarks` | id、user_id、target_type、target_id | 唯一约束防止重复收藏 |
| `reports` | id、reporter_id、target_type、target_id、reason、status | 进入审核工作流 |
| `notifications` | id、user_id、type、actor_id、target_type、target_id、read_at | 由领域事件异步创建 |

所有可展示内容均包含 `status`（草稿、待审核、已发布、已隐藏、已删除）、`created_at`、`updated_at`；可编辑内容另加 `version` 乐观锁字段。账户、权限与后台操作必须记录审计事件。

## 5. 权限、安全与合规

- 角色：访客、普通用户、专题管理员、内容审核员、系统管理员。使用 RBAC，并在资源层验证“是否本人/协作者”。
- 密码使用 Argon2id 或 BCrypt 加密；不保存明文密码、令牌或第三方密钥。
- Web 会话优先使用短期 Access Token + 可轮换 Refresh Token（存入 `HttpOnly`、`Secure`、`SameSite` Cookie）；刷新令牌可撤销、可追踪设备。
- 注册/找回密码使用一次性、短有效期令牌；邮箱枚举保护与验证码限流必须到位。
- 所有写入接口做输入校验、授权校验、频率限制和审计；Markdown 渲染必须使用严格的 HTML 白名单防 XSS。
- 使用参数化查询，上传文件校验类型/大小/内容并隔离存储；前端配置 CSP、CSRF 防护和安全响应头。
- VPS 价格、地域、优惠等可能过期：每项显示采集/更新日期、来源链接和“信息可能变化”的提示；禁止伪造官方背书。
- 制定隐私政策、用户协议、内容规范和删除/导出数据流程，并遵守实际运营地区的适用法规。

## 6. API 与页面契约

API 使用版本前缀 `/api/v1`，统一返回问题详情（RFC 9457 风格）和可追踪 `requestId`。先写 OpenAPI 契约，再由前端生成类型和客户端，避免两端各自猜测字段。

### 关键接口示例

| 方法与路径 | 用途 |
| --- | --- |
| `POST /auth/register`、`POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout` | 身份与会话 |
| `GET/POST /articles`、`GET/PATCH/DELETE /articles/{slug}` | 文章列表和编辑生命周期 |
| `GET/POST /topics`、`GET/PATCH/DELETE /topics/{id}` | 话题发布与浏览 |
| `POST /comments`、`GET /comments?targetType=&targetId=` | 通用评论树 |
| `GET/POST /collections`、`POST /collections/{slug}/items` | 专题及其收录内容 |
| `GET/POST /vps/providers`、`GET/POST /vps/offers`、`POST /vps/offers/{id}/reviews` | VPS 目录、套餐和测评 |
| `POST /reactions`、`POST /bookmarks`、`POST /reports` | 互动与治理 |
| `GET /search?q=&type=&tag=&cursor=` | 跨内容检索 |

### 首期页面

1. 首页：关注/推荐内容流、热门专题、热门标签、VPS 精选。
2. 登录、注册、找回密码和账户设置。
3. 文章详情、话题详情、发布/编辑器和个人主页。
4. 专题目录、专题详情与专题管理页。
5. VPS 服务商目录、套餐比较/筛选、测评详情、提交推荐页。
6. 全局搜索页、通知中心、举报入口及运营后台。

## 7. AI 辅助开发工作流

AI 用于加速实现与审查，但不能替代架构决策、权限审查和上线验收。

1. **规格先行**：每个功能在 `docs/specs/` 写用户故事、验收标准、接口和数据变更；由 AI 协助补齐边界条件。
2. **垂直切片开发**：按“数据库迁移 → 后端 API → OpenAPI → 前端页面 → 自动化测试”完成一个小能力，不按前后端大块分割。
3. **模板化生成**：用 Spring Initializr/项目模板生成模块骨架；用 OpenAPI 生成 TypeScript 客户端。AI 只生成小而可审阅的提交。
4. **双重校验**：AI 生成的鉴权、SQL、正则、依赖升级和安全相关代码必须人工复核；每次变更运行格式化、静态检查、单元/集成测试。
5. **知识沉淀**：将确认过的领域规则、API 决策、提示词和故障复盘写入仓库，不把关键知识只留在对话中。

## 8. 里程碑与交付节奏

假设 2 名全栈开发者加 1 名产品/设计协作，首个可用版本预计 8 个开发周。人员不同不改变依赖顺序，只改变并行程度。

| 阶段 | 周期 | 交付物 | 完成标准 |
| --- | --- | --- | --- |
| 0. 基线与设计 | 第 1 周 | 用户旅程、低保真稿、OpenAPI 草案、ERD、Docker 本地环境、CI | 新成员可一条命令启动；关键决策已记录 |
| 1. 身份与内容骨架 | 第 2–3 周 | 注册登录、用户资料、文章草稿/发布/详情、Markdown 编辑器、标签 | 未登录无法写入；文章可完整发布和检索 |
| 2. 社区互动 | 第 4–5 周 | 话题、评论树、点赞/收藏、专题、通知、举报 | 用户可围绕内容完成发布、讨论、组织和反馈闭环 |
| 3. VPS 与搜索 | 第 6 周 | 服务商/套餐/测评、筛选、统一搜索、审核队列 | 可提交、审核、展示并筛选 VPS 信息 |
| 4. 性能、安全与上线 | 第 7–8 周 | 缓存、限流、监控、备份演练、压测、漏洞修复、发布手册 | 满足性能指标，无高危安全问题，具备回滚方案 |

## 9. 质量门禁与验收指标

- 后端：核心领域逻辑单元测试；登录、发布、评论、权限、审核链路覆盖集成测试；接口契约测试防止前后端漂移。
- 前端：关键流程端到端测试（注册/登录、发布文章、评论、创建专题、提交 VPS 推荐）；键盘操作和基础无障碍检查。
- 性能：在目标峰值 500 RPS、读写比 95:5 的压测下，核心读取接口 P95 < 300 ms、P99 < 800 ms（不含外部网络）；错误率 < 0.5%。
- 可用性：每日自动数据库备份，定期恢复演练；发布采用健康检查、滚动/蓝绿策略和可验证回滚。
- 安全：依赖漏洞扫描、密钥扫描、SAST、权限回归测试全部纳入 CI；高危问题阻止发布。
- 体验：移动端优先适配；主要内容页 LCP 目标 < 2.5 秒（真实用户数据持续校正）。

## 10. 建议的仓库结构

```text
finns/
├─ apps/
│  ├─ api/                 # Spring Boot 应用
│  └─ web/                 # Next.js 应用
├─ packages/
│  └─ api-client/          # 由 OpenAPI 生成的 TS 客户端
├─ docs/
│  ├─ specs/               # 功能规格与验收条件
│  ├─ adr/                 # 架构决策记录
│  └─ runbooks/            # 发布、回滚、故障处理
├─ infra/                  # Docker、K8s、监控与 IaC
├─ openapi/                # API 契约源文件
└─ DEVELOPMENT_PLAN.md
```

## 11. 下一步执行清单

1. 确认产品名称、目标用户（开发者/站长/普通用户）和首发地区；它们决定内容规范、中文搜索与合规细节。
2. 建立上述 Monorepo、Java 21/Spring Boot 与 Next.js 基线，以及 PostgreSQL/Redis 的 Docker Compose 环境。
3. 先完成《身份认证》和《发布文章》两份可验收规格，再按垂直切片实现第一个端到端流程。
4. 在功能膨胀前确定审核规则、VPS 信息字段字典和内容排序规则；这三项会直接影响数据模型。

---

### 首个端到端切片：登录后发布文章

**验收场景**：未登录访客打开文章可阅读但不能评论；用户可用邮箱注册并验证、登录后创建 Markdown 草稿，预览后发布；发布的文章出现在作者主页、首页最新列表和搜索结果；作者可以编辑，其他用户只能阅读、点赞、收藏和举报。所有失败请求展示可理解错误，不泄露账户是否存在或系统内部信息。

这是后续话题、专题和 VPS 推荐的共同基础，建议作为工程启动后的第一项实现任务。
