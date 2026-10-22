# FINNS-18 实施记录（G5，进行中）

日期：2026-10-21；依据：[契约修订2](CONTRACT_CONTENT_PUBLISHING.md)、授权B1–B6（PUBLISH-D08/D09）。**状态：B1–B8完成并已终验（自动化范围）；话题代码收敛已做；V19、真实数据盘点与演练、发布未做（见[验收结果](ACCEPTANCE_RESULT_CONTENT.md)）。** 以下只记录实际发生的事，计划与完成分开。

## B1 数据与基础工具（已完成）

| 项 | 实际改动 |
| --- | --- |
| 实体与仓库 | 新增`src/main/java/com/aitome/content/ArticleDraft.java`（JPA，H2靠`ddl-auto`建表）、`ArticleDraftRepository.java`；`applyContent`仅内容变化才加版本，`recordRequest`记录`last_request_*`（含内容哈希列） |
| 公开表 | `ContentPost`新增`updatedAt`（`updated_at`，空=未被作者更新）与`getUpdatedAt()`；`GET /api/posts/{id}`响应会多出`updatedAt:null`（前端B5接入“更新于”） |
| 文本工具 | 新增`com.aitome.common.TextLimits`（码点计数、`blank`含全角空白、码点安全`cut`、`require`抛400`TOO_LONG`含`field/max/actual`）；`ReviewText.cut`改为委托它，不再截断代理对（复评#7） |
| 迁移脚本 | 新增`src/main/resources/db/migration/V18__article_drafts.sql`：草稿表、`updated_at`、`WITHDRAWN`约束、审计类型`DELETE_DRAFT`、回填A/B/C（含在途提交补建草稿）。**未在PostgreSQL实测**（B7未授权），H2不执行Flyway |

## 验证（实际结果）

- 命令：`mvn -s .mvn/settings.xml -q test -Dtest=TextLimitsTests,ReviewTextCutTests,ArticleDraftRepositoryTests`，退出码0；新增10项通过（`TextLimitsTests`4、`ReviewTextCutTests`1、`ArticleDraftRepositoryTests`5，含`post_id`唯一约束违反用例——日志里的`23505`是该用例的预期错误）。
- 回归：`ContentReviewTests`23、`RestrictedContentTests`3、`SensitiveMatcherTests`9、`SensitiveRuleScaleTests`2，共37项通过，退出码0。
- **运行环境注意**：我的shell里有真实SMTP环境变量（`FINNS_MAIL_MODE`、主机、账号、密码等），导致测试上下文因缺`FINNS_PUBLIC_BASE_URL`而无法启动。测试命令在**进程内清除了`FINNS_MAIL_*`**后运行（测试用`RecordingMailSender`，不会发信）；未修改任何系统/用户配置。以后在该shell跑测试须同样清除。测试库为H2内存库`aitome-test`，未触碰`data/aitome`或PostgreSQL。
- 未验：V18在PostgreSQL上的语法与语义、约束名`content_posts_moderation_state_check`、回填五项断言、迁移用时（均属B7）；`ArticleDraft`在PostgreSQL`ddl validate`下与V18的列类型一致性（B7）。

## B2 草稿服务与接口（已完成，2026-10-22）

| 项 | 实际改动 |
| --- | --- |
| 接口 | 新增`ArticleController`：`GET /api/me/articles`（`state/q/page/size`）、`POST`创建（201）、`GET /{id}`、`PUT /{id}`保存；权限分工按PUBLISH-D09——读取`profile.manage`，创建/保存`post.create`；归属取自会话，他人404，写请求受现有CSRF保护 |
| 服务 | 新增`ArticleDraftService`：码点校验（`TextLimits`）、全空`EMPTY_DRAFT`、`VERSION_CONFLICT`（带`currentVersion`）、缺版本`VALIDATION`；保存先`lockById`锁草稿行（锁序第一把）；内容未变不加版本；列表按`R9`语义筛选、`q`搜标题/摘要、`size`≤50；状态推导按契约§4.1与S3（撤回后只显示已撤回+已保存）；`published`/`submitted`/`flows`/`canCancel/canWithdraw/canDelete` |
| 仓库/审核服务 | `ArticleDraftRepository`增`lockById`（悲观写锁）与按作者查询；`ReviewService`增`articleFlows(stateId, limit)`（按最近事件时间倒序，S2） |
| H2列宽 | **实测发现**：H2的`varchar(120)`按UTF-16单元计，120个emoji被拒（500）。为使码点口径在H2成立，JPA实体放宽到240/560：`ArticleDraft`、`ContentPost`（title/summary）、`ReviewFlowEntity`/`ReviewSubmissionEntity`/`ReviewNotedEntity`（title）；PostgreSQL由V18/既有迁移建表，`varchar(n)`按字符计（**文档行为，未实测，B7**） |

## B2 验证（实际结果）

- `mvn -s .mvn/settings.xml -q test -Dtest=ArticleDraftApiTests,ArticleDraftRepositoryTests,ContentReviewTests,RestrictedContentTests,AccessEnforcementTests,AiTomeApplicationTests`，退出码0（进程内已清除`FINNS_MAIL_*`）。`ArticleDraftApiTests`7项（创建/读取/归属/401/CSRF、空稿与码点上限含120/121个emoji边界、版本保存与冲突、撤权作者仍可读不可写、列表筛选搜索分页、状态推导含LIVE/MODIFIED/REVIEWING/RECHECK/REJECTED/WITHDRAWN/DOWN与不泄露命中词、待处理提交阻止删除标记）全部通过；与`ArticleDraftRepositoryTests`5、`ContentReviewTests`23、`RestrictedContentTests`3、`AccessEnforcementTests`9、`AiTomeApplicationTests`12共59项通过。
- 覆盖：CT-A01、CT-A02、CT-A10（后端权限/会话/CSRF/归属）、CT-A13（列表筛选与状态）；仅H2内存库。
- **限制**：状态推导在H2用jdbc夹具构造数据验证，真实“发布/审批”产生的数据待B3端到端验证；列表为按作者全量加载后内存筛选，作者草稿量很大时的性能未评估（G6）；PostgreSQL语义、并发与锁行为未验（B7/G6）。

## B3 发布/取消送审/撤回/删除与审批补强（已完成，2026-10-22）

| 项 | 实际改动 |
| --- | --- |
| 接口 | `ArticleController`新增`POST /{id}/publish`（`post.create`）、`POST /{id}/cancel`、`POST /{id}/withdraw`（须`confirm:true`）、`DELETE /{id}`（均`profile.manage`，PUBLISH-D09） |
| 发布 | `ArticleDraftService.publish`刻意不加事务：①校验（码点上限、三项必填、`requestId`≤64、版本）②回执回放（命中则不保存不检测；**同一`requestId`带不同内容→409`REQUEST_REUSED`**）③`ArticleDraftWriter`（独立Bean、`REQUIRES_NEW`）保存并记录`last_request_id/hash/version`，同请求重试按哈希与版本幂等④`ReviewService.submitArticleDraft`重新锁草稿并核对版本，快照取自锁住的草稿行，先`detect`后建状态（503时草稿已保存） |
| 公开行 | `publishArticle`：无公开行则新建并写回`article_drafts.post_id`；有则条件`UPDATE`（仅`VISIBLE/WITHDRAWN`，`WITHDRAWN`改回`VISIBLE`，写`updated_at`），0行→409`TAKEN_DOWN`；不`save`整个实体 |
| 取消/撤回/删除 | `cancelArticleSubmission`（仅`REVIEW`类，复核任务409`RECHECK_NOT_CANCELLABLE`，写“已取消送审”事件）；`withdrawArticle`（仅`VISIBLE`，使待审失效，事件追加到最近`PUBLISHED`流转，无流转则建合成流转，不补造提交/检测事件）；`deleteArticleTraces`（显式按序删`review_flow_events→flows→submissions→receipts→state`再删草稿，审计`DELETE_DRAFT`、`content_type='DRAFT'`、不含正文） |
| 审批/处置 | `decide`先锁草稿再锁状态（锁序草稿→状态→公开行），ARTICLE分支走`publishArticle`；下架后批准→中止并使提交失效；`manage`对`WITHDRAWN`的`TAKE_DOWN/RESTORE`→409`NOT_PUBLIC`；审批侧`publications`列表排除`WITHDRAWN`、详情404；`withdrawTask`对文章复核任务409 |
| 点赞 | `ContentController.like`改原子`update … set likes=likes+1 where moderation_state='VISIBLE'`，0行404，不再整行回写 |

## B3 验证（实际结果）

- 新增`ArticleLifecycleTests`20项：首次发布/更新同一公开行（发布时间、点赞保留）、低敏感留痕、中敏感冻结快照与审批发布快照、首次送审审批写回草稿、高敏感不可取消且可改稿重提并使旧复核任务失效、取消送审、撤回（二次确认、各公开入口不可见、重新公开重新检测、历史文章合成流转）、人工下架不被作者动作解除与`WITHDRAWN`不可处置、下架后批准被中止、幂等回放与误用、检测不可用时草稿保留与同请求重试、版本冲突、必填与上限、删除（库内无正文、仅元数据审计）、不可删场景、权限分工与归属、点赞原子、两线程并发。
- **全量后端套件**：`mvn -s .mvn/settings.xml test`（进程内清除`FINNS_MAIL_*`）共178项，**177通过、1失败**：`TopicBuilderTests.generatesSnapshotAndChatPreservesAnswers`返回504（该测试用本机127.0.0.1桩服务且`finns.ai.timeout-seconds=1`，全量运行负载下超时）；**单独重跑两次均8/8通过**，判断为计时抖动、与B3无关，但**本次全量并非全绿**，如实记录。其余含`ArticleLifecycleTests`20、`ArticleDraftApiTests`7、`ArticleDraftRepositoryTests`5、`ContentReviewTests`23在内均通过。
- **测试中发现并修复**：同一`requestId`携带不同内容重放时原先返回200，现返回409`REQUEST_REUSED`（对契约§14 R2的补强）；另两处是测试假设错误（作者自己的正文含命中词；内容未变则版本不变，故相同内容并发发布均成功但只有一条公开行）。
- 覆盖：CT-A05–A09、A11（H2两线程）、A12（部分）、A14、A15；仅H2内存库。
- **限制**：PostgreSQL语义、行锁在真实并发下的行为、V18迁移未验（B7）；`PersonalController`/专题收录的可见性、`POST /api/posts`移除与旧测试迁移属B4；前端属B5。

## B4 公开读取与可见性、旧发布接口停用（已完成，2026-10-22）

| 项 | 实际改动 |
| --- | --- |
| 旧接口 | `POST /api/posts`改为返回410`GONE`（权限检查仍先执行，无权限者仍401/403）；`PostRequest`与`ReviewService.submitArticle`移除；话题创建因此关闭，既有话题仍可读（阶段1不改读取） |
| 作者侧 | `ContentPostRepository.findSummariesByAuthorId`只返回`VISIBLE`；`countByAuthorIdAndModerationState`用于个人资料`postCount`（只统计可见内容） |
| 专题 | `PersonalController.addPost`要求被引文章`VISIBLE`（否则400）；`CollectionPublicationService.capture`对不可见文章不再冻结标题摘要（左连接加`moderation_state='VISIBLE'`），公开读取原有占位逻辑不变 |
| 测试迁移 | 新增`TestArticles`（创建草稿→发布→返回公开文章ID）；迁移`AccessAdminTests`、`AccessEnforcementTests`、`UserStatusTests`、`CollectionImportTests`、`AiTomeApplicationTests`（5处造数据）与`ContentReviewTests`的`article()`辅助（保持旧201/202语义，新接口200由`outcome`区分） |
| 偏差 | **专题编辑页保存草稿的校验（`CollectionWorkspaceController.validate`）未加可见性要求**：专题需规修订2允许草稿保留失效引用，若在保存时拦截，已引用撤回文章的专题草稿将无法保存；新增引用由`addPost`拦截，发布期阻断与占位属FINNS-19。契约§3.10原写“专题收录校验要求VISIBLE”，据此收窄为`addPost`+`capture` |

## B4 验证（实际结果）

- 新增`ArticleVisibilityTests`3项（旧接口410且不产生任何内容/话题、作者列表与资料计数只含可见内容、收藏只接受可见文章且快照不冻结不可见文章标题）：通过。
- **全量后端套件**（进程内清除`FINNS_MAIL_*`）181项：首次179通过、2失败，均在迁移后的`ContentReviewTests`，原因是适配器与断言假设而非行为变化：①适配后的响应缺少旧响应里的`title`字段；②响应里含作者自己的正文，“不含命中词”应检查流转与字段而非整段文本。已修复这两处，**修复后只重跑了`ContentReviewTests`、`ArticleVisibilityTests`、`ArticleLifecycleTests`、`ArticleDraftApiTests`四个类（23/3/20/7项全部通过），没有重跑全量**；其余类在首次全量中已通过。
- 覆盖：CT-A12（部分：撤回/下架后不泄露、收录可见性）、CT-A16阶段1（创建入口与接口关闭）；仅H2内存库。
- **限制**：前端仍调用旧接口（发布框得410），B5切换；阶段2（删除既有话题）与专题期校验未做；PostgreSQL语义未验。

## B5 前端（已完成，2026-10-22；浏览器核对属B6，尚未做）

| 项 | 实际 |
| --- | --- |
| 接口封装 | `lib/articles.js`：`articles.list/get/create/save/publish/cancel/withdraw/remove`、码点计数、上限、状态徽标、`errorKind/errorText`（错误码→页面应对与文案集中于此） |
| 安全Markdown | `lib/safeMarkdown.js`（令牌树纯函数，由原型`safe-markdown.js`正式化）+ `components/SafeMarkdown.vue`（文本插值渲染；HTML/脚本显示为文字、图片不加载、不安全链接不可点击） |
| 编辑页 | `pages/ArticleEditorPage.vue`：保存与发布分离；三个版本面板（读者现在看到/已提交/工作稿）与流转；离开三选+`beforeunload`；`VERSION_CONFLICT`保留输入并提供“以我的为准/载入别处版本”；检测不可用（503）后刷新版本、**同一`requestId`**重试；登录失效/换号沿用`useSessionGuard`+`ReauthPanel`，输入原位保留；新稿首次保存后地址换为`/me/posts/:id/edit`（同一组件实例） |
| 预览页 | `pages/ArticlePreviewPage.vue`：工作稿与“读者现在看到”切换，与公开阅读共用`SafeMarkdown` |
| 我的内容 | 文章页签改读`/api/me/articles`；`state`/`q`/`page`在地址里；行内操作编辑/私人预览/取消送审/撤回（确认）/删除草稿（确认）；专题与自选专题页签保持原行为 |
| 路由 | `/me/posts/new`、`/me/posts/:id(\d+)/edit`（`post.create`）、`/me/posts/:id(\d+)/preview`；旧`/me/reviews`重定向保持 |
| 创作入口 | `App.vue`顶部“开始创作”改为选择（写文章/创建专题/AI创建专题，按权限显示）；`ComposerForm.vue`删除（文章直发与话题入口随之下线）；`/?create=post|topic`旧链接进入同一选择 |
| 公开阅读 | `DiscoverPage`详情正文改用`SafeMarkdown`，`updatedAt`非空时显示“更新于”；既有话题仍可读（阶段2才删） |
| 其他 | `FlowTimeline`新增`lit`（文章流转点亮首/尾阶段）并把“取消”事件归为灰色；`assets/content.css` |

## B5 验证（实际结果）

- 前端测试：`node --test tests/*.test.js` **55项全通过**（新增`articles.test.js` 6项；`my-content-ui.test.js`重写为6项：文章页签/筛选在地址、撤回与删除确认、发布建稿+503同`requestId`重试+离开保护、版本冲突、401就地重登保留输入、预览安全渲染）。
- `vite build`通过（输出到临时目录，未改动被跟踪的`src/main/resources/static`）。
- 测试暴露并已修的缺陷：新稿“发布”建稿后地址仍是`/me/posts/new`（刷新会丢草稿并可能重复建稿）→ 首次保存后换地址。
- **限制/未验**：没有浏览器实测（桌面/390/768、直达刷新、前进后退、加载/空/失败态、会话丢失）——属B6；`DiscoverPage`“更新于”与SafeMarkdown仅由构建与组件测试覆盖，未做页面级测试；后端全量测试自B4修复后仍未复跑；PostgreSQL/V18仍未验（B7）。

## B6 验证（2026-10-22，实际结果）

- **后端全量H2**：`mvn -s .mvn/settings.xml test`（进程内清除`FINNS_MAIL_*`）181项，失败0、错误0、跳过0，退出码0。（TopicBuilderTests此次未抖动。）
- **前端**：`node --test tests/*.test.js` 57项全通过；新增2项覆盖直达地址还原筛选、前进/后退、加载态、筛选后为空、失败可重试、文章不存在的失败页。
- **浏览器实测：首次未完成（访问被拒），后经放行已补做，见下方补记。** 我起了带内存mock接口的临时vite服务（配置在临时目录，未改项目文件），但内置浏览器拒绝访问localhost/127.0.0.1，无法打开页面；服务已停止，临时配置已撤回。因此**桌面/390/768宽度的真实布局、真实刷新、会话丢失与冲突横幅的视觉表现均未实测**。jsdom组件测试只覆盖逻辑与结构，不能替代。
- 发现：首次加载时筛选栏随加载态一起隐藏（加载完成后出现），属可接受的小瑕疵，未改。
- 仍未验：PostgreSQL/V18（B7）、话题删除（B8）、DiscoverPage页面级“更新于”。

### B6 补记：浏览器实测（用户放行localhost后，2026-10-22）

方法：带内存mock接口的临时vite服务（临时目录配置、不连数据库、不改项目文件，已停止并撤回`launch.json`临时条目），在内置浏览器里实测。**mock不是真实后端**：冲突/503/会话丢失由mock开关制造，只证明前端行为，不证明服务端。

| 场景 | 宽度 | 结果 |
| --- | --- | --- |
| 列表直达、无横向滚动 | 390 | 通过（scrollWidth=clientWidth） |
| 编辑页直达、两栏改单栏 | 390 / 768 / 桌面 | 通过 |
| 版本冲突：输入保留，出现“以我的为准/载入别处版本” | 390 | 通过；**发现并修复**：这两个`text-link`按钮被渲染成带边框的默认按钮，已在`content.css`加`button.text-link`样式并复测 |
| 登录失效：就地重登面板、三个按钮禁用、输入保留；重登后恢复 | 390 | 通过（mock登录接受任意值，未用真实凭据） |
| 发布遇检测不可用（503）→ 提示“输入已保留” → 再点发布成功（公开版与工作稿版本号更新） | 390 | 通过（同requestId由单元测试证明，此处未抓包） |
| 预览：脚本显示为文字、无img/script、坏链接不可点、图片占位 | 768 | 通过 |
| 列表：失败可重试、清空筛选、空态、前进/后退恢复筛选、撤回确认对话框与Esc关闭 | 768 | 通过 |
| 创作入口选择（写文章/创建专题/AI创建专题） | 768 | 通过 |
| 新稿缺正文发布→提示补全；补全后发布→地址换成`/me/posts/:id/edit`；刷新仍回到该草稿 | 768 | 通过 |
| 有未保存修改点“返回列表”→三选对话框，放弃修改后离开 | 768 | 通过 |

仍未验：真实后端联调（前后端同时运行）、`DiscoverPage`的“更新于”与公开阅读渲染、`beforeunload`原生提示、键盘/读屏可达性、真实浏览器刷新时的会话丢失。

## B7 隔离PostgreSQL演练（2026-10-22，PUBLISH-D10授权范围内）

**隔离方式**：未启动项目自带的`data/postgres`集群（含真实`finns`库）；在临时目录（E盘）另起全新PostgreSQL 17.11实例，端口54329、仅回环，只建`finns_test_v18`、`finns_restore_check_v18`、`finns_test_full`、`finns_test_full2`，属主`finns_app`。演练结束后实例已停止。`data/postgres/postmaster.pid`是10-21遗留的旧文件（无对应进程、5432无监听），非本次产生，未触碰。

| 步骤 | 结果 |
| --- | --- |
| 旧包（`finns-0.7.0.jar`，仅到V17）在空库迁移 | V1–V17共17个迁移成功（含Java迁移V7），首次在真实PostgreSQL上验证 |
| 灌入虚构V17形态数据（[seed-v17.sql](rehearsal/seed-v17.sql)）并`pg_dump`备份 | 成功（备份演练） |
| 当前代码启动，Flyway应用V18；Hibernate `ddl-auto: validate` | V18成功，0.26s；应用正常启动，实体与表结构一致 |
| 回填核对（[verify-v18.sql](rehearsal/verify-v18.sql)，14项） | 13项通过；第14项“无旧键残留”是**断言写错**——全空快照与无提交的空状态按设计保留旧键（本演练恰好2个），其余设计行为均符合：已发布/已下架/无状态老文章各建草稿，状态键改`ARTICLE#<草稿id>`，在途快照补建草稿（缺title也建），全空快照与无提交状态跳过，超长快照截到120/280码点，评论状态与话题行不变，下架状态保持，`legacy_state_id`已删，约束放宽含`WITHDRAWN`/`DELETE_DRAFT` |
| 迁移后`pg_dump`→`pg_restore`到`finns_restore_check_v18` | 草稿、状态键、文章的行数与MD5校验和、Flyway历史与源库一致 |
| 契约假设 | V15/V17内联CHECK的默认约束名与契约一致 |
| 全量后端测试（`scripts/test-postgres.ps1`） | 首次181项：25错误，**全为测试夹具外键顺序**（H2无外键）——`ContentReviewTests`清理先删文章未删草稿（21）、`ArticleDraftRepositoryTests`用不存在的作者id（4）；已改测试夹具，非生产代码。重跑（脏库）剩2项`AiTomeApplicationTests`按全库总数断言失败；换**干净库**重跑 **181项全过（0失败0错误）** |
| H2全量（改测试后） | 181项全过（0失败0错误） |

**限制/未验**：数据是虚构的小样本——真实数据量下的迁移耗时、锁与表重写、真实历史数据的边角（如旧键残留）未验；高并发下行锁/连接池占用（契约S4）未测；那2项总数断言失败我判断为脏库所致，只靠一次干净运行支持，未查根因。**上线前盘点须加：迁移后`ARTICLE`类状态中旧键（不以`ARTICLE#`开头）应为0**，且需用真实备份的副本再演练一次（属B8/发布前，需你授权）。V19（话题删除）未做。

## B8 话题删除准备（2026-10-22；只准备与隔离演练，**未对主库或任何真实数据执行**）

产出（均在[`topic-removal/`](topic-removal/)，**不在Flyway目录下，不会被任何启动流程执行**）：[INVENTORY_TOPICS.sql](topic-removal/INVENTORY_TOPICS.sql)（只读盘点，`begin read only`+`rollback`）、[V19__remove_topics.sql.draft](topic-removal/V19__remove_topics.sql.draft)（删除脚本草稿）、[rehearsal-seed-topics.sql](topic-removal/rehearsal-seed-topics.sql)（演练用虚构数据）。

- **盘点覆盖**：话题数/作者数/可见性/点赞合计；话题下评论数；评论审核留痕（状态、noted、待审任务）；被专题引用（`collection_entries`、旧`collection_posts`、已发布专题数）；已发布专题冻结快照提及话题的数量；作者清单（真实内容提示）；指向话题的草稿应为0；文章旧键残留应为0（承接B7）。
- **V19草稿**：开头DO块把实际数量与**已确认清单的数字**比对，任何不符→抛错、整事务回滚、什么都不删（占位符`__EXPECTED_*__`未替换时同样在首步报错）；随后按顺序清`review_noted`与评论的`review_content_state`（同时按`target_id`与`COMMENT:<话题id>:%`前缀匹配，因为键有80字符截断），再删话题（评论、旧`collection_posts`级联；`collection_entries.post_id`置空；提交/流转/事件/回执靠外键级联），最后事后断言；审计日志只留元数据。
- **隔离演练**（`finns_test_v19`，由B7迁移后备份恢复而来，PostgreSQL 17）：盘点输出与种子一致（话题4、评论2、评论状态2、noted 1、待审任务1、专题引用1、旧collection_posts 1、快照提及1）。①占位符未替换→报错无变化；②错误数字（话题=9）→“V19中止”，各表计数前后完全相同；③确认数字（4/2/2）→0.5s完成：话题0；话题评论、其评论状态、提交、回执、noted被清除；**文章及其下评论、评论状态、noted、草稿（6）、审计日志保持不变**；专题条目保留、`post_id`置空（`POST`类条目允许空`post_id`，CHECK通过）；旧`collection_posts`行被删。
- **快照（已更正）**：冻结快照（`collection_publications.document`）确实仍含被删话题的`postId/postTitle`，但`CollectionPublicationService.read`读取时已把不再可见/已删除的引用遮成“原文已不可用”，读者看不到旧标题，**V19不需要改写快照**（我早先“公开页会显示旧标题”的担心不成立）；新增测试`aPublicCollectionNeverShowsTheFrozenTitleOfAPostThatWasDeleted`固定该行为。
- **后续已做（见下“话题代码收敛与终验”）**：代码收敛。**仍未做**：真实备份副本上的盘点与演练；用户对不可逆删除的逐类确认与发布授权。

## 话题代码收敛与终验（2026-10-22；用户授权“按你的选择做完所有行为”后执行）

- **代码收敛**：`ContentPost.Type`只剩`ARTICLE`；`DemoData`的话题样例改为文章；`Permission`发布说明去掉“话题”；发现页去掉“话题”标签页，卡片/详情标签统一为“文章”；`?type=TOPIC`不再可用（非法枚举）。V19仍只是草稿，**含历史话题的数据库须先由V19清理（PostgreSQL）**，H2开发库不支持（含历史话题的H2库须迁移或重建）。
- **测试改动**：`AiTomeApplicationTests`把话题造数据改为文章（分页用例的测试行排到十年后，避免与其他测试遗留文章混排）；`ArticleVisibilityTests`的非文章计数改为`type<>'ARTICLE'`；新增4个用例：被删文章在公开专题读取时不展示冻结标题、停用账号全部401且恢复后下架仍在、被维持不通过的复核任务取消仍409、删除与发布并发不留孤儿公开行。
- **终验结果**：H2全量184项、PostgreSQL 17全量184项均0失败（`finns_test_final`干净库）；之后新增的并发用例在H2与PostgreSQL上均通过，未再跑全量；前端57项、构建通过；发现页详情“更新于”与安全渲染在浏览器（mock接口）实测通过。
- **验收**：逐项结果与未验清单见[ACCEPTANCE_RESULT_CONTENT.md](ACCEPTANCE_RESULT_CONTENT.md)。
- **环境**：隔离PostgreSQL实例（临时目录、端口54329）已停止；`.claude/launch.json`临时条目已撤回；未触碰主库与`data/postgres`；未提交Git、未改版本号、未部署。

## 下一步

开发与自动化验证已完成。发布前必做（需你授权/提供，我没有做）：真实备份副本上的盘点与V18/V19演练、对不可逆删除逐类确认、真人手机与真实联调、四处版本号与发布条目、部署及部署后检查（详见[验收结果](ACCEPTANCE_RESULT_CONTENT.md)“未验”）。Git未提交；主库未触碰。
