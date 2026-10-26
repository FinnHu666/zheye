# FINNS 0.7.0｜FINNS-19 验收结果（修订1）

日期：2026-10-24。范围以需规修订2–4、PUBLISH-D21及[实施记录](IMPLEMENTATION_COLLECTION.md)为准。以下记录真实服务端、数据库和页面验证，不把5199/8099模拟接口当业务证据。最终命令、退出码、源码/测试/产物哈希见实施记录“最终收尾核对”与[清单](COLLECTION_VALIDATION_SHA256.json)。G5/G6/G7本轮通过，G8发布未执行。

| 验收 | 实际证据与覆盖 | 判定 |
| --- | --- | --- |
| A01 创建、模板、导入、复制、容量、私人保存 | CollectionImportTests 7项；浏览器真实创建/保存/刷新；未完整条目保存与发布定位分开测试 | 已验证 |
| A02 四级分流/无首次必审 | ContentReviewTests.collectionsFollowTheSamePipelineAndFirstPublicationNoLongerNeedsApproval；统一process低敏感测试与专题适配字段核对；受限专题回归 | 已验证 |
| A03 字段覆盖、最高级、幂等 | ReviewService.outlineTexts逐字段核对（名称/简介/人群/目标/章节/条目/SEO/标签）；highestLevelWinsAndVariantsCannotBypass；专题回执重试 | 已验证 |
| A04 固定被审版/重提 | reviewApprovesFrozenScheduledVersionAndHighReviewCannotBeCancelled；collectionUpdatesKeepTheOldVersionWhileReviewedAndArchiveInvalidatesThePendingFlow；共享管线替代旧任务 | 已验证 |
| A05 更新保留旧版 | 同上与snapshotIsolatesAllPublicReadersUntilExplicitRepublishAndArchive；立即发布改为确切送检快照 | 已验证 |
| A06 取消与撤回 | scheduledPublicationFreezesContentAndCancellationPreventsReappearance；withdrawalRetainsSelectionsAndOriginalPublicationTime；旧任务失效与旧回执不能重发 | 已验证 |
| A07 历史/重发/下架 | 旧公开数据不补审的共享F08回归；ContentReviewTests下架/独立恢复与作者禁止自审；重发保留原始时间 | 已验证 |
| A08 副本隔离 | copyAndImportCannotCrossOwnersAndImportedHtmlIsOnlyText、duplicateAndAppendPreserveMetadataWithoutPublicationOrSchedule、RestrictedContentTests副本默认范围 | 已验证 |
| A09 草稿引用与提交阻断 | unfinishedDraftsSaveButPublishListsMissingFields；publishProblems定位空/失效引用；提交/审批/调度使用锁内assertReferences最终检查，失败需修正重提 | 自动化与源码核对 |
| A10 已发布失效引用不泄漏 | retryCannotRepublishWithdrawnContentAndReferenceCardsAreRedacted；旧快照统一清空自定义title/annotation/原文ID/卡片信息 | 已验证 |
| A11 范围快照/读取链路 | RestrictedContentTests全3项，覆盖列表/数量/直达/outline/兼容posts/自选；快照隔离回归 | 已验证 |
| A12 身份与权限 | 非作者删除/保存/导入/复制拒绝；ContentReviewTests撤权/停用/禁止自审；UserStatusTests旧会话与旧发布版边界 | 已验证 |
| A13 幂等与竞态 | concurrentApprovalsPublishExactlyOnce；approvalRacingWithdrawalCannotRestoreAnOldSubmission（真实PostgreSQL）；旧回执重试、409版本冲突 | 已验证 |
| A14 输入/登录保护 | 草稿队列回归及向导组件失败不清输入、不提交发布、换号阻止写入；恢复原账号仍保留内容；刷新只恢复已落库稿 | 已验证 |
| A15 状态/流转 | 真实collection_activity创建/保存/发布/取消/撤回；审核流转和待定时公开API展示；我的专题名称/状态筛选 | 已验证 |
| A16 页面与视口 | 实例5200→8094真实路径；390/768/1440 CSS px内容页、390预览/公开页，响应式修正、直达/刷新/路由历史；实体手机见下方发布前置 | 浏览器通过，真机未验 |
| A17 删除私人稿 | unpublishedDraftDeletionChecksOwnerRevisionAndPendingSubmission；rejectedScheduledSubmissionIsClearedAndPendingDraftCannotBeDeleted；已公开不可删，缺失ID404 | 已验证 |
| A18 自选保留/恢复 | withdrawalRetainsSelectionsAndOriginalPublicationTime：数据库关系保留、撤回隐藏、重发后恢复；下架读取复用同一过滤 | 已验证 |
| A19 三步向导 | 真实创建→内容→预览→保存→刷新→发布；字段校验与队列组件测试；新建busy保护；问题定位复用wizardIssues | 已验证 |
| A20 引子接入 | metadata冻结/分类API与广场筛选、封面真实HTTP、SEO转义HTML、定时补发、Markdown引用及章节CARDS/TIMELINE；开关未开明示不可选 | 已验证（保留明确非目标） |
| A21 编辑入口完整承接 | edit重定向统一向导；更新/撤回/取消/审核流转/导入补充/范围/会话保护；追加前先保存的组件回归 | 已验证 |
| A22 人群结构化/迁移 | 服务端白名单验证；前端/阅读优先结构化数组，旧audience兼容保留；四条迁移样例原文无丢失 | 已验证 |
| A23 阅读复用 | CollectionReader用于公开、私人、向导；新选文章引用预览补齐真实标题/摘要；Markdown真实浏览器渲染；390容器 | 已验证，真机未验 |
| A24 封面安全 | JPEG/PNG、伪扩展名/畸形/超字节/超头尺寸、16:9输出；真实HTTP EXIF消除、nosniff、ETag；读取权限、下架404；未提交私人封面不可被他人版主处置；引用保留/无引用清理 | 已验证 |
| A25 迁移/兼容 | V18（含Java V7）→V19→V20真实演练；2全匹配/1部分/1未匹配且原文保留；0.6.0旧包在升级库独立副本启动/创建201/保存200/读取200；导入复制metadata回归 | 已验证（基础读写冒烟范围） |
| A26 定时 | 提交冻结/中敏感批准较晚/自动不通过/取消/撤回/重提清除；H2与PostgreSQL skip locked；真实进程重启补发；CollectionFeatureGateTests默认关闭拒绝定时和非LIST样式 | 已验证 |

## 单列的发布前置与非目标

实体手机/触摸、跨浏览器和屏幕阅读器未验，生产备份/恢复、生产负载、发布环境开启开关未执行；它们保留在G8，不写为发布完成。旧包兼容只验证专题基础读写，不承诺任意旧功能和数据组合均可安全降级。定时按单实例设计。

用户此前明确的发布渠道/指定成员、轮播/评分榜等暂缓范围不因此实现。画布坐标及HTML模块沿用原型“仅本浏览器预览、公开发布不包含”的明示边界，不伪称已持久化。标签按已确认0.7.0展示范围交付，不把后续标签点击筛选称为已交付。

没有对业务主库、真实账号数据或主站进行操作；没有Git提交/推送和版本升级。
