# FINNS-18 概要设计：文章草稿、发布与生命周期（修订4，D1–D5已决定，独立评审及复评条件已折入）

日期：2026-10-21；输入：[需规修订2–4](../../requirements/FOUNDATION_CONTENT_REQUIREMENTS.md)（G1通过）、[验收CT-A01–16](ACCEPTANCE_CONTENT.md)、[原型R1](PROTOTYPE_CONTENT_R1.md)（PUBLISH-D05确认）、[决定与遗留项](../../requirements/PUBLISHING_DECISIONS.md)、当前源码（`release/0.7.0`，HEAD `c0119db`）。**状态：修订4——D1–D5已决定（PUBLISH-D06/D07）；独立评审（退回）与复评（带条件通过）的发现已折入§14、§14.3；评审见[DESIGN_REVIEW_CONTENT_PUBLISHING.md](DESIGN_REVIEW_CONTENT_PUBLISHING.md)。设计层面的条件均已关闭，G3通过；实现、迁移与契约属G4及之后。** 本文不含实现、迁移执行或发布授权；详设/接口/迁移脚本在G4。

## 1. 目标与范围

只做**文章**（话题整体移除，方案C，见§8）：服务端私人草稿、手动保存、发布/更新（本次请求内检测）、三个版本（工作稿/冻结提交版/公开版）、取消送审、撤回与重新公开、人工下架独立恢复、删除从未公开的私人草稿、安全Markdown预览与阅读、合并“我的内容”文章页签、会话/并发保护。复用F08修订6的三级检测与流转，不新增审批政策。**不含**：传统专题（FINNS-19）、评论、媒体、自动保存、邮件/消息中心。

## 2. 现状核对（均已对照源码）

| # | 事实 | 位置 | 对设计的含义 |
| --- | --- | --- | --- |
| 1 | 文章只在**发布时**才产生`content_posts`行；没有服务端文章草稿。幂等/流转键是客户端随机`draftKey`。 | `ReviewService.submitArticle`、`ContentController.create` | 需要新的持久草稿实体与稳定ID。 |
| 2 | 已发布文章再次提交被拒（`ALREADY_PUBLISHED`），审批通过的发布分支只会**新建**`ContentPost`。 | `ReviewService.process`（`state.targetId()!=null`分支）、`decide`（ARTICLE分支） | 必须支持“更新同一公开行”，否则改稿会产生第二篇文章。 |
| 3 | 话题分支直接`posts.save`并公开，不经检测。 | `ContentController.create` | 话题移除前该缺口存在；移除后消失（§8）。 |
| 4 | 公开读取一致过滤`moderation_state='VISIBLE'`：列表、详情、评论、点赞、专题引用。 | `ContentPostRepository`、`ContentController.requirePost`、`CollectionPublicationService` | 新增`WITHDRAWN`状态后这些读取**自动**不再可见，无需逐处改。 |
| 5 | 例外（独立评审补全）：作者自己的列表`findSummariesByAuthorId`无VISIBLE过滤；专题编辑校验`CollectionWorkspaceController:263`只检查作者不检查可见；发布捕获`CollectionPublicationService:27`会把非可见文章的标题摘要冻结进快照；审批侧列表/详情`ReviewService:367/709/733`未排除`WITHDRAWN`；`PersonalController:138`同样不过滤。 | `PersonalController`、`CollectionWorkspaceController.validate` | 个人列表改由新草稿接口提供；专题引用已撤回文章时发布读取已有占位过滤，仍需契约确认。 |
| 6 | `moderation_state`有CHECK约束，仅`VISIBLE/TAKEN_DOWN`。 | `V15__content_review.sql` | 增加`WITHDRAWN`需改约束。 |
| 7 | 作者撤回待审任务已存在（`withdrawTask`）。 | `ReviewService.withdrawTask` | “取消送审”复用，不新写。 |
| 8 | `@Size`按UTF-16代码单元计数；DB `varchar(n)`按字符计。 | `PostRequest`、`V1__baseline.sql` | 字符计量需自定义校验（见D3）。 |
| 9 | SPA回退已覆盖`/me/**`。 | `SpaController` | `/me/posts/new|:id/edit|:id/preview`无需改回退。 |
| 10 | 已有“不支持回退旧包”的先例。 | 交接 REVIEW-D16-U3（V15–V17） | V18沿用同一口径，不另承诺回退。 |

## 3. 总体方案与取舍

**方案A（采用）：新增`article_drafts`作为作者侧稳定实体，`content_posts`仍只存公开版。** 草稿行持久保存工作稿；发布/更新时把当前输入冻结为`review_submissions.snapshot_json`（已有机制），通过则**更新**草稿关联的同一`content_posts`行；公开读取链路零改动。复用`review_content_state`（键改为稳定的`ARTICLE:<draftId>`）、`review_receipts`（请求幂等）、flows/tasks/audit。

被否决的备选：
- **B 把草稿塞进`content_posts`（加`DRAFT`状态）**：所有公开查询、计数、专题引用、统计都要加过滤，漏一处即泄露草稿；风险高于收益。
- **C 草稿只存浏览器/localStorage**：违反“刷新/换设备恢复、跨账号保护”（CT-A01/A10），且服务端无冻结快照来源。
- **D 每次保存都生成不可变版本**：需求只要求“工作稿+提交快照+公开版”，不要求历史回滚（非目标）；会放大存储与读取复杂度。

## 4. 数据模型（迁移V18，草案；脚本在G4）

| 对象 | 变更 | 说明 |
| --- | --- | --- |
| `article_drafts` | 新表：`id`、`author_id`（FK 用户，随用户删除）、`post_id`（可空、唯一，发布后关联`content_posts`）、`title varchar(120)`、`summary varchar(280)`、`body text`、`version`、`created_at/updated_at`；索引`(author_id,updated_at desc,id desc)` | 全空稿由服务拒绝创建；上限按字符（码点）校验。 |
| `content_posts` | `moderation_state`约束加`WITHDRAWN`；类型收敛见§8（历史话题删除后`type`实际只剩`ARTICLE`） | 无`body_format`列：所有文章一律按安全Markdown渲染（D2）。 |
| `review_content_state` | 文章键由随机`draftKey`改为`ARTICLE:<draftId>`；历史文章回填 | 唯一键`(author,type,key)`不变。 |
| 历史文章回填（D5） | 为每篇既有`ARTICLE`建一条`article_drafts`（`post_id`关联、内容拷贝、版本1），并把对应`review_content_state`键改为稳定键（无则补`legacy=true,approved_once=true`行） | 使历史文章可改稿；幂等写法；**真实数据，迁移前备份并演练恢复**。 |
| `review_audit_log` | 类型约束增加`DELETE_DRAFT` | D4选物理删除：仅元数据，无正文。 |

不新增外部服务或依赖。

## 5. 状态模型

公开状态与工作状态分开（与原型一致）：
- 公开状态来自`content_posts.moderation_state`与是否存在行：`未公开`（无关联行）、`已发布`（VISIBLE）、`已撤回`（WITHDRAWN）、`已下架`（TAKEN_DOWN）。
- 工作状态来自草稿与流转：`私人草稿/已保存`、`有改动未发布`（草稿与公开版内容不同且无待办）、`待审核`（PENDING REVIEW任务）、`未通过·复核中`（PENDING RECHECK）、`未通过`（最近流转被驳回/维持不通过）。
- 规则：人工下架只能由另一位审批者在`manage(RESTORE)`恢复；保存/更新/撤回/账号恢复**不得**改动`TAKEN_DOWN`；`RESTORE`只作用于`TAKEN_DOWN`，不能恢复`WITHDRAWN`；作者重新发布是对`WITHDRAWN`的再次检测发布。
- **取消送审只对`kind=REVIEW`的待办任务有效**：对`RECHECK`（高敏感人工复核）一律409“不能取消，可改稿重提”；取消成功须写流转事件“已取消送审”并把该流转置为失效（现有`withdrawTask`只作废任务，**没有**类型保护，也未写这条作者可见事件，需补，评审发现#1）。
- **审批侧的处置只作用于`VISIBLE/TAKEN_DOWN`**：对`WITHDRAWN`的`TAKE_DOWN`与`RESTORE`都返回409（现有`manage`仅靠“`RESTORE`⇔已下架”判断，对`WITHDRAWN`执行`TAKE_DOWN`会通过校验并把它改成`TAKEN_DOWN`，评审发现#2）；审批侧“已发布内容”列表不展示`WITHDRAWN`。

## 6. 接口（路径与职责；DTO/错误码在G4）

均需登录，身份取自服务端会话，所有写请求受现有CSRF保护；他人资源一律404；停用/失权按现有`authorActive`与权限检查最终复核。

| 接口 | 作用 | 要点 |
| --- | --- | --- |
| `GET /api/me/articles?state=&q=&page=&size=` | 我的文章（草稿+公开状态+最近流转摘要） | 分页；替代`/api/me/posts`文章部分。 |
| `POST /api/me/articles` | 创建私人草稿 | 全空拒绝；返回稳定id、version。 |
| `GET/PUT /api/me/articles/{id}` | 读取/手动保存工作稿 | `expectedVersion`乐观锁；冲突409，输入由前端保留。 |
| `POST /api/me/articles/{id}/publish` | 校验→持久保存当前输入→冻结快照→检测分流 | `requestId`幂等；保存先提交，检测异常（503）不回滚已保存草稿；已下架409。 |
| `POST /api/me/articles/{id}/cancel` | 取消本次送审 | 复用`withdrawTask`；高敏感复核任务不可取消。 |
| `POST /api/me/articles/{id}/withdraw` | 撤回公开 | 需`confirm:true`；使待审提交失效；`WITHDRAWN`。 |
| `DELETE /api/me/articles/{id}` | 删除从未公开的私人草稿 | 公开过/有待处理提交拒绝；存储语义见D4。 |
| 既有`GET /api/posts*` | 公开读取 | 响应结构不变；正文由前端按Markdown渲染。 |

事务与顺序：**保存先于检测**——检测不可用时（现有`rules.detect`在建立状态前抛503）草稿已持久，旧公开版不变；发布与审批通过的“更新分支”在同一事务内更新`content_posts`并写流转事件。审批通过路径（`decide`）同样改为“有`targetId`则更新”。

## 7. 前端

- **路由**：沿用真实入口`/me/posts`（页签 文章/我创建的专题/自选专题），新增`/me/posts/new`、`/me/posts/:id/edit`、`/me/posts/:id/preview`；旧`/me/reviews`重定向保持。`开始创作`改为选择入口（写文章/创建专题/AI创建专题），不再直接发布。
- **页面**：`MyContentPage`文章页签改用草稿接口（搜索、状态筛选、分页、行内操作与确认）；新增文章编辑页与私人预览页（原型R1已验证版式）。
- **Markdown**：服务端存原文，**客户端令牌树渲染**（原型`safe-markdown.js`的正式化）：HTML/脚本只显示为文字、图片不加载、不安全链接不生成链接；预览与公开阅读共用同一渲染组件；历史文章同样按Markdown渲染（D2：全部按Markdown，旧纯文本里的`#`、`-`、`>`等符号外观会变）。否决：引入`marked/markdown-it`+净化库（新依赖与净化面）、服务端生成HTML（XSS面）。
- **保护**：离开三选、`beforeunload`、保存失败不离开；复用已有`useSessionGuard`处理登录失效/换号（输入原位保留、不代存代发）。

## 8. 话题整体移除（方案C）

目标状态：产品无“话题”类型；创建入口与接口、发现页标签/展示、统计一并移除。`ContentPost.Type`收敛为`ARTICLE`、`DemoData`话题样例改为文章。**用户已选择方案3“删除”（D1，PUBLISH-D06）**，三案比较保留如下：

| 方案 | 做法 | 能力边界 / 风险 |
| --- | --- | --- |
| 1 转换为文章（原推荐） | 话题`type`改`ARTICLE`并建稿 | 数据零丢失；标签由“话题”变“文章”。 |
| 2 下线隐藏 | 新状态隐藏，数据保留 | 可恢复；评论点赞随之不可见。 |
| **3 删除（用户选定）** | 物理删除话题；`comments.post_id`为`on delete cascade`，评论随帖删除；点赞是帖内计数列一并消失 | **不可逆**，丢评论；专题引用要先处理。 |

选定方案的设计要求（不可省）：
1. **先清单后删除**：在备份副本上统计话题数、评论数、涉及作者、被哪些专题（`collection_entries.post_id`、旧`collection_posts`）引用，交用户逐类确认；清单含非演示的真实用户内容时再次提示，允许改选转换/下线。
2. **引用的结果（已更正）**：`collection_entries.post_id`为`on delete set null`，V8已放宽CHECK（`kind='POST'`行允许`post_id`为空），所以删除不会因约束失败（修订2曾误引V6，独立评审更正）。删除后这些条目成为“引用不可用”的POST条目：专题草稿按专题需规修订2保留失效引用、发布时阻断并定位，已发布专题统一占位，口径一致；清单中须列出受影响专题与作者。旧`collection_posts`为`on delete cascade`。
3. **备份与恢复演练**前置（隔离库`finns_restore_check_*`）；执行只在用户发布授权下，且不在开发/测试阶段触碰主库。
4. 同步清理：`DemoData`的话题样例（唯一含`TOPIC`字面量的后端配置）、`DiscoverPage`话题标签、`ContentController`话题分支、前端创作入口。`H2ToPostgresMigration`只通用拷贝`type`列、没有`TOPIC`字面量（修订2曾误列，已更正）。
5. 删除后读取：话题旧链接返回“内容不存在”；统计口径不再含话题。

## 9. 兼容、迁移与回退

- V18全部为附加/回填；执行前**必须**备份并在隔离库演练（沿用`scripts/test-postgres.ps1`的`finns_test_*`/`finns_restore_check_*`规则）；回填SQL须可重复执行。
- 回退：沿用“不支持回退旧包”口径（先例REVIEW-D16-U3）。旧包读取仍只认`VISIBLE`，不会让`WITHDRAWN`重新公开；但旧包不认识草稿表，回退期间产生的新文章不会有草稿——发布说明里明示。**回退行为本轮未验。**
- 旧API兼容：`POST /api/posts`文章分支保留一个版本周期还是直接移除，G4决定（前端同步切换，不对外承诺第三方客户端）。

## 10. 能力边界表

| 场景 | 结果 | 属于 | 处理/接受理由 |
| --- | --- | --- | --- |
| 在两个窗口同时改同一草稿 | 后保存者收到409，输入保留，可载入别处版本或覆盖 | 能做到 | 乐观锁；不做三方合并 |
| 发布时检测服务不可用/无启用规则 | 草稿已保存，不发布，旧公开版不变，可重试 | 能做到 | 与F08一致：规则为0时全部发布被拒，上线前需先启用规则 |
| 重复点击发布/网络重试 | 同`requestId`返回同一结果，不重复发布 | 能做到 | `review_receipts`；换`requestId`的重复提交会使旧待审任务失效 |
| 作者撤回后又改稿 | 公开处不可见；重发需重新检测，旧审核结论不恢复公开 | 能做到 | — |
| 人工下架后作者保存/重提/撤回 | 保存可，发布与撤回409，下架不变 | 能做到 | 只能另一位审批者恢复 |
| Markdown里写`<script>`、外链图片、`javascript:`链接 | 显示为文字/占位，不执行、不外连 | 能做到（渲染层） | 令牌树渲染；服务端不生成HTML。**不检测**内容语义是否违规，那是敏感规则的职责 |
| 含emoji/生僻字的长度 | 按Unicode码点计（D3），一个emoji算1，与浏览器`[...s]`、PostgreSQL字符计数一致；前后端边界测试验证（Java侧用`codePointCount`，不用`@Size`） | 能做到（D3已定） | 组合字符（如带肤色的家庭emoji）仍可能算多个码点，属已知口径 |
| 历史文章里恰好有`# `、`- `、`>`等 | 全部按Markdown渲染，外观会变（标题/列表/引用）；脚本与HTML仍只显示为文字 | 能做到（D2已定，**有外观变化**） | 用户已选择；迁移前抽样若发现明显错乱，发布说明提示 |
| 删除草稿后想恢复 | 不能；数据库备份可恢复但不属产品功能 | 做不到（按需求“无法恢复”） | D4决定是否保留元数据 |
| 几千篇历史文章迁移 | 一次性回填建稿，用时随文章数线性增长；未实测 | 未验 | G4用隔离库按真实量级演练并记录耗时 |
| 删除历史话题 | 话题、评论、点赞物理删除，不可逆；被专题引用处占位 | 做不到恢复（除备份） | 用户已选择；清单确认+备份恢复演练+发布授权为前置 |
| 回退到旧包 | 不支持（见§9） | 做不到 | 沿用既定口径，发布前备份 |

## 11. 已决定（PUBLISH-D06，2026-10-21）

| 编号 | 决定 | 用户选择 | 与推荐的差异 |
| --- | --- | --- | --- |
| D1 | 历史话题处置 | **删除** | 与推荐（转换）不同：不可逆、丢评论，已加“清单确认/备份演练/授权”前置 |
| D2 | 旧文章正文显示 | **全部按Markdown** | 与推荐（保持纯文本）不同：旧文外观会变，已不设`body_format` |
| D3 | 字数计量 | Unicode码点 | 同推荐 |
| D4 | 删除草稿留存 | 物理删除 | 同推荐 |
| D5 | 历史文章改稿 | 迁移时自动建稿 | 同推荐 |
| — | 独立评审 | 授权 | 已启动，结果记入评审记录 |
| — | 是否拆分 | **不拆分**，同版交付 | 与推荐（拆两批）不同：范围与风险集中在0.7.0，评审标注为风险 |

## 12. 需求与验收映射

| 验收 | 设计落点 |
| --- | --- |
| CT-A01/A02 | `article_drafts`+草稿接口（§4/§6）；全空拒绝、必填/上限校验（D3） |
| CT-A03/A04 | 前端离开保护、`beforeunload`、令牌树渲染（§7） |
| CT-A05 | `publish`：先保存后检测，三级分流复用`process`；幂等回执（§6） |
| CT-A06/A07 | 冻结快照`review_submissions`；公开行只在通过后更新；`cancel`复用`withdrawTask`（§5/§6） |
| CT-A08/A09/A12 | `WITHDRAWN`状态、`TAKEN_DOWN`独立恢复；读取链路`VISIBLE`过滤（§2#4/§5） |
| CT-A10/A11 | 会话/CSRF/所有权/乐观锁/行锁与回执（§6/§10） |
| CT-A13 | “我的内容”文章页签与流转（§7） |
| CT-A14/A15 | `DELETE`接口与D4（§6/§11） |
| CT-A16 | 话题移除与历史处置（§8/D1） |

## 13. 遗留项交付核对

见评审记录“遗留项核对”表：CT-L01已交付（原型R1）；CT-L02计量口径与旧文渲染已按D3/D2定稿；CT-L03骨架在§4–§6，DTO与测试映射在G4；CT-L04在D4；CT-L05在D1/§8。

## 14. 修订3：独立评审发现的处置（2026-10-21）

独立评审（只读子代理，结论“退回，小范围修订后复评”）的发现经我逐条用源码核实后折入。成立的写在这里；不成立或需更正的已在§2/§8就地改正。

| # | 发现（核实结论） | 设计处置 |
| --- | --- | --- |
| 1 | **删除后正文仍在**（成立）：`review_submissions.snapshot_json`存完整正文，`review_flows.title`存标题；`unpublishedFlows`按`target_id is null`取流转会让已删草稿的流转继续露出 | 删除=在同一事务内`delete from review_content_state`（`review_submissions`、`review_flows`、`review_flow_events`均为`on delete cascade`，V15/V17已核对）+ 删草稿行；`review_audit_log`只保留不含正文的元数据（新增类型`DELETE_DRAFT`）；删除后`unpublishedFlows`自然取不到。契约须断言：删除后库内无该稿正文（G4测试）。 |
| 2 | **V18回填漏在途提交**（成立）：待审/自动不通过/被驳回的旧文章提交，状态键是随机`draftKey`且`target_id`为空 | 用户已选“按快照补建草稿”（PUBLISH-D07）：回填对每个`type=ARTICLE`且`target_id is null`的`review_content_state`，用其**最新提交的快照**建`article_drafts`（`version=snapshot_version`、`post_id`空），并把状态键改为`ARTICLE:<draftId>`；旧待审任务保持`PENDING`并仍属该状态，审批通过时走“有草稿则更新/无公开行则首次发布”。键改写用**相关子查询**，不用`UPDATE…FROM`（H2不通用，且生产为PostgreSQL，脚本仅在隔离PostgreSQL验证）。畸形快照应为0，演练须检查。 |
| 3 | **点赞并发回写会覆盖状态**（成立，已有缺陷）：`like()`先加载实体再`likes++`，`ContentPost`无`@DynamicUpdate/@Version`，提交时整行回写，可能把并发撤回/下架/改稿写回的`moderation_state`或正文覆盖 | 点赞改为原子SQL：`update content_posts set likes=likes+1 where id=? and moderation_state='VISIBLE'`（0行则404）；文章更新、撤回、下架/恢复一律用**带条件的定向SQL更新**，不再`save`整个实体；同步改`ContentPost`加受控更新方法。这是CT-A11的前置修复，纳入G4/G5。 |
| 4 | **话题删除的悬空数据与部署顺序**（成立）：话题下评论经`submitComment`产生`review_content_state/flows/noted`与可能的待审评论任务；`ContentPost.Type`先收敛而TOPIC行仍在会让读取失败 | ①清单步骤同时统计话题评论的审核留痕/待审任务；删除话题时**一并清除**这些评论的`review_content_state`（键`COMMENT:<topicId>:*`，级联带走提交与流转）和`review_noted`（`content_type='COMMENT'`且`content_id`属被删评论），仅保留审计日志元数据——与D1/D4的“删除”一致；②**两阶段发布**：阶段1（不改数据）**仅关闭话题创建**（前端入口与`POST /api/posts`的话题分支），既有话题仍照常可读、可评论、可点赞，`ContentPost.Type`保留`TOPIC`（复评后简化，见§14.3#3）；阶段2（仅在用户发布授权后）单独的`V19`删除话题数据并同单元收敛枚举/移除代码。`V19`作为独立脚本、仅在授权发布的版本加入，使文章生命周期不被迁移演练阻塞（降低评审R3风险）。 |
| 5 | **CT-A13事件映射缺落点**（成立） | 不新建事件表：①提交与处理沿用`review_flows`+`review_flow_events`；②**取消送审**=该流转置`INVALID`并追加`RESULT`事件“已取消送审”；③**撤回/重新公开**=在最近一条流转追加`RESULT`事件“作者撤回/已更新发布”，不新增流转状态（`review_flows.status`约束不变）；④“创建/最近保存”取自`article_drafts.created_at/updated_at`展示，**手动保存不追加事件**（需求：不是每次保存都追加记录）；⑤删除草稿无展示（已删除）。时间线“创建与草稿”阶段用草稿字段点亮。 |
| 6 | **阻止收录未覆盖**（成立） | ①`CollectionWorkspaceController`的POST条目校验要求被引用文章`moderation_state='VISIBLE'`且作者一致，否则400“该文章未公开，不能收录”；②`CollectionPublicationService.capture`只冻结`VISIBLE`文章的标题摘要，非可见条目捕获为“不可用”占位；③`PersonalController`作者列表改用新草稿接口；④审批侧列表/详情/`publishedView`排除`WITHDRAWN`。已存在于专题草稿里的撤回/下架文章引用按专题修订2规则显示失效引用。 |
| 7 | **`ReviewText.cut`按UTF-16截断**（成立）：`value.substring(0,max)`可能截断代理对 | `cut`改为按码点截断（`offsetByCodePoints`）；与D3同批实现；边界测试含代理对。 |
| 8 | **实现约束**（成立）：默认H2 profile关闭Flyway、`ddl-auto: update`；`ContentPost`无setter；`decide`更新分支需锁内复核状态 | `article_drafts`同时提供JPA实体（H2开发/测试自动建表）与V18 SQL（PostgreSQL），G4须证明两者结构一致；PostgreSQL专有的CHECK/迁移只在隔离PostgreSQL验证，H2通过**不代表**迁移通过；`decide`更新分支在状态行锁内先确认公开行`moderation_state`，把`WITHDRAWN`改回`VISIBLE`，`TAKEN_DOWN`则中止。 |
| 9 | **保存先于检测在Spring下是否可行**（未写明，成立） | 发布入口所在控制器**不加事务**；`saveDraft`（独立事务，提交后返回）与`submitArticle`分属**不同Bean**，避免同类自调用绕过代理；检测异常发生在第二个事务内、建任何流转状态之前，已保存草稿不受影响。契约测试用“检测不可用”夹具断言草稿已持久。 |
| 10 | **改稿后的展示语义**（需用户决定，已决：PUBLISH-D07） | 保留原发布时间与列表排序（`createdAt`不变），新增`content_posts.updated_at`，详情页显示“更新于”，列表不变；旧评论与点赞保留在新版本上。 |

新增的数据变更：`content_posts.updated_at timestamp null`；`review_audit_log`类型约束加`DELETE_DRAFT`；V18回填含在途提交补建草稿；`V19`（阶段2，授权后）。

### 14.1 能力边界补充

| 场景 | 结果 | 属于 | 处理 |
| --- | --- | --- | --- |
| 点赞与撤回/下架并发 | 点赞只在`VISIBLE`时累加，不会把状态写回 | 能做到（修复后） | 原子SQL；修复前存在整行回写覆盖风险 |
| 已删草稿的流转/正文 | 库内不再有该稿正文与流转 | 能做到（级联删除） | 契约测试断言；审计日志仅元数据 |
| 迁移时在途提交 | 补建草稿，任务保持待办 | 能做到 | 演练检查畸形快照数为0 |
| 阶段1后、阶段2前的话题 | 不能新建；既有话题仍可读/评论/点赞，读取侧无变化 | 能做到 | 话题“对读者消失”只发生在阶段2；阶段1无数据变更，回退即恢复创建入口 |
| 作者把已撤回/下架文章收录进专题 | 400拒绝 | 能做到 | 校验要求`VISIBLE` |

### 14.2 遗留项与评审状态

CT-L03事件映射已在#5交付；CT-L04在#1闭合与实现矛盾；CT-L05在#4补全。待独立评审者对修订部分复评。

## 14.3 复评条件的关闭（2026-10-21）

独立评审者对修订3复评，结论**带条件通过**、无阻断项。以下重要项/建议已写入设计（我对其中涉及源码的断言做了核实：JPA实体`ReviewSubmissionEntity`等用普通`Long state_id`、无外键与级联，故H2开发/测试库没有级联；`lockState`在独立事务里先建状态行，所以可能存在没有任何提交的空状态）：

| # | 条件 | 设计处置 |
| --- | --- | --- |
| 1 | 发布重试会先撞版本冲突，到不了回执回放 | 发布先按`(state, requestId)`查回执，命中直接回放；保存步骤对同一`requestId`幂等：`article_drafts`记`last_request_id/last_request_version`，同一`requestId`再次到达时跳过版本校验与保存、直接进入提交。前端在检测失败（503）后刷新版本再重试的做法保持（原型已验证）。 |
| 2 | 删除草稿靠级联在H2不成立 | 删除**显式按序**在同一事务内进行：先锁草稿行、再锁状态行（与发布同序，避免死锁）→ 删`review_flow_events`（按本状态各流转）→`review_flows`→`review_submissions`→`review_receipts`→状态行→草稿行；不依赖外键级联。“库内无该稿正文”的断言**在PostgreSQL上做**，H2用例仅验证显式删除代码路径，不作为证据。 |
| 3 | 阶段1须列全仍显示话题的读取点 | **取消“阶段1按ARTICLE过滤读取”**（§8修订2→本条简化）：阶段1只关闭创建，不改任何读取，因此无新增的读侧变化与风险。阶段2（授权后）删除话题数据时，**必须逐一处理并各配测试**的读取点：列表与`?type=`查询（`ContentPostRepository`）、`requirePost`链路（详情/点赞/评论读取/新评论）、专题文章列表jdbc（`ContentController`）、首页统计`content_count`（`SiteStatsController`）、`PersonalController`的`addPost`与`postCount`、专题编辑校验、`submitComment`路径、`DemoData`与`DiscoverPage`话题标签。 |
| 4 | 回填细节 | 在途提交取`max(review_submissions.id)`的最新快照；**没有任何提交的空状态跳过**（不建草稿）；幂等判断以“该状态已对应存在的草稿”为准（而不是`target_id is null`，因为旧键同样以`ARTICLE:`开头）；改键前检测`ARTICLE:<数字>`类旧键与新键空间的碰撞（`draftKey`是客户端任意字符串，仅限64字符），冲突者先改为带前缀的临时唯一键再换；这类草稿经`decide`**首次发布**后，`decide`的ARTICLE分支须**写回**`article_drafts.post_id`与状态`target_id`（目前只新建帖）。 |
| 5 | 话题评论留痕清理的匹配方式 | 先收集被删话题下的评论ID，再用`review_content_state.target_id in (评论ID)`加`content_key like 'COMMENT:<topicId>:%'`双重匹配（键有80字符截断）；**必须在评论被级联删除之前**执行；`review_noted`按评论ID删。 |
| 6 | 撤回事件的落点 | 旧文章（V15前发布）可能没有任何流转：此时为其创建一条**合成流转**（`PUBLISHED`、无检测级别、标题取公开标题、标记历史基线）承载“作者撤回/已更新发布”的`RESULT`事件，不补造`SUBMIT/CHECK`记录；有流转时追加到最近一条已发布流转，而不是任意最近流转。 |

发布说明须披露（转入G8）：①在途提交补建草稿后，作者会在编辑器里看到旧稿，包括被自动不通过的内容（原先表单已清空）；②话题在阶段2才对用户消失，阶段1无数据变更、可回退；③旧文章按Markdown显示外观会变；④不支持回退旧包。

## 15. G4 契约对本概设的细化（2026-10-21）

详见[CONTRACT_CONTENT_PUBLISHING.md](CONTRACT_CONTENT_PUBLISHING.md)§1：①状态键由`ARTICLE:<draftId>`细化为**`ARTICLE#<draftId>`**（避免与客户端任意`draftKey`撞车，复评#4）——本文§4/§14.3中出现的`ARTICLE:<draftId>`均以契约为准；②新接口一律HTTP 200由`outcome`区分；③草稿外键不级联、时间列用`timestamptz`；④草稿记`last_request_id/last_request_version`实现发布重试幂等；⑤公开版版本取最近`PUBLISHED`流转的`snapshot_version`。以上均不改变用户可见流程。
