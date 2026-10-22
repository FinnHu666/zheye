# FINNS-18 验收结果（CT-A01–A16）

日期：2026-10-22；对象：F02 文章草稿及发布 + 话题整体移除（工作区 `release/0.7.0`，未提交）。验收表见 [ACCEPTANCE_CONTENT.md](ACCEPTANCE_CONTENT.md)（其“未执行”列保留为历史，以本文为准）。

## 实际执行的检查（真实退出码）

| 检查 | 结果 |
| --- | --- |
| 后端全量，H2 内存库：`mvn -s .mvn/settings.xml test`（进程内清除 `FINNS_MAIL_*`） | **184 项，0 失败 0 错误，退出码 0**；之后新增 1 个并发用例（`ArticleLifecycleTests` 23/23、`ArticleVisibilityTests` 4/4，H2 与 PostgreSQL 均通过），新增后**未再跑全量**，合计应为 185 |
| 后端全量，隔离 PostgreSQL 17.11（`scripts/test-postgres.ps1`，干净库 `finns_test_final`，端口 54329，Flyway V1–V18 + `ddl-auto: validate`） | **184 项，0 失败 0 错误，退出码 0** |
| 前端：`node --test tests/*.test.js` | **57 项通过** |
| 前端构建：`vite build`（输出到临时目录） | 通过 |
| V18 迁移演练（虚构数据，备份→迁移→回填断言→dump/restore 校验和） | 通过（B7） |
| V19 话题删除演练（虚构数据，错误数字中止无变化；确认数字后只删话题及其评论/留痕） | 通过（B8） |
| 浏览器（内置浏览器 + mock 接口，390/768/桌面）：我的内容、编辑、预览、冲突、会话丢失、503 重试、离开保护、发现页详情“更新于”与安全渲染 | 通过（见实施记录 B6 补记；发现页另测一次） |

## 逐项结果

| 验收 | 结论 | 证据 / 限制 |
| --- | --- | --- |
| CT-A01 私人稿保存、刷新恢复、他人不可见 | 通过 | `ArticleDraftApiTests.createReadAndOwnership`；浏览器直达/刷新编辑页 |
| CT-A02 不完整稿/全空/必填/上限/保留输入 | 通过 | `emptyAndLimits`、`publishValidatesRequiredFieldsLimitsAndRequestId…`、`TextLimitsTests`、`ReviewTextCutTests`、前端 `articles.test.js`（码点口径） |
| CT-A03 离开三选/保存失败不离开/原生提醒 | 部分 | 三选与保存失败：前端组件测试 + 浏览器通过；**浏览器原生 `beforeunload` 提示未实测**（自动化浏览器无法确认原生对话框） |
| CT-A04 Markdown 预览与阅读一致、安全 | 通过 | `safeMarkdown` 正反例单测、预览页与发现页详情浏览器实测（脚本显示为文字、图片不加载、坏链接不可点） |
| CT-A05 保存→冻结→检测分流、取最高、异常阻止、重试幂等 | 通过 | `ArticleLifecycleTests` 低/中/高三路、`sameRequestIdReplays…`、`detectionUnavailable…`、`retryWithTheSameRequestIdAfterAnotherSave…`；前端同 `requestId` 重试 |
| CT-A06 旧有效版在改稿/待审/驳回/复核期间继续可读 | 通过 | `mediumUpdateWaitsForReview…`、`cancellingASubmission…`、`ArticleVisibilityTests` |
| CT-A07 后改不替换提交稿、重提作废、取消只作废任务 | 通过 | `cancelling…`、`highSensitivity…CannotBeCancelled…`、`anUpheldRecheckTaskIsClosed…`、`twoConcurrentPublishes…` |
| CT-A08 撤回二次确认、所有入口与专题冻结引用不泄露、重新发布重新检测 | 通过 | `withdrawal…`、`withdrawingAHistoricalArticle…`、`onlyVisibleOwnArticles…`、`aPublicCollectionNeverShowsTheFrozenTitle…`；列表撤回确认对话框（组件测试+浏览器） |
| CT-A09 下架不被作者操作/账号恢复解除，另一审批者恢复 | 通过 | `manualTakedownIsNotUndone…`、`approvingAnUpdateAfterATakedown…`、`aDisabledAuthorIsRejectedEverywhere…`（经管理接口停用→恢复，下架保持） |
| CT-A10 权限/未登录/停用/失权/CSRF/会话/换号 | 通过 | `ArticleDraftApiTests`（401/403/CSRF）、`revokedAuthorsCanManage…`、停用账号 401；前端 401 就地重登与换号保护（组件测试 + 浏览器） |
| CT-A11 多窗口冲突与并发 | 部分 | H2/PG 两线程：同草稿并发发布只产生一篇、点赞原子更新、不覆盖下架、版本冲突保留输入。**未做高并发/连接池压力**（契约 S4 转入发布前评估） |
| CT-A12 撤回/下架阻止互动与收录，关联数据不丢 | 通过 | `ArticleVisibilityTests`、`likesOnlyCountOnVisiblePosts…`、V18 回填演练（旧 ID/作者/来源保留） |
| CT-A13 我的内容复合状态与流转、桌面/390/768、直达刷新、历史导航 | 通过（浏览器为 mock 接口） | 组件测试 + 浏览器；**真人手机未测** |
| CT-A14 仅从未公开私人草稿可删，二次确认，库内无正文 | 通过 | `deletingAnUnpublishedDraftRemovesEveryCopy…`（H2 与 PG 均跑过）、`publishedOrWithdrawnArticlesCannotBeDeleted`、列表删除确认 |
| CT-A15 删除的权限/CSRF/会话/幂等/并发 | 通过 | 权限/CSRF/未登录/重复请求 404；`deleteRacingWithPublishNeverLeavesAnOrphanedPublicPost`（两线程 3 轮，H2 与 PostgreSQL 均通过）：要么删除成功要么发布成功，绝无“有公开行无草稿” |
| CT-A16 话题整体移除与历史话题处置 | 通过（代码与隔离演练）；**真实数据未执行** | 创建入口/接口关闭（410）、`ContentPost.Type` 仅 `ARTICLE`、发现页无话题、种子数据改文章；V19 在隔离库演练；被删文章在公开专题读取时遮成“原文已不可用” |

## 未验 / 须在发布前完成（我没有做，也不应在没有你授权和真实备份副本时做）

1. 真实备份副本上的盘点（`topic-removal/INVENTORY_TOPICS.sql`）与 V18/V19 演练；你对不可逆删除（话题、评论、点赞）逐类确认。
2. 真人手机测试、浏览器原生 `beforeunload`、键盘/读屏可达性。
3. 前后端真实联调（运行打包后的应用 + 浏览器）：本次浏览器用 mock 接口，后端由 H2/PostgreSQL 集成测试覆盖。
4. 高并发/连接池压力、真实数据量下的迁移耗时与锁。
5. 发布：四处版本号、CHANGELOG 发布条目、部署、部署后检查。H2 开发库（`data/aitome`）不支持 V19，含历史话题的 H2 库须先迁到 PostgreSQL 或重建（同 S5）。

## 对既有行为的影响（发布说明素材）

- 在途未发布提交迁移后会在编辑器里补建草稿，作者能看到旧稿（含被自动不通过的内容）。
- 话题类型整体消失；旧话题链接返回“内容不存在”；点赞与评论随话题删除。
- 旧文章按 Markdown 显示，外观可能变化。
- 不支持回退旧包；`POST /api/posts` 返回 410。
