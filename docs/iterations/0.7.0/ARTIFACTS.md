# AI 工作产物索引：0.7.0 规划及流程规范

日期：2026-10-10。目的：让下一位 AI 直接定位权威资料和未完成事项。保留既有路径，不批量移动、删除或复制历史 AI 文件。

2026-10-12 新版本入口：[产品路线图](../../RELEASE_ROADMAP.md)、[0.7.0 计划](PLAN.md)/[需规](REQUIREMENTS.md)、[0.8.0 计划](../0.8.0/PLAN.md)/[需规](../0.8.0/REQUIREMENTS.md)。版本归属与本轮状态以这些入口及最新交接为准；下表保留较早产物日期/修订记录。

2026-10-13 直接项目绑定产物：[项目与两个 Cycle 回执](../../PLANE_PROJECT_BINDING.md)，批准差异修订 1 / 回执修订 2，记录三个获确认操作与实际 ID；[0.7.0](PLANE_SYNC.md)/[0.8.0](../0.8.0/PLANE_SYNC.md) 分别维护版本映射。当前两个 Cycle 已建立，不等于业务实施或需求迁移。最小脱敏回读证据：target/req-review-proof/plane-cycle-write-readback.json（忽略目录，可能清理）；写前文档检查为 plane-cycle-preparation-validation.json，写后检查见最新交接。本轮无 Git 提交/推送。

2026-10-15 原型产物（FINNS-10/11）：[原型记录 R1](PROTOTYPE_SITE_ACCESS.md)（待用户确认）与 [原型源码](../../prototypes/site-access/README.md)；构建输出在忽略目录 target/prototypes/site-access（可重建）。旧 [access-rating](../../prototypes/access-rating/README.md) 对 FINNS-10/11 已被取代，保留历史。

2026-10-15 FINNS-10/11 交付产物：[原型记录 R2–R4](PROTOTYPE_SITE_ACCESS.md)（已确认）、[概要设计](DESIGN_SITE_ACCESS.md)（G3 通过）、[详细设计与接口契约](CONTRACT_SITE_ACCESS.md)（G4 草稿）、[Plane 差异与回执 U12–U14](PLANE_SCOPE_UPDATE_R5.md)；需规修订 5。

## 1. 本轮持久产物

以下为项目内文本文件，可纳入正常版本管理；本轮没有提交/推送 Git。产物状态不等于业务功能上线。

| 需求/阶段 | 产物与用途 | 路径 | 状态/修订 | 来源与保存 |
| --- | --- | --- | --- | --- |
| REQ-014/G0–G1 | 下一版本范围及进入条件 | [PLAN.md](PLAN.md) | 修订 1，已安排；细节待确认 | 本轮新增，项目文本 |
| REQ-014/REQ-004 | 用户决定、待决与门禁 | [DECISIONS.md](DECISIONS.md) | 修订 1，状态分别记录 | 本轮新增，不保存逐字私聊 |
| REQ-013/REQ-014/REQ-004 | Plane 核对、差异、确认与回执 | [PLANE_SYNC.md](PLANE_SYNC.md) | 以文件中每项实际结果为准 | 本轮新增，仅脱敏回执，无工具响应镜像 |
| REQ-004/G6 | 格式、引用、版本与边界检查 | [VALIDATION.md](VALIDATION.md) | 本轮检查；业务测试未执行 | 本轮新增，项目文本 |
| REQ-004 | skill 唯一维护源及元数据 | [SKILL.md](../../../.agents/skills/finns-delivery-workflow/SKILL.md)、[openai.yaml](../../../.agents/skills/finns-delivery-workflow/agents/openai.yaml) | 项目级，已制作 | 本轮新增，不另建全局副本 |
| REQ-004 | 严格阶段、同步、归档及模板 | [阶段门禁](../../../.agents/skills/finns-delivery-workflow/references/stage-gates.md)、[Plane 协议](../../../.agents/skills/finns-delivery-workflow/references/plane-sync.md)、[产物规则](../../../.agents/skills/finns-delivery-workflow/references/artifacts.md)、[记录模板](../../../.agents/skills/finns-delivery-workflow/references/record-templates.md) | 已制作 | 本轮新增；可复用模板，不复制无关空文件 |
| REQ-004 | 统一入口及技术规则 | [AGENTS.md](../../../AGENTS.md)、[finns-development](../../../.agents/skills/finns-development/SKILL.md) | 增量更新；旧技术栈保留 | 既有文件本轮修改 |
| REQ-014/G1 | 多轮沟通详细方案 | [沟通方案](../../TOPIC_BUILDER_CONVERSATION_PLAN.md) | 从待安排更新为已纳入下一版本；业务未实施 | 既有方案本轮增量更新 |
| 全局接续 | 总计划、交接及变更日志 | [NEXT](../../NEXT_ITERATION_PLAN.md)、[HANDOFF](../../AI_HANDOFF.md)、[CHANGELOG](../../../CHANGELOG.md) | 当前事实更新 | 既有文件本轮修改 |
| REQ-013 | Plane 项目与操作边界 | [PLANE_WORKFLOW.md](../../PLANE_WORKFLOW.md) | 增加 skill/本轮回执入口 | 既有文件本轮修改 |

## 2. 保留的历史权威资料

下列均为历史引用，未重做业务验收；阅读时以文档日期/版本和最新交接为准。记录曾经的完成与未验项，不把其当成 0.7.0 新功能已实现的证明。

| 资料 | 用途与状态 |
| --- | --- |
| [需规 0.5](../../TOPIC_BUILDER_REQUIREMENTS.md)、[0.4 归档](../../TOPIC_BUILDER_REQUIREMENTS_0.4_ARCHIVE.md) | 既有需求基线原文，本轮不改；0.5 原文 SHA256 见验证 |
| [产品原型说明](../../TOPIC_BUILDER_PROTOTYPE.md)、[原型文件](../../prototypes/topic-builder/index.html) | 既有原型与历史验证；不能替代本次多轮交互原型确认 |
| [实施设计](../../TOPIC_BUILDER_IMPLEMENTATION.md)、[代码模块设计](../../TOPIC_BLOCK_LANGUAGE_DESIGN.md) | 已实现私人草稿与模块边界；新增契约仅在沟通方案中提出 |
| [首轮验证](../../TOPIC_BUILDER_VALIDATION.md)、[第二轮变更](../../TOPIC_BUILDER_ITERATION_2.md)、[第二轮验证](../../TOPIC_BUILDER_ITERATION_2_VALIDATION.md) | 既有自动化、隔离浏览器与真实模型证据；不得重复消耗已用预算 |
| [0.6.0 发布记录](../../RELEASE_0.6.0.md) | 发布、备份恢复及运行边界；本轮不重验运行态，不向 Plane 上传完整记录 |
| [技术历史](../../TECHNICAL_HISTORY.md)、[开发参考](../../DEVELOPMENT_REFERENCES.md)、[需求管理方案](../../REQUIREMENTS_HUB_PLAN.md) | 选型与管理背景，不能覆盖当前源码/AGENTS/交接 |

## 3. 大体积证据和寿命

已有日志、截图、真实 AI 报告与发布备份的位置保留在对应验证/发布文档。`target/`、`data/` 已在 .gitignore 中，可能被清理或仅留本机；本轮未复制、移动、删除或上传这些文件，未重新验证其完整性。

本轮文档检查输出放在 `target/delivery-workflow-proof/`，同属可清理的忽略目录；检查方法、摘要与基线哈希保留在 VALIDATION.md。新业务原型、概设/详设、测试和截图尚未制作，不能给出不存在的交付链接。

后续若需持久保存忽略目录中的重要证据，先按授权指定受控位置制作脱敏审阅包与 SHA256 清单，再登记实际位置及验证。当前没有声称历史二进制证据已经永久归档。

本轮不生成或归档密钥、密码、OAuth、Cookie、完整模型输入输出、完整 Plane 响应或隐藏推理；仓库内已有历史资料不通过本索引复制到远程。
