# FINNS 0.7.0｜F08 内容敏感拦截与专题首次审批（FINNS-17）：概要设计（G3）

日期：2026-10-19。修订：1。输入：[需求修订4](../../requirements/FOUNDATION_REVIEW_REQUIREMENTS.md)（REVIEW-D01–D09、P01–P06、RV-A01–A15）、[已确认原型 R2](PROTOTYPE_REVIEW_R2.md)（REVIEW-D15）、当前源码。评审记录：[DESIGN_REVIEW_CONTENT_REVIEW.md](DESIGN_REVIEW_CONTENT_REVIEW.md)。详设与接口：[CONTRACT_CONTENT_REVIEW.md](CONTRACT_CONTENT_REVIEW.md)。

状态：设计，不是已实现能力。设计不新增产品范围；与 R2 的偏差见契约 §1。

## 1. 现状（以 2026-10-19 源码为准）

| 事实 | 位置 |
| --- | --- |
| 文章/话题发布 `POST /api/posts` 校验后直接 `posts.save`，没有工作稿、审核或下架概念 | `ContentController.create` |
| 评论 `POST /api/posts/{id}/comments` 直接保存；公开读取只检查文章存在 | `ContentController.comment/comments` |
| 传统专题只有一个对外发布入口：`POST /api/me/collections/{id}/publish`，在锁定专题行的事务内改状态并调用 `publishSnapshot` 覆盖快照；启动迁移 V7 与演示数据也调用它 | `CollectionWorkspaceController.publish`、`CollectionPublicationService` |
| 公开专题读取从 `collection_publications.document`（冻结 JSON）读取；引用文章已不存在时显示“原文已不可用” | `CollectionPublicationService.read` |
| 权限目录是代码枚举，数据库只保存角色拥有的权限；站长（OWNER）角色拥有全部权限且不可关闭，每次请求从库读取权限 | `Permission`、`AccessService.forUser` |
| “谁是站长”的唯一事实源是 `access_control_state.owner_user_id`；管理写操作锁 `access_control_state` 行 | FINNS-16 `AccessAdminService` |
| `access_audit_log` 的 `type` 有 CHECK 约束，只含角色/账号类取值，且查询权限是 `admin.audit` | `V12`、`V14` |
| 账号停用：`user_accounts.account_status`；停用账号在 `AccessService.resolve` 与 `AuthController.requireUser` 都被拒绝 | FINNS-16 |
| 源码中没有敏感词、审核、下架实现 | `src/main`、`frontend/src` 检索 |

## 2. 设计总览

```
作者（会话 + CSRF）
  │ 提交：文章 / 评论 / 专题发布（内容快照 + requestId + 内容键）
  v
ContentReviewService.submit   ← 同一事务：锁“内容状态行”→ 幂等回执 → 检测 → 计数 → 分支
  ├─ 检测通过 ─ 文章/评论：写入公开表；专题：若已有首次资格→冻结快照发布，否则建 FIRST 任务
  ├─ 命中第1/2次 ─ 回 BLOCKED（不公开、不建任务）
  └─ 命中第3次起 ─ 建 PENDING 任务（SENSITIVE 或 FIRST+SENSITIVE）
站内审批人（content.review）
  │ 决定：通过 / 驳回（带 generation） ；处置：下架 / 恢复（带发布版本）
  v
ReviewDecisionService.decide / managePublication   ← 同一事务：重读权限与账号状态→禁自审→校验 generation→应用→写记录
站长（owner）
  └ SensitiveRuleService：规则查询/新增/编辑/启停/删除（版本条件更新 + 记录）

表：sensitive_rules  review_content_state  review_submissions  review_receipts  review_audit_log
列：content_posts / comments / community_collections 各加 moderation_state（VISIBLE|TAKEN_DOWN）
```

## 3. 关键设计决定

### 3.1 待审内容放在审核表，不进公开表

文章与评论在通过之前**不写入 `content_posts`/`comments`**；被拦截、待审的内容只存在 `review_submissions.snapshot_json`。这样列表、详情、数量、检索、主页、收藏、专题引用（RV-A09）天然读不到，不需要在每条读取链路加“待审过滤”。批准时才插入公开行并记录来源提交。专题待审快照是冻结 JSON（与现有发布快照同格式），工作稿继续留在原工作区表。

被否决：公开表加 `review_status` 列并在所有读取处过滤（读取链路多、易遗漏泄露）；批准时重新读取工作稿（违反“审批绑定不可变快照”）。

### 3.2 内容键与“本轮”计数

“同一作者同一内容”用 `content_key` 表示：已有对象用 `类型:对象ID`（专题），新建文章/评论用客户端在打开发布表单时生成的 `draftKey`（UUID，≤64 字符）。计数放在 `review_content_state(author_id, content_type, content_key)`：`attempts`、`generation`、`taken_down`、`approved_once`。命中且实际提交 `attempts+1`；通过敏感检测（含批准）发布后 `attempts=0`；驳回不清零。重复请求靠 `review_receipts(content_state_id, request_id)` 唯一回执返回首次结果，不重复计数（RV-A02）。不同内容键互不累计，也没有账号级汇总计数。

被否决：按 HTML 表单点击次数计数（需求明确排除）；按内容文本哈希识别“同一内容”（改一个字就成了新内容，等于清零，且不能关联后续修改）。局限：作者清掉本地 `draftKey` 重新开始一篇新文章视为新内容——这与需求“不同内容不累计”一致。

### 3.3 任务、快照与 generation

`review_submissions` 记录不可变快照。`generation` 是内容状态行上的单调计数；每次新提交、作者撤回/删除、下架、批准发布都 `generation+1` 并把旧 PENDING 任务置 `INVALID`。决定请求必须带任务当前 `generation`，与内容状态行不一致即 409，从而重复批准、批准过期任务、与撤回并发都不会重复发布（RV-A08）。作者停用使其 PENDING 任务失效：不做定时扫描，读取队列/处理决定时在事务内按“作者非 ACTIVE → INVALID”惰性失效，与 R2 `sweep` 一致。

### 3.4 权限与自审

新增权限 `content.review`（“内容审批”）：组“管理”，不 lockAll、不 lockAdmin，OWNER 角色自动拥有且不可关闭（`lockedFor` 已有此规则）；V15 补写 OWNER 角色的 `role_permissions`。其他角色手动授予，不自动建审核员角色（P01）。

敏感规则维护不用 `content.review`，而是**当前站长**判定（`access_control_state.owner_user_id`，处理请求时在锁内重读），因此审批人员即使被授权也不能改规则（RV-A11）。内容审批不套用账号层级，不检查作者角色。

每次决定与处置在事务内重读操作者：账号 ACTIVE、仍有 `content.review`、`operator.id != task.author_id`；任一不满足即 403，撤权在后续处理请求生效（RV-A07）。无第二名审批者时队列显示“等待另一名有权限者”（`otherReviewerAvailable` 由统计 ACTIVE 且有权限且非作者的用户得出）。

审批人读取待审快照所需的接口只在 `/api/review/**` 下，要求 `content.review`；它们不返回用户管理、全站审计或他人私人草稿。

### 3.5 检测（需规修订5 / REVIEW-P07，用户选择 B2：REVIEW-D17）

**规范化**：NFKC → 小写 → 去掉空白、零宽/格式/控制字符、标点和符号（`ReviewText.scan`）；提交文字与规则词语用同一套。每段文字（标题、摘要、正文、专题各字段）单独扫描，不会跨字段拼出命中。

**匹配**：对全部启用规则建一个 Aho-Corasick 自动机（`SensitiveMatcher`，项目内自行实现，不新增依赖），文本扫描一遍得到全部命中词（去重、保持规则顺序）。每条规则有匹配方式：`子串`（默认）或`整词`（只约束以英文字母/数字开头或结尾的一端：相邻处必须是原文分隔，如空白、标点、零宽字符或文本边界；中文端不受约束）。启用规则数为 0 或读取异常 → `DETECTION_UNAVAILABLE`（503）。自动机每次检测现建（≤500 条规则、约 2 万节点，毫秒级），因此规则变更对后续提交立即生效，没有缓存失效问题。

**备选与取舍**（用户可感知，已于 2026-10-20 上报并选定）：

| 方案 | 结论 |
| --- | --- |
| A 只做原有的大小写/全半角/去空白＋逐条子串匹配 | 否决：插标点即可绕过；无整词、无样例 |
| B1 规范化再去标点/零宽＋整词开关＋样例词库 | 采纳（B2 的一部分） |
| B2 = B1 ＋ 多模式匹配（DFA/Aho-Corasick） | **采纳（REVIEW-D17）**；自行实现而非引入库：候选库的版本与许可未核实，且代码约百行、有对拍测试 |
| 拼音/同义词/谐音/繁简 | 不采纳：拼音会放大误报；需另评审 |
| C 第三方内容安全 API | 不采纳：与 REVIEW-D08 冲突，价格/限额/合规未核实 |
| D 自建模型 | 不采纳：运维与训练成本与项目规模不相称 |

**能力边界表**（完整样例与对应测试见 [SENSITIVE_LEXICON_SAMPLES.md](SENSITIVE_LEXICON_SAMPLES.md)）：

| 场景（具体输入） | 结果 | 类别 | 处理办法或接受理由 |
| --- | --- | --- | --- |
| `b.a.d.w.o.r.d`、`ＢＡＤ　ＷＯＲＤ`、夹零宽字符的 `badword` | 命中规则 `badword` | 能做到 | 规范化去除标点/空白/零宽字符 |
| `badword` 拆成两个字段（标题 `bad`、正文 `word`） | 不命中 | 能做到（有意） | 各字段独立扫描，避免误拼 |
| 规则 `cat`（整词）遇到 `category` | 不命中 | 能做到 | 整词对英文有效 |
| 规则 `习`（任意模式）遇到 `学习` | **误命中** | 做不到 | 避免单字规则；整词对中文无效；人工复审兜底 |
| 谐音、拼音（`jia wei xin`）、同义词、繁简、错别字 | 漏判 | 做不到 | 不在范围（P07）；可另评审拼音 |
| 图片文字、附件、链接内容 | 漏判 | 做不到 | 不在范围 |
| 作者换新 `draftKey` 重新开始 | 仍被检测拦截，但不累计升级人工 | 做不到 | 已知局限：计数用于引导修改而非惩罚 |
| 规则 500 条以内、文本十几万字 | 毫秒级完成 | 能做到 | 测试断言 <2 秒；超过 500 条启用规则被拒绝 |
| 启用规则为 0 条 | **所有文章/评论/专题发布返回 503** | 取决于配置 | **上线前提**：站长须先启用规则（P03） |
| 规则只含标点/空白 | 拒绝保存 | 能做到 | 规范化后为空 |

### 3.6 专题首次审批、更新与恢复

- 首次发布（`approved_once=false`）：检测通过→建 `FIRST` 任务；命中第3次→`FIRST,SENSITIVE` 同一任务（RV-A03）；批准时：专题置 PUBLISHED、写冻结快照、`approved_once=true`、`attempts=0`。
- 已有首次资格：检测通过→直接发布冻结快照；命中第3次→`SENSITIVE` 任务。
- 作者撤回（现有 archive 流程）后重新发布：仍走检测，不重复首次审批；独立下架（`moderation_state=TAKEN_DOWN`）不被保存/发布/批准解除（RV-A05）。
- 历史已发布专题/文章/评论：不写审批记录；`review_content_state` 在首次后续修改时懒创建，`approved_once` 对已发布专题置 true、`legacy=true`（来源标记，不倒填记录）。
- 恢复：另一位有权限者对指定发布版填写原因；事务内重新检测该发布版。命中时恢复动作本身即“合并的敏感复审”（需求 REVIEW-D09 与 R2 行为一致）：首次请求返回 409 并列出命中词，审批者须在已阅读全文后带 `acknowledgeMatches=true` 重新提交才恢复；记录标明触发类型 `RESTORE+SENSITIVE`。检测不可用时恢复被拒（同 P03）。

### 3.7 下架与读取链路

三张公开表各加 `moderation_state`（默认 `VISIBLE`，附加变更）。公开读取处加 `moderation_state='VISIBLE'` 条件：文章列表/详情/评论、公开专题列表/详情/冻结快照读取（TAKEN_DOWN 返回 404）、专题内引用文章（下架引用显示“原文已不可用”，沿用已有占位）。作者本人仍可在“我的审核状态”查看。受限阅读权限继续独立生效。

### 3.8 记录

新建 `review_audit_log`，不复用 `access_audit_log`：后者的查询权限是 `admin.audit`，复用会把全站审计给审批人员，违反第7条。记录：类型（SUBMIT/APPROVE/REJECT/TAKE_DOWN/RESTORE/RULE_CREATE/RULE_UPDATE/RULE_DELETE/RULE_TOGGLE）、操作者、内容类型/ID、提交版本、触发类型、原因、结果、时间。拒绝/失败记录在独立事务提交，沿用 FINNS-16 做法；记录不含密码、令牌、会话。查看规则变更历史需站长；处置历史对审批人员和作者本人（仅本人内容）可见。

### 3.9 前端

沿用 Vue 3。把已确认原型的页面移植到 `frontend/src`：`/admin/reviews`（队列）、`/admin/reviews/tasks/:id`、`/admin/reviews/publications/:id`、`/admin/sensitive-rules`；复用 FINNS-16 的 `AdminLayout/AdminDialog` 外壳，导航项按权限显示（审批页需 `content.review`，规则页需站长）。作者侧：发布表单读取服务端返回的 `outcome`（PUBLISHED/BLOCKED/PENDING）展示结果；新增 `/me/reviews`“我的审核状态”。阅读页用文本分块渲染（不 `v-html`），与原型 `review-reading.js` 同逻辑。原型不被生产代码引用。

## 4. 兼容、迁移与回退

- `V15` 全部为附加变更：新表、三张公开表加带默认值的列、OWNER 角色补权限；不改写业务数据。对 `finns` 主库迁移须先备份并做恢复演练（G8 前置）。
- 回退旧包：旧包不认识 `moderation_state`，**下架内容将重新公开**；旧包也不做敏感检测。参照 USER-ADMIN-D13 的先例，是否同样“不支持回退旧包”是**待用户决定**（评审待决 U3）。
- 新增依赖：无。

## 5. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 提交内容与每个请求加载全部规则 | 规则上限 500、仅启用项；测量后再决定缓存，缓存必须随规则版本失效 |
| `draftKey` 由客户端提供，可被刻意轮换以规避计数 | 已知局限：计数目的是引导修改而非惩罚；轮换仍每次被检测拦截（命中就不公开），只是不会升级人工 |
| 只有一名审批者时作者内容无法审批 | 需求已规定（P02）；队列明确提示 |
| 专题批准时旧工作稿已改动 | 批准发布的是快照，工作稿不被覆盖；专题 `revision` 以快照提交时为准写回时不覆盖更高版本（契约 §3.3） |
| 回退使下架失效 | 见 §4 |

## 6. 技术栈变动说明

无新增依赖、无框架升级、无外部服务；沿用 Java 17、Spring Boot 3.3、JdbcTemplate、Flyway、Vue 3。

## 7. 验收映射

见契约 §10（RV-A01–A15 → 测试）。

## 8. 修订2：需规修订6 / 原型 R3（2026-10-20，REVIEW-D18–D22）

输入：[需规§10](../../requirements/FOUNDATION_REVIEW_REQUIREMENTS.md)（P08–P10、§10.8、RV-A17–A26）、[已确认原型 R3](PROTOTYPE_REVIEW_R3.md)、[示例词库样例](SENSITIVE_LEXICON_SAMPLES.md)。本节**取代**前文中与下列主题冲突的设计：§3.2 命中计数与 generation 中的“前2次拦截”、§3.6 的“首次审批/FIRST”触发；其余（待审不入公开表、内容状态行、权限、下架、记录）保持。契约增量见 [CONTRACT §13](CONTRACT_CONTENT_REVIEW.md)。

### 8.1 判定与流程（一条流水线，文章/评论/专题一致）

`ReviewService.process` 改为：**检测（取最高等级）→ 分流 → 人工 → 结果**，每一步写入流转事件。

| 最高等级 | 分流 | 内容 | 任务 |
| --- | --- | --- | --- |
| 无 | 直接发布 | 公开 | 无 |
| 低 | 放行并留痕 | 公开 | 无；写一条 `review_noted` 留痕记录 |
| 中 | 送审 | 不公开（仅快照） | `REVIEW` 任务（通过/驳回） |
| 高 | 自动不通过 | 不公开 | `RECHECK` 任务（维持不通过/判定误判并放行，均须原因） |

取消：命中计数（`attempts` 不再使用，列保留不写）、`FIRST` 触发、专题首次必审（`approved_once` 对判定不再起作用，保留作历史标记）、HTTP 422 拦截（提交只会得到发布/送审/自动不通过三种结果）。`draftKey` 保留，仅用于识别同一份内容、关联流转和重试幂等。

### 8.2 流转时间线：落库的事件，不从状态反推

新表 `review_flows`（一次提交一条：快照版本、最高等级、状态 `IN_REVIEW/PUBLISHED/AUTO_REJECTED/APPROVED?` 等）和 `review_flow_events`（阶段 `SUBMIT/CHECK/ROUTE/REVIEW/RESULT`、标题、作者可见说明、审批侧详情、操作者 ID 与姓名、时间）。事件与业务变更**同一事务**写入，因此“发生了什么”不会和状态脱节。作者接口返回时：审批人姓名替换为“审核员”，**高敏感的命中词与详情整体剔除**；审批接口返回完整事件。预留阶段（创建/草稿、更新发布、撤回）**不落库**，只在前端以占位显示，由 FINNS-18/19 接入时新增阶段值，不改本表结构（阶段为字符串，白名单在服务端校验）。

被否决：从 `review_submissions.status` 现算时间线（丢失“谁在何时做了什么”，且无法表达多次重提）；把事件塞进已有 `review_audit_log`（那是管理记录，保留期与可见范围不同，且作者不应读取）。

### 8.3 规则等级、标签、批量与导入

- `sensitive_rules` 增加 `level`（LOW/MEDIUM/HIGH，默认 MEDIUM）、`tag`（可空，≤30 字）。匹配器的命中结果带等级；多条取最高。
- 批量启停：按标签条件更新（一个事务、版本号逐行 +1、写一条记录）。
- **导入由站长上传文件，服务端不联网下载**：站长在规则页选择本地文本文件（一个或多个，文件名即标签、每行一词；或单个 CSV `词语,标签`；UTF-8，≤2MB、≤20000 行），服务端解析成预览（标签分布、建议等级、疑似误报、重复），确认后按标签设置落库。好处：来源与许可由站长自己对照核实、系统没有外部依赖与额外下载、不把词库放进仓库或迁移。预览阶段不写库；提交时再次校验并按规则唯一性跳过重复。
- 疑似误报判定：长度 ≤2 个字符的词（依据实测：候选词库色情类型 80%、色情词库 63% 为短词，见 [SENSITIVE_LEXICON_CANDIDATE.md](SENSITIVE_LEXICON_CANDIDATE.md)）；默认不导入，需显式勾选。

被否决：服务端按 URL 抓取开源词库（外部依赖、许可与体积风险）；把示例词库写入迁移（会让未经站长确认的规则在生产生效，违背 P03/P09）。

### 8.4 低敏感留痕与处置

`review_noted`（内容类型/ID、作者、命中词、等级、时间、已阅人与时间）；列表接口只对 `content.review` 开放；“查看内容”复用既有发布内容详情，“下架”复用既有下架接口，“调整等级”跳到规则页（不在留痕页改规则）。公开读取链路不受影响。

### 8.5 前端

沿用 Vue 3。移植原型 R3：`FlowTimeline`、`PageNav`（页码+跳转）、重做的队列（概览卡片、等级筛选、迷你进度条）、详情 tab（`?tab=flow`）、作者“发布与流转”页（`/me/reviews` 升级）、规则页（等级、标签筛选、批量启停、导入弹窗）。发布表单/评论/专题发布对 `PENDING` 和 `AUTO_REJECTED` 显示就地提示并给“查看流转”入口；不再有“命中拦截”提示。

### 8.6 能力边界表（修订6 增量）

| 场景（具体输入） | 结果 | 类别 | 处理办法或接受理由 |
| --- | --- | --- | --- |
| 内容含站长标为“高”的词 | 自动不通过，进复核队列，作者不见命中词 | 能做到 | 复核可放行误判 |
| 政治类敏感内容但站长词库里没有对应词 | **放行或只按其他词处理** | **做不到** | 系统只匹配站长配置的词，不识别语义；“高敏感主要指政治敏感”依赖词库质量 |
| 同一内容命中低+中 | 按中处理（送审） | 能做到 | 取最高等级 |
| 导入大词库并启用（真实候选词库去重后 3,775 词） | 检测仍是一次线性扫描；自动机按启用规则指纹缓存，规则不变时不重建；**精度取决于类别**（见词库文档） | 取决于配置 | 启用上限 20,000；超出拒绝启用。压测转入 G6 |
| 作者看高敏感的未通过原因 | 只看到“包含不允许发布的内容” | 能做到 | 不透露词，避免试探；但作者可通过二分改稿猜词，属已知局限 |
| 低敏感内容事后发现有问题 | 可查看、已阅、下架；放行本身不撤销 | 能做到 | 站长事后处置 |
| 时间线 | 只记录本系统内审核阶段；创建/草稿/更新/撤回只有占位 | 做不到 | 待 FINNS-18/19 |
| 异步 | 检测同步毫秒级；时间线节点一次性写入 | 做不到真异步 | 推迟到 FINNS-18/19（REVIEW-D18） |
| 上线前提 | 启用规则为 0 条时全部发布返回 503；V17 不带任何规则 | 取决于配置 | 站长先导入或新增并启用规则 |
