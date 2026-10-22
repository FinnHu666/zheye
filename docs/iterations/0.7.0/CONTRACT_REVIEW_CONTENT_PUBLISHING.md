# FINNS-18 详细设计与接口契约评审

日期：2026-10-21；被评审：[CONTRACT_CONTENT_PUBLISHING.md](CONTRACT_CONTENT_PUBLISHING.md)修订1→修订2；评审者：与作者上下文隔离的只读子代理（用户授权，PUBLISH-D08；未读作者对话，只读源码与迁移，未运行任何东西）；结论：**带条件通过**（无阻断项）；条件全部在契约修订2§14关闭或转入下游。
输入：契约、概设修订4、决定D01–D08、验收CT-A01–16、原型R1、`ReviewService`、`ReviewController`、`ContentController`、`CollectionWorkspaceController`、`CollectionPublicationService`、`PersonalController`、`SecurityConfig`、`application.yml`及V1/V2/V6/V8/V15/V16/V17。**所有PostgreSQL语义均未实测。**

## 抽查（评审者）与我的核实

评审者逐条核对约14项主张：约束名推断、审计类型约束与长度、状态键唯一性、`ARTICLE#`键无冲突、回填C可插入性、回填重跑、503时草稿保持、锁序无环、复用提交流程、回执内容、`SET NULL`与CHECK、专题收录入口、权限矩阵、状态推导一致性、HIGH命中词泄露、移除`POST /api/posts`影响。其中**不成立/不完整**的有：回填C对title缺失的状态会失败；“可重复安全”不准确；§7漏列`process`/`submitArticle`；回执存的是`SubmitResult`；专题收录还有`PersonalController.addPost`；权限矩阵与现状不一致；`POST /api/posts`约16处测试使用。我对依赖源码的五点（作者侧接口权限、`withdrawTask`单入口、`addPost`、测试用量、V1 title非空）重新核实，均成立。

## 发现与去向

| # | 级别 | 发现 | 去向 |
| --- | --- | --- | --- |
| R1 | 重要 | 回填C title缺`coalesce`；断言2/3/4问题；重跑表述 | **关闭**：§14 R1 |
| R2 | 重要 | 幂等：同`requestId`语义、`last_request_version`无用、回执内容 | **关闭**：§14 R2（内容哈希、`REQUEST_REUSED`、版本相符、回执只存结果） |
| R3 | 重要 | 提交事务未重新锁草稿/核对版本；§7漏列`process`/`submitArticle`/`decide` | **关闭**：§14 R3 |
| R4 | 重要 | 移除`POST /api/posts`影响约16处测试 | **转入B4**（契约已定替代方案`TestArticles`） |
| R5 | 重要 | B6与D08冲突；H2差异 | **关闭**：B6只跑H2，PG用例标未验至B7 |
| R6 | 重要 | 权限矩阵（撤权作者） | **关闭**：用户决定（PUBLISH-D09）→`PROFILE_MANAGE`/`POST_CREATE`分工 |
| R7 | 重要 | `addPost`、`postCount`、`capture`可见性 | **关闭**：§14.2/§3.10 |
| R8 | 重要 | `withdrawTask`共用，不得改变评论/专题行为 | **关闭**：新增`cancelArticleSubmission`，旧入口对ARTICLE委托 |
| R9 | 重要 | 列表`state`筛选语义缺失 | **关闭**：§14 R9 |
| S1–S3,S6,S7 | 建议 | 审计`content_type`、flows排序、撤回后状态、补用例、头部状态 | **关闭**：§14 |
| S4 | 建议 | `lockState`第二连接与连接池 | **转入G6** |
| S5 | 建议 | H2开发库旧键 | **明确不采纳兼容代码**（开发库重建，写入交接/发布说明） |
| 决定2 | 需用户决定 | 阶段拆分与D06⑦同版交付冲突 | **关闭**：用户选“仍同版交付”（PUBLISH-D09） |
| 决定1 | 需用户决定 | 撤权作者的能力 | **关闭**：用户选“仍可以”（PUBLISH-D09） |
| 决定3 | 说明 | RECHECK期间不能取消也不能删除 | 保持并已说明（契约§14） |

## 对B1–B6的判断

评审者：B1、B2在修正R1与§2后可开始；B3在关闭R2、R3、R8、S1后；B4、B5在关闭R4、R6、R7、R9与决定2后；B6只跑H2；其余批次不涉及主库、真实PostgreSQL、部署或Plane。**以上条件均已在契约修订2关闭，除R4属B4实施内容、S4转G6。** 本评审为独立评审一轮，契约修订2对条件的关闭由作者完成，未再做第二轮。

## 对已通过门禁的影响

G0–G3不受影响（概设仅在§15记录键格式细化）；用户可见流程未变。
