# CONTENT-SYNC-P04：FINNS-18 实施进展同步差异

日期：2026-10-22；差异修订：1；状态：**A–D四项均已确认并写入、回读通过（2026-10-22）**。用户已在PUBLISH-D10回复“同步吧”，但每个写入项仍须逐项确认。

操作：workitem update，项目 `587f13f8-9bd0-4ab2-933c-e3ace27f647f`，工作项 `5531f40a-0693-4be3-a9f5-82c197a7ec6c`（FINNS-18）。

## 写前核对（只读，2026-10-22）

远程为[CONTENT-SYNC-P03](FOUNDATION_CONTENT_PLANE_P03.md)写入的版本：`updated_at=2026-10-21T10:19:30.020498Z`，标题“FINNS 0.7.0｜F02 文章草稿及发布”，state=Backlog，parent=FINNS-14，cycle=0.7.0周期；正文与P03拟稿一致，无人工新段落。执行前须再按ID重读核对。Release成员未核对（此前HTTP 402）。

## 精确变更（4项，各自可单独确认/跳过）

### A. 状态句与“实施进度”：G1–G4通过、G5进行中（不标Done，不声称已发布）

- 原文：`当前源码及目标产品版本均为0.7.0，本项完整生命周期尚未实施，不表示主站已部署。`
- 改为：`当前源码及目标产品版本均为0.7.0，本项后端已部分实施（见“实施进度”），未发布、未部署主站。`

- 原文：`G1通过；管理同步已完成，G2原型R1已制作（浏览器模拟）待用户评审，G2未通过；保持Backlog，不标Done。`
- 改为：`G1–G4通过（原型R1经用户确认PUBLISH-D05；概要设计与详设契约经独立评审，PUBLISH-D06–D09）；G5实施进行中（见“实施进度”）；不标Done。`

- 原文：`⏎验收：CT-A01–16`
- 改为：`⏎实施进度（2026-10-22，仅本地H2测试，PostgreSQL语义未实测）：B1–B4后端已完成——草稿表与V18脚本、草稿创建/读取/保存/列表、发布/取消送审/撤回/删除、审批与下架补强、点赞原子更新、可见性修复、旧发布接口停用并关闭话题创建；前端B5、测试与浏览器核对B6、PostgreSQL迁移演练B7、话题删除B8尚未完成；未发布、未提交主站。⏎验收：CT-A01–16`

### B. 第9项改为用户已选“删除”，并新增第10项记录其他已确认决定

- 原文：`历史话题数据不得静默丢失，处置方式（下线隐藏保留/转换为文章/删除）待G3备选比较后由用户选择（CT-L05），迁移需备份与恢复演练、设计评审。当前代码话题仍直发不经检测，移除前该缺口存在。`
- 改为：`用户已选择历史话题删除（PUBLISH-D06）：连同评论与点赞物理删除、不可逆，被专题引用处显示不可用占位；真实删除前须在备份副本出清单由用户逐类确认、备份与恢复演练，并在发布授权下执行（B8，未执行）。阶段1已关闭话题创建（旧发布接口返回410），既有话题仍可读，阶段2删除前不再新增未检测的话题。⏎10. 其他已确认决定：已发布文章更新通过后保留原发布时间与排序并在详情页显示“更新于”，迁移时在途未发布提交按快照补建草稿（PUBLISH-D07）；被撤销发布权限的作者仍可查看、取消送审、撤回、删除自己的内容，创建/保存/发布需发布权限，文章生命周期与话题移除同版交付（PUBLISH-D09）；字数按Unicode码点计，旧文章一律按Markdown显示，删除草稿物理删除（PUBLISH-D06）。`

### C. 下一步与本地权威路径更新

- 原文：`下一步：用户评审原型R1并确认具体版本与范围后，进入概要设计与设计评审、契约及实施；G3/G4前关闭遗留项（字符计量、兼容格式、路由、快照/并发契约、删除后的存储语义CT-L04、历史话题处置CT-L05）。`
- 改为：`下一步：B5前端（我的内容文章页签、编辑与预览页、创作入口、安全Markdown渲染）、B6前端测试与浏览器核对；B7隔离PostgreSQL迁移演练按授权进行，B8话题删除另行逐项授权；发布等B7/B8完成后与话题删除一并进行。`

- 原文：`docs/iterations/0.7.0/PROTOTYPE_PUBLISHING_BRIEF.md；docs/delivery-progress.json。`
- 改为：`docs/iterations/0.7.0/PROTOTYPE_PUBLISHING_BRIEF.md；docs/iterations/0.7.0/DESIGN_CONTENT_PUBLISHING.md；docs/iterations/0.7.0/CONTRACT_CONTENT_PUBLISHING.md；docs/iterations/0.7.0/IMPLEMENTATION_CONTENT_PUBLISHING.md；docs/delivery-progress.json。`

### D. 状态：Backlog → In Progress（实施已开始）

- state：`Backlog (5b7d9df4…)` → `In Progress (71236130-c4d8-4d18-9a59-4106619bf8c6)`；不改其他字段。

## 保留与不做

- 只发送被确认项涉及的字段（`description_stripped`与/或`state`）；保留标题、parent、priority、assignees、labels、日期、sort_order、cycle_id；不调用Cycle/Release、评论、附件、成员操作；不标Done。
- 未确认项保持原文；部分确认时按所确认项重新生成载荷。全量载荷（A+B+C）见`FOUNDATION_CONTENT_PLANE_P04.json`。

## 执行约束

写前按ID重读（updated_at/标题/正文/状态变化则重新确认，不覆盖他人新内容；无条件更新API，读写间有并发窗口）；执行后按ID回读核对被确认项文字、state与保护字段；超时先回读，不盲目重试。

确认：2026-10-22用户在聊天中对A状态句与实施进度、B第9/10项、C下一步与本地路径、D状态→In Progress四项分别选择确认。
执行与回读：写前按ID重读（updated_at=2026-10-21T10:19:30Z、标题、state=Backlog、cycle与基线一致，无他人新内容）；发送description_stripped与state一次，调用成功。写后按ID回读：state=In Progress（71236130…），标题不变，parent=FINNS-14，priority/assignees/labels/日期/sort_order/is_draft保持，updated_at=2026-10-22T04:25:57Z；回读的cycle_id仍为0.7.0周期（update返回体再次显示null，以retrieve为准）；正文A–C变更文字与拟稿逐字一致。未核对Release成员（此前HTTP 402），未做Cycle/Release/评论/附件写操作。**已知残留**：正文第8项仍写“存储语义（物理删除或保留审计）待设计（CT-L04）”，而D4已决定物理删除——该句不在本次确认的差异内，需下次同步时再提。
