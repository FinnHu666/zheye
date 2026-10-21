# FINNS 0.7.0｜F08 内容敏感拦截与专题首次审批（FINNS-17）：实施与验证记录（G5/G6）

日期：2026-10-20。授权：REVIEW-D16（用户：U1 接入现有文章/评论/专题发布入口；U3 不支持回退旧包；U4 按契约 B1–B6 实施）。契约：[CONTRACT_CONTENT_REVIEW.md](CONTRACT_CONTENT_REVIEW.md)。

状态：已实施，**未验收、未发布、未提交 Git、未写 Plane**。四处版本仍为 0.7.0。

## 1. 改动范围

后端（`src/main/java/com/aitome`）：
- 新包 `review`：`SensitiveRuleService`（检测 + 规则 CRUD，仅当前站长）、`ReviewService`（提交/决定/下架恢复/队列/详情/记录）、`ReviewController`（`/api/review/**`）、`ReviewAudit`（独立记录表，拒绝记录走独立事务）、5 个 JPA 表映射实体（H2 建表与 PG `validate`）。
- `access`：`Permission.CONTENT_REVIEW`（`content.review`）；`AccessBootstrap` 的 H2 种子管理员角色不含该权限（默认仅站长）。
- `content`：`ContentController`（文章、评论经 `ReviewService`，话题保持直接发布；公开读取过滤 `moderation_state`）、`CollectionWorkspaceController`（发布经首次审批/检测，归档使待审失效）、`CollectionPublicationService.publishFrozen`（审批通过发布冻结快照；读取过滤下架）、三个实体加 `moderation_state`。
- `stats/SiteStatsController`、`user/PersonalController`：统计与自选专题排除下架内容。
- `common/ApiExceptionHandler`：`ApiProblem` 可带 `code` 与附加字段。
- 迁移 `V15__content_review.sql`（全部附加）。

前端（`frontend/src`）：`/admin/reviews`（队列/已处理/已发布/已下架）、`/admin/reviews/tasks/:id`、`/admin/reviews/publications/:type/:id`、`/admin/sensitive-rules`（站长）、`/me/reviews`；`ReviewDocument`、`reviewReading.js`、`reviewText.js`、`review.css`；发布表单/评论/专题发布处理 422 拦截与 202 转人工；`api()` 附带 `error.body/code`。

测试：新增 `ContentReviewTests`（14 项）、`TestReviewConfig`（测试库种子中性规则与重置）、`frontend/tests/review.test.js`（4 项）；调整 5 处既有测试：新专题发布测试先 `preapprove`（等同历史已发布专题），统计/受限专题计数测试按 `moderation_state='VISIBLE'` 口径。

## 2. 与契约草案的偏差

| # | 契约 | 实际 | 原因 |
| --- | --- | --- | --- |
| 1 | `POST /api/review/submissions` 通用提交接口 | 不提供；提交直接嵌入原有 `POST /api/posts`、`/api/posts/{id}/comments`、`/api/me/collections/{id}/publish`（请求体新增可选 `requestId`/`draftKey`） | U1 要求接入现有入口；通用接口无调用方 |
| 2 | 命中拦截/转人工用 4xx 异常 | 422 / 202 以 `ResponseEntity` 返回，不抛异常 | 专题发布在 `@Transactional` 内，抛异常会回滚命中计数 |
| 3 | 先建内容状态行再检测 | 先检测（不可用即 503，不建任何状态），再建状态行 | 满足“检测不可用不计数不建状态”；代价：检测不可用期间同 `requestId` 重放也返回 503 |
| 4 | 处置带 `expectedVersion` | 只带 `expectedModeration` | 文章/评论无发布版本号；专题以冻结快照为准 |
| 5 | 队列列表含快照摘要 | 列表只含标题等摘要，正文仅详情返回 | 避免列表泄露大段待审正文 |
| 6 | 首次发布状态行创建 | 在 `REQUIRES_NEW` 事务里创建（避免 PG 唯一冲突毒化外层事务） | PG 事务特性 |
| 7 | 审批不改专题 `revision` | 同左；批准时只置 `status=PUBLISHED/published_at` | 不让作者正在编辑的工作稿版本号被动变化 |

## 3. 验证（命令均在项目根；`FINNS_MAIL_MODE=disabled`，因本机用户环境变量设有 `smtp` 模式会让测试上下文起不来）

| 检查 | 结果 |
| --- | --- |
| `mvn -s .mvn/settings.xml -o test`（H2） | 121/121 通过（基线 107 + 新增 14） |
| `scripts/test-postgres.ps1 -DatabaseName finns_test_070_review_2`（新建隔离库，Flyway 从 V1 到 V15 + Hibernate validate） | 121/121 通过 |
| V14→V15 升级演练：复制库 `finns_test_070_useradmin_full`（V14）→ `finns_test_070_review_upgrade2` | Flyway 成功迁移到 15；除 2 项计数测试外全部通过。该 2 项是**旧库残留的受限专题**导致 DB 计数与游客 API 计数不等（与既有交接“旧库残留数据会让计数类测试失败”一致），不是本项缺陷；全新库 121/121 |
| `node --test frontend/tests/*.test.js` | 42/42（新增 4） |
| `vite build`（frontend） | 84 模块，退出 0；静态产物已更新 |
| 变异/负向检查 | 自审、撤权、停用作者、重复批准、并发批准恰好发布一次、规则版本冲突均有测试 |

## 4. 未验证 / 限制

- **未在浏览器验证已登录页面**：本机预览 <http://127.0.0.1:8091/>（PID 见 `target/account-preview.pid`，库 `finns_test_070_review_preview`）已启动，但演示账号 `owner@demo.finns.test` 用仓库种子密码登录被拒（复制库中账号的密码与当前种子常量不符），随后尝试经“忘记密码”页重置被自动模式拒绝，**未绕过**。桌面/390px/768px、刷新直达、前进后退、长文阅读体验、规则增删改查界面、422/202 提示均**未做浏览器实测**，RV-A13/A15 前端部分待用户验收。
- 真人手机、真实邮件、多设备并发未验。
- 全局性能：每次提交读取全部启用规则（≤500），未做缓存与压测。
- **生产启用前提**：V15 不带任何规则。在站长于“敏感规则”页至少启用一条规则前，所有文章、评论、专题发布都会被拒绝（503，P03 规定的“检测未配置阻止发布”）。发布前必须先备份主库并做恢复演练，并确认首批规则；不支持回退旧包（USER-ADMIN-D13 同类决定：REVIEW-D16-U3），回退会让已下架内容重新公开。
- 话题（TOPIC）未纳入；`ContentController` 中话题仍直接发布。
- 回退/发布、Git 提交、Plane 同步均未做。

## 6. 需规修订5：检测规范化与多模式匹配（2026-10-20，REVIEW-D17）

用户选择 B2。实现：
- `ReviewText.scan`：NFKC→小写→去掉空白、零宽/格式/控制字符、标点和符号，并记录每个保留字符前是否有被去掉的分隔（供整词判断）。
- `SensitiveMatcher`：Aho-Corasick 多模式匹配（项目内自行实现，**无新增依赖**），每段文字单独扫描，结果与逐条子串匹配一致；规则支持 `SUBSTRING`/`WHOLE_WORD`。`SensitiveRuleService.detect` 改用它，并由 `phrase` 现算规范化词语（不依赖库中旧 `active_key`）。
- 迁移 `V16__sensitive_rule_match_mode.sql`（`match_mode`，默认子串）；规则接口与前端规则页增加“匹配方式”；规范化后为空的词语被拒绝。
- 文档：[SENSITIVE_LEXICON_SAMPLES.md](SENSITIVE_LEXICON_SAMPLES.md)（示例词库、正反例、误伤清单，均有对应测试）；概设 §3.5 备选比较与能力边界表；设计评审 §5 补充评审。
- 偏差：不引入第三方库（候选库版本与许可未核实）；拼音/同义词/图片/外部服务不在范围；示例词库不写入迁移。
- 验证：新增 `SensitiveMatcherTests` 9 项（含与朴素实现的 400 组随机对拍、500 条规则×12 万字性能断言）与 `ContentReviewTests` 2 项；H2 全量 **132/132**；PostgreSQL 新建隔离库 `finns_test_070_review_3`（Flyway 至 V16 + validate）**132/132**；前端 42/42，构建 84 模块。预览 8091 已用新包重启并迁移到 V16，3 个演示待审任务保留。
- 仍未验：已登录浏览器验证（含规则页“匹配方式”界面）、真人手机、真实邮件。

## 7. 需规修订6 / 契约 §13：三级敏感处理、流转时间线、词库导入（2026-10-20，REVIEW-D18–D23，B7–B12）

**状态：已实现并通过自动化验证；浏览器登录态验证未完成（见 7.4），待用户验收。**

### 7.1 实现范围
- 迁移 `V17__review_levels_flows.sql`（只增不改，未发布）：`sensitive_rules.level/tag`；`review_submissions.kind/level/flow_id`（状态增 `UPHELD/OVERTURNED`）；新表 `review_flows`、`review_flow_events`、`review_noted`；`review_audit_log` 类型扩展 `UPHOLD/OVERTURN/NOTED/BATCH_RULES/IMPORT_RULES`。旧任务的 `kind` 默认 `REVIEW`、`level` 默认 `MEDIUM`，没有流转记录，详情页按空流转处理。
- 分级管线（`ReviewService.process`）：无命中/低敏感直接发布（低敏感写 `review_noted` 留痕）；中敏感建 `REVIEW` 任务（通过/驳回）；高敏感自动不通过并建 `RECHECK` 任务（维持/放行，原因必填）。文章、评论、专题首次发布同一管线；取消“累计 3 次转人工”与专题首次必审。多条命中取最高等级。
- 创作者流转：提交→检测→分流→处理→结果，持久化在 `review_flows/_events`；作者侧隐藏处理人姓名（统一“审核员”）与高敏感命中词；预留阶段（创建与草稿、更新发布·撤回）仅为前端占位，未接后端。
- 检测：Aho-Corasick 编译结果按“启用规则指纹”缓存；启用规则上限 20,000（`SensitiveRuleService.MAX_ENABLED`）。
- 词库导入（`LexiconImportService`）：站长上传 UTF-8 `txt`（文件名=标签，每行一词）或 CSV（词语,标签），预览不写库；提交时重传文件并按标签组设置等级/启用；≤2 字符词默认排除；重复跳过；同词跨标签保留最高等级；限制 2MB/20,000 行/40 字符。
- 前端：`/admin/reviews`（分页+页码跳转）、`/admin/reviews/tasks/:id`（“内容与处理 / 流转过程”页签，高敏感复核表单）、`/admin/sensitive-rules`（等级、标签、批量启停、导入弹窗）、`/me/reviews`（作者流转）；发布入口对 202/自动不通过显示持久的行内提示。

### 7.2 与契约 §13 的偏差（实现细节，需回写契约）
- `review_flows`、`review_noted` 带 `title` 列；`review_flow_events` 时间列名为 `occurred_at`。
- 导入不使用“预览令牌”：预览与提交均上传文件（`multipart/form-data`，`config` 为 JSON 字符串），避免服务端保存临时数据。
- 审批队列过滤参数用 `level`（替代修订5的 `trigger`）。

### 7.3 自动化验证
| 检查 | 结果 |
| --- | --- |
| H2 全量 `mvn test`（`FINNS_MAIL_MODE=disabled`） | 138/138（2026-10-21 复跑 141/141：含新增每内容流转与导航改动后的回归；PG 新库未复跑） |
| PostgreSQL 新建隔离库 V1→V17 + validate | 138/138 |
| V16→V17 升级演练（复制库）`ContentReviewTests` | 22/22 |
| `SensitiveRuleScaleTests`（20,000 条启用规则，18 万字） | 首次编译+检测 945ms，缓存命中 38ms |
| 前端 `node --test` / `vite build` | 43/43；构建通过，静态产物已更新 |
| 原型测试 | 53/53 |
| 预览 8091 用新包重启 | Flyway 最新版本 17 `review levels flows`；`/api/stats` 200 |

### 7.4 未验证 / 限制
- **未做已登录浏览器验证**：尝试注册本地测试账号时，使用出信箱验证链接被自动模式拦截，未绕过；预览中仅有站长用自己的账号可登录。桌面/390/768、直达刷新、分页跳转、页签、导入弹窗在生产前端上的实际表现均**未实测**，待用户在 8091 验收。
- 13 个分类词库文件（`data/lexicons/konsheng-sensitive-lexicon-20261020/`，已 git 忽略）未实际经界面导入；后端解析/提交逻辑由测试覆盖。词库来源与许可请站长自行核对。
- 词库不识别同义词、谐音、拼音与图片；启用上限 20,000。
- 预留阶段为占位；Plane REVIEW-SYNC-P03 未写入；未发布、未提交 Git、版本仍为 0.7.0。
- 预览库演示数据：规则“演示低敏词/演示拦截词/演示高敏词”，待办任务 5（中，文章）、6（高，复核）、7（中，评论），及一条低敏感留痕；任务 4 为旧结构遗留，无流转。

### 7.5 用户体验反馈后的导航与页面调整（2026-10-21，同属修订6的前端范围）
- 后台导航改为两级：一级「用户管理 / 内容管理」，二级只列当前分区页面（用户与角色、角色权限、操作记录 / 内容审批、敏感规则）。
- 个人中心“我的内容”合并为一个页面 `/me/posts`，页签：文章与话题、我创建的专题、自选专题；文章与专题每条可展开自己的审核流转；审核中/未通过的文章单列在“已发布”之上。原“发布与流转”“我创建的专题”“自选专题”独立页取消，旧路径 `/me/reviews`、`/me/collections`、`/my/collections` 重定向到新页；顶部主导航“自选专题”保留，直达自选专题页签。
- 评论不放入“我的内容”（用户决定，后期另做“消息与回复”：点赞、评论被回复等）；评论的作者侧流转接口 `GET /api/review/mine?type=COMMENT` 仍保留，暂无界面入口。
- 新增接口：`GET /api/review/mine/content?type=&ids=`（按内容 id 取各自最近 5 次流转）、`GET /api/review/mine/unpublished?type=`（未发布内容最近一次流转）、`/api/review/mine` 增加 `type` 筛选。均只返回本人数据，高敏感不含命中词。
- 验证：`ContentReviewTests` 23/23（新增每内容流转与越权隔离）；前端 44/44（新增 `my-content-ui.test.js`，jsdom 挂载真实页面验证页签与流转展开）；构建通过；8091 已换新包。**仍无已登录浏览器实测**。
