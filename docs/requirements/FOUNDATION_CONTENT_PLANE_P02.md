# CONTENT-SYNC-P02：FINNS-18需求修订3同步差异

日期：2026-10-21；差异修订：1；状态：**A–D四项均已确认并写入、回读通过（2026-10-21）**。需求修订3（PUBLISH-D03）已确认，不代替远程写入批准。

操作：workitem update，项目 `587f13f8-9bd0-4ab2-933c-e3ace27f647f`，工作项 `5531f40a-0693-4be3-a9f5-82c197a7ec6c`（FINNS-18）。

## 写前核对（只读，2026-10-21）

远程当前为[CONTENT-SYNC-P01](FOUNDATION_CONTENT_PLANE_P01.md)写入的版本：`updated_at=2026-10-21T05:22:52.321822Z`，标题“FINNS 0.7.0｜F02 文章与普通话题草稿及发布”（P01已改名，旧标题记录见P01）；正文与P01拟稿逐字一致，**无人工新段落**；state=Backlog、parent=FINNS-14、cycle=0.7.0周期、priority none、无负责人/标签/日期。Release成员未核对（此前HTTP 402）。

## 精确变更（4项，各自可单独确认/跳过；只发送description一个字段）

### A. 非目标收窄（永久删除）

- 原文：`自动保存、永久删除、任意历史回滚`
- 改为：`自动保存、删除已公开/曾公开内容及其审核留痕（仅允许删除从未公开的本人私人草稿，见确认范围第8项）、任意历史回滚`

### B. 确认范围新增第8项并记录PUBLISH-D03来源

- 原文：`（PUBLISH-D01）。G1通过；`
- 改为：`（PUBLISH-D01）。2026-10-21用户评审原型R1后确认保留“删除从未公开的私人草稿”（PUBLISH-D03，需规修订3）。G1通过；`

- 原文：`公开列表/详情/检索/数量/主页/收藏/专题引用仅提供有效公开版，撤回/下架不能从旧卡片泄露原内容。⏎验收：`
- 改为：`公开列表/详情/检索/数量/主页/收藏/专题引用仅提供有效公开版，撤回/下架不能从旧卡片泄露原内容。⏎8. 删除（修订3，PUBLISH-D03）：仅可在“我的内容”列表删除从未公开的本人私人草稿，二次确认；公开过、已撤回、被下架或有待处理提交（含高敏感复核中）的不可删并说明原因。删除后列表、搜索、直达地址均不可见且不可恢复，不产生公开副作用、不补造审核记录；存储语义（物理删除或保留审计）待设计（CT-L04）。传统专题草稿删除不含在内。⏎验收：`

### C. 验收范围 CT-A01–13 → CT-A01–15

- 原文：`验收：CT-A01–13，见ACCEPTANCE_CONTENT.md修订2；全部未执行。覆盖两类内容存稿恢复、`
- 改为：`验收：CT-A01–15（CT-A14/A15为修订3新增：删除的可删范围/确认/结果，及删除的权限、CSRF、会话、并发与幂等），见ACCEPTANCE_CONTENT.md；全部未执行。覆盖两类内容存稿恢复、`

### D. 状态与下一步文案：原型R1已制作待评审（不标通过）

- 原文：`G1通过；本次管理同步后进入G2原型，具体原型尚待制作/评审；保持Backlog，不标Done。`
- 改为：`G1通过；管理同步已完成，G2原型R1已制作（浏览器模拟）待用户评审，G2未通过；保持Backlog，不标Done。`

- 原文：`下一步：管理同步回读后复用现有Vue原型制作文章/话题生命周期，具体原型确认后概要设计与设计评审、契约及实施；G3/G4前关闭遗留项（字符计量、兼容格式、路由、快照/并发契约）。`
- 改为：`下一步：用户评审原型R1并确认具体版本与范围后，进入概要设计与设计评审、契约及实施；G3/G4前关闭遗留项（字符计量、兼容格式、路由、快照/并发契约、删除后的存储语义CT-L04）。`

- 原文：`docs/iterations/0.7.0/PROTOTYPE_PUBLISHING_BRIEF.md；docs/delivery-progress.json。`
- 改为：`docs/iterations/0.7.0/PROTOTYPE_CONTENT_R1.md；docs/iterations/0.7.0/PROTOTYPE_PUBLISHING_BRIEF.md；docs/delivery-progress.json。`

## 保留与不做

- 保留：标题（不发送name）、state（Backlog，不发送）、parent、priority、assignees、labels、type_id、point、estimate_point、start_date、target_date、sort_order、is_draft、external_*、cycle_id；不调用Cycle/Release、评论、附件或成员操作。
- 不标In Progress/Done；原型R1只写“已制作待评审”，不写“已通过”。

## 执行约束

仅发送`description_stripped`。每项确认后写前按ID重读：若updated_at或正文已变化，重新计算并重新确认，不覆盖他人新内容；没有条件更新API，读写间有并发窗口。执行后按ID回读，核对被确认各项文字、标题、state、parent、cycle等保护字段；超时先回读，不盲目重试。仅应用被确认的项（未确认项原文保持）。

确认：2026-10-21用户在聊天中对A非目标收窄、B第8项与PUBLISH-D03来源、C验收CT-A01–15、D状态与下一步文案四项分别选择“确认写入”。
执行与回读：2026-10-21写前按ID重读（updated_at=2026-10-21T05:22:52.321822Z、state、cycle与基线一致，无他人新内容）；仅发送description_stripped一次，调用成功。写后按ID回读：标题不变，state仍为Backlog，parent=FINNS-14，priority/assignees/labels/日期/sort_order/is_draft保持，updated_at=2026-10-21T07:52:42Z；回读的cycle_id仍为0.7.0周期（update返回体再次显示null，以retrieve为准，序列化差异）；正文A–D四处变更文字与拟稿逐字一致，其余段落未变。未核对Release成员（此前HTTP 402），未做Cycle/Release/评论/附件写操作。
