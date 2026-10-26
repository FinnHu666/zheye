# FINNS 0.7.0｜专题接口与验收契约

2026-10-23，修订1。所有写接口沿用会话与CSRF、COLLECTION_MANAGE，归属来自服务端。400字段错误、401未登录、403无权、404不可见、409版本/状态冲突、503检测不可用。服务端校验50章/500条及既有字段上限。

- draft GET/PUT 在原 DTO 增加 `metadata`（可空，旧客户端缺省保留）：tags[]、category稳定key、seoTitle≤60、audiences[]、coverId、sectionLayouts[]。未知字段/值不默认开放；旧 audience 不删除。发布与审核冻结该元数据。
- POST `/api/me/collections/{id}/cover` multipart `file` 返回不可变coverId与读取URL；≤2MB JPEG/PNG，头尺寸≤4096，服务端裁16:9，无EXIF。上传需本人已创建草稿；删除引用通过保存metadata。GET `/api/collection-covers/{coverId}`按当前发布/作者权限校验，不可见404。
- POST publish 增加可空 scheduledAt（ISO UTC）；请求id与revision沿用。开关关闭拒绝定时；提交时检测，中敏感等待人工，自动不通过不排程；到点只使用已通过快照。新稿/旧版/当前计划分开返回。
- POST `/api/me/collections/{id}/cancel` 带revision；取消普通待审或已通过定时，保留稿与旧发布；高敏感复核不可取消。
- POST archive（新增withdraw别名）带revision，保留读者自选，未处理提交及定时失效；保留首次publishedAt。
- DELETE `/api/me/collections/{id}/draft?revision=…` 只限从未公开且无待审/下架限制的本人OUTLINE草稿，物理删除并清理相关私人痕迹，204。
- 公共outline沿用旧DTO兼容字段并附加metadata；无效POST引用所有携带原文信息字段清空。审批发布前再次验证引用。
- 分类查询参数category用于专题广场与发现的专题查询，发布版过滤在分页/计数前完成。SEO只取可见发布版，HTML转义。

测试映射：A01/A08/A25→导入复制兼容；A02–05/A07/A13/A26→冻结/审核/调度；A06/A17/A18→取消撤回删除/自选恢复；A09–12/A24→权限/引用/封面；A14–16/A19/A21/A23→浏览器队列/入口/共享阅读/适配；A20/A22→metadata校验、持久化、迁移与敏感检测。
