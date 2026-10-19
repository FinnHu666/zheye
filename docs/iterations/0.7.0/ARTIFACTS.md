## FINNS-16：已按契约B1–B5实施，等待用户验收G7（2026-10-18）

用户要求“完成这个Plane上的任务”，AI据此（USER-ADMIN-D12）完成概设+自评设计评审、契约、后端/前端实施与验证；**未验收、未发布、未提交Git、未写Plane**。入口：[概设](DESIGN_USER_ADMIN.md)、[设计评审](DESIGN_REVIEW_USER_ADMIN.md)、[契约](CONTRACT_USER_ADMIN.md)、[实施与验证记录](IMPLEMENTATION_USER_ADMIN.md)、[用户验收清单V1–V10](ACCEPTANCE_USER_ADMIN.md)。

- 实现：V14迁移（账号状态、唯一站长系统角色与`owner_user_id`、扩展操作记录）；登录/会话/权限三处强制停用；停用/恢复与站长转交接口，全部在锁定`access_control_state`的同一事务内，操作者锁内重读；层级规则（站长>管理员>成员）接入既有角色/权限/角色定义接口；启动时站长初始化与自愈；前端三管理页共用外壳与弹窗。无新依赖，四处版本未改。
- 验证：H2全量107/107（基线88）；PostgreSQL隔离库全量106/106，新增两类在全新PG库各自通过（含并发）；V13复制库升级到V14演练通过；前端38/38与构建；变异检查5项均被抓到；本机浏览器（站长视角）停用/转交/审计/角色页与390px。**未验**：真人手机、多窗口F07专项演练、768px逐页、真实邮件。
- 验收环境：预览 <http://127.0.0.1:8091/admin/users> 运行中（PID见`target/account-preview.pid`），隔离库`finns_test_070_useradmin_upgrade`，演示账号owner@demo.finns.test等（`scripts/seed-user-admin-preview.*`重置）；用完停止：`scripts\start-account-preview.ps1 -DatabaseName finns_test_070_useradmin_upgrade -Stop`。8080与`finns`主库未触碰。
- 待用户：①按清单V1–V10验收并反馈；②生产站长账号与“不回退旧包”已由用户决定（USER-ADMIN-D13，邮箱不入库；发布时经`FINNS_BOOTSTRAP_ADMIN_EMAIL`配置），发布仍需另行授权；③[USER-ADMIN-P03](../../requirements/FOUNDATION_USER_ADMIN_PLANE_P03.md)已获用户单项确认并执行：FINNS-16 Plane状态Backlog→In Progress并追加实施进度正文，写前重读、写后回读通过（[回执](../../requirements/FOUNDATION_USER_ADMIN_PLANE_P03_RECEIPT.json)），Cycle11项与父项不变，Release成员HTTP402仍未核实；**不是Done**；④是否授权隔离上下文做独立设计评审（现为同作者自评）；⑤是否提交Git。
- 注意：旧包不认识停用状态；PG隔离测试每次用新库名；新增文件含`scripts/seed-user-admin-preview.ps1`（带BOM，已在PowerShell 5.1运行）。构建输出`src/main/resources/static/`已重新生成。

## FINNS-16：P02同步回读完成，下一步G3（2026-10-18）

USER-ADMIN-P02差异修订1获用户“确认写入”，写前核对一致后仅正文更新一次并回读通过；[脱敏回执](../../requirements/FOUNDATION_USER_ADMIN_PLANE_P02_RECEIPT.json)保存正文比较、21项保护字段、Cycle11项前后集合及Release HTTP402限制。Backlog/其余字段不变，未写关联，Release当前成员无法核实。需求修订2与R3确认有效，G1/PLANE/G2通过，下一步概要设计与设计评审、详设/接口和验收映射；G3以后尚未开始，业务验收A01–A15全部未执行。

本轮无业务/原型源码、数据库、主站或发布版本变化，未提交推送发布；仅同步正文与归档，未重跑原型/业务测试。P01/P02不重复执行，下方待P02授权措辞均为此前历史；其他功能（含FINNS-15）的门禁保持。

## FINNS-16：R3及范围已通过（2026-10-18）

用户在[R3展示](PROTOTYPE_USER_ADMIN_R3.md)后回复“通过”（USER-ADMIN-D11），需求修订2、G1增量与G2均通过，勿重复索要R3批准。站长转交候选/原因与邮箱确认/原站长降级/会话/审计按R3冻结；业务UA-A01–A15仍全部未执行。P02完整正文差异修订1已备妥，待单项核对写入授权，本轮未连接Plane；下一步处理P02，然后概要设计/设计评审与详设契约，不因原型批准直接开发或发布。仅本地文档记录，本轮未重跑原型测试/浏览器，历史25项模拟及构建证据保留；代码、数据库、主站和版本均未改。下方待R3评审为历史。

## FINNS-16用户管理R3（2026-10-18）

[当前R3/确认来源/待评审方案/验证](PROTOTYPE_USER_ADMIN_R3.md)为入口。用户已确认站长管理边界及页面转交方向；R3提供独立交接、二次确认、失败一致性、原站长降级/会话及审计。25项模拟测试、构建和940/390px浏览器检查通过，证据在忽略目录`target/prototypes/user-admin-r3-proof/`，32文件快照摘要见R3。G1具体转交方案/PLANE/G2待办，业务验收全部未执行，未写Plane或改业务。R1/R2及其历史未答表述不再代表当前状态。

## FINNS-16用户管理R2修订（2026-10-18）

[R2记录/三条批注映射/两项待决/验证](PROTOTYPE_USER_ADMIN_R2.md)为当前入口；新增共用AdminLayout/AdminDialog，站长与管理员限制仅浏览器模拟。22项模拟测试、构建及940/390px布局测量通过，截图/测量/源码快照在忽略目录`target/prototypes/user-admin-r2-proof/`。R1记录保留历史，不支撑新代码。原型和详细站长边界未获整体确认；G1新增范围/PLANE/G2仍待办，真实业务验收未执行，未写Plane。

最终归档验证：10处新增区域本地链接可达，31个原型源码/测试文件与R2快照哈希一致；940/390px三页标题坐标相同、文档不溢出，关闭按钮滚动前后坐标一致。进度检查器退出2，F07停在G1新增站长细节待确认，其余任务状态不变，证据哈希有效；`git diff --check`退出0，原型HTTP200。生产业务及四处版本文件无改动。

## FINNS-16用户管理原型R1（2026-10-18）

| 阶段/用途 | 产物 | 状态与来源 | 保存方式 |
| --- | --- | --- | --- |
| G3概设与评审 | [概要设计](DESIGN_USER_ADMIN.md)、[设计评审](DESIGN_REVIEW_USER_ADMIN.md) | 2026-10-18；同作者自评、带条件通过（C-02/C-04已在G6关闭，C-01/C-03转G8，C-05不采纳，C-06关闭） | 持久，无敏感信息 |
| G4详设契约 | [契约](CONTRACT_USER_ADMIN.md)（V14、接口、错误、测试映射）、[USER-ADMIN-D12](../../requirements/FOUNDATION_DECISIONS.md) | 2026-10-18；实施授权按用户“完成这个Plane上的任务”的理解记录 | 持久 |
| G5/G6实施与验证 | [实施与验证记录](IMPLEMENTATION_USER_ADMIN.md)；`src/main/resources/db/migration/V14__account_status_and_site_owner.sql`、`src/test/java/com/aitome/UserStatusTests.java`、`OwnerTransferTests.java`、`scripts/seed-user-admin-preview.*`、前端`AdminLayout/AdminDialog/admin.css`及三管理页 | 2026-10-18；H2 106/106，PG 106/106，浏览器主路径通过；未提交Git | 持久；工作树未提交 |
| G7验收清单 | [ACCEPTANCE_USER_ADMIN.md](ACCEPTANCE_USER_ADMIN.md) V1–V10 | 待用户填写结果 | 持久 |
| PLANE待确认 | [USER-ADMIN-P03差异](../../requirements/FOUNDATION_USER_ADMIN_PLANE_P03.md) | 只读核对后备妥，未执行 | 持久 |
| G2产品原型 | [R1记录/验证/体验步骤](PROTOTYPE_USER_ADMIN.md)、[源码及运行入口](../../prototypes/site-access/README.md) | 本轮USER-ADMIN-D07授权制作，待用户确认具体R1/范围；需规修订1不变 | 本地持久文档与原型源码，均为模拟 |
| 验证证据 | `target/prototypes/user-admin-r1-proof/`中的测试/构建日志、截图和源码快照 | 最终21/21模拟测试、构建退出0；浏览器主路径与1180/768/390px检查 | 忽略目录，可清理重建；快照摘要见R1记录 |

G0/G1/PLANE既有通过，G2仍pending，未开始G3后业务。本轮未连接/写Plane，R1进度后续正文增量另行核对与确认；不把模拟证据写成真实认证、并发、邮件、数据库或真人验收。

最终归档检查：14处本轮新增区域的本地链接可达，29个原型源码/测试文件与最终快照哈希一致；全部进度证据哈希有效，其他任务门禁状态与本轮HEAD一致。进度检查器退出2（真实待办），FINNS-16停在G2等待用户确认R1；`git diff --check`退出0。业务及四处版本文件无改动，入口HTTP200。

## FINNS-16同步执行产物（2026-10-18）

| 阶段/用途 | 产物 | 修订/状态 | 来源与保存 |
| --- | --- | --- | --- |
| PLANE执行回执 | [USER-ADMIN-P01回执](../../requirements/FOUNDATION_USER_ADMIN_PLANE_RECEIPT.json) | 差异修订1，succeeded/verified | 本轮“确认写入”；仅必要脱敏元数据/内容哈希，无完整工具响应 |
| 原差异与确认 | [执行记录及差异](../../requirements/FOUNDATION_USER_ADMIN_PLANE_SYNC.md)、[精确载荷](../../requirements/FOUNDATION_USER_ADMIN_PLANE_SYNC.json)、[USER-ADMIN-D06](../../requirements/FOUNDATION_DECISIONS.md) | 已执行，不重复申请 | G1/PLANE通过，G2原型待制作；需求修订1范围不变 |

回读：正文规范化一致，19项保护字段保持Backlog等原值；Cycle完整11项集合不变。Release成员HTTP 402未核实，未执行关联写操作。未产生原型、业务测试或部署证据。

本轮执行后验证：19处新增区域本地链接可达；原批准Markdown正文、JSON载荷及回执SHA-256一致；一次写入/回读结果与本地状态吻合；全部进度证据哈希有效。进度检查器真实退出2（后续门禁待办），FINNS-16下一门禁为G2，G0/G1/PLANE通过。`git diff --check`退出0，业务及四处版本文件无改动。未运行前后端测试、构建、浏览器或数据库验收。

## FINNS-16需求归档与同步准备（2026-10-18）

| 阶段/用途 | 产物 | 修订/状态 | 来源与保存 |
| --- | --- | --- | --- |
| G0/G1已确认需求 | [F07需规](../../requirements/FOUNDATION_USER_ADMIN_REQUIREMENTS.md)、[确认决定](../../requirements/FOUNDATION_DECISIONS.md) | 修订1，G1通过，USER-ADMIN-D01–D05 | 本轮用户分组选择/整项批准；脱敏，本地持久文件 |
| 后续验收映射 | [UA-A01–UA-A12](ACCEPTANCE_USER_ADMIN.md) | 修订1，全部未执行 | 本轮生成；不是业务通过证据 |
| PLANE检查点 | [USER-ADMIN-P01差异与完整待写稿](../../requirements/FOUNDATION_USER_ADMIN_PLANE_SYNC.md)、[精确JSON](../../requirements/FOUNDATION_USER_ADMIN_PLANE_SYNC.json) | 差异修订1，待单项确认、未写入 | 本轮只读核对；无凭据或完整远程响应；Release成员HTTP 402 |

本轮只完成需求记录及管理差异准备。原型/概设/契约/业务测试证据均待产生，不创建占位实现或伪造回执。历史FINNS-15条目不被本项进度覆盖。

本轮文档验证：44处新增区域本地链接可达；待写JSON与Markdown完整正文一致，参数仅包含action/project_id/workitem_id/description_stripped，confirmed/executed均false；10条需求规则与12项验收映射存在。`git diff --check`退出0。只读进度检查器按协议退出2（真实待办），FINNS-16下一门禁为PLANE；全部通过门禁证据路径/哈希有效，其他任务门禁状态与HEAD一致。PLAN/REQUIREMENTS仅新增F07入口、DECISIONS新增F07决定及Q03结论，原统计/权限条款与账号批准未改变，受影响共享证据经语义复核后刷新哈希。四处发布版本文件无改动。未执行业务测试、构建、浏览器、数据库或真人手机验收。

## 账号概设修订2与设计评审（2026-10-18）

[概设修订2](DESIGN_ACCOUNT.md)（公共邮件服务商）、[设计评审记录](DESIGN_REVIEW_ACCOUNT.md)；评审规则在 `.agents/skills/finns-delivery-workflow/references/design-review.md`。仅文档与 skill，未提交；服务商免费额度为 2026-10-18 官方页面读取值，会变。

## FINNS-15账号实施（2026-10-18）

[实施与验证记录](IMPLEMENTATION_ACCOUNT.md)含文件清单、偏差、验证结果、事故与未验项；[契约修订2](CONTRACT_ACCOUNT.md)已回写实施偏差。代码改动未提交，验证jar仅为本地产物。

## FINNS-15账号开发设计（2026-10-18）

[概要设计修订1](DESIGN_ACCOUNT.md)（G3）与[详设/接口契约草案修订1](CONTRACT_ACCOUNT.md)（G4待实施授权）基于原型R2.1和账号需规修订7。仅文档：未实施、未运行测试、无Plane写入。BCrypt行为依据本机会话临时目录中读取的Spring Security 6.3.6源码，未入库。

## R2.1账号安全邮箱重置入口（2026-10-18）

[当前原型记录](ACCOUNT_PROFILE_R2_REVIEW.md)补充已登录忘记原密码入口、当前邮箱绑定、申请/完成会话区别及本轮验证。12项模拟测试和Vite构建通过；浏览器申请保持登录、刷新冷却、邮件重置后退出及390px面板无横向溢出已核对。仅本地原型，真实邮件/业务/数据库未验，Plane待同步且本轮未操作，具体原型R2.1待用户确认。截图与源码哈希位于忽略目录target/prototypes/account-r21-proof，可重建。

## 2026-10-18账号与资料局部原型R2

[当前变更、用户批注映射、评审步骤和验证](ACCOUNT_PROFILE_R2_REVIEW.md)为本轮持久记录；原site-access源码继续使用，新ProfilePage和validation辅助文件位于其src下。R2待用户确认，F05整体G1未通过；账号需规修订7记录用户已明确的注册顺序/密码上限变更，旧修订5快照保留。Plane上次回执只覆盖修订6，本轮没有远程操作，新差异待处理。

11项模拟测试及构建通过；浏览器注册先验证、无原生校验气泡、资料只读/独立编辑/保存失败保留/取消、会话失效换账号隔离、桌面手机平板已检查；实际邮件/后端/真实库/真人手机未验。最终引用/证据和范围检查另见本轮结果。截图及源码SHA256快照位于忽略目录target/prototypes/account-r2-proof，可清理重建。

## FINNS-15账号原型R1（2026-10-17）

[原型记录/评审步骤/验证](PROTOTYPE_ACCOUNT.md)、[现有原型扩展源码入口](../../prototypes/site-access/README.md)已形成。G2待用户确认R1，模拟测试7/7、构建和浏览器桌面/手机检查通过；没有业务交付或Plane写入。详细边界和最终检查见[基础索引](../../requirements/FOUNDATION_ARTIFACTS.md)。

## 本轮已执行的Plane回执（2026-10-17）

[15项回执](FOUNDATION_PLANE_RECEIPT.json)记录本轮确认、6项更新、7项创建、2项关联及每项回读；全部succeeded/verified。只有必要元数据、实际ID及检查结果，不保存完整工具响应。Cycle/Release各11项，八子项G1待评审；下方“零写入/待确认”是准备阶段记录。

## 当前地基规划产物（2026-10-17）

[计划修订5](PLAN.md)、[需规汇总修订7](REQUIREMENTS.md)、[基础索引](../../requirements/FOUNDATION_ARTIFACTS.md)为当前入口。[Plane逐项差异](FOUNDATION_PLANE_DIFF.md)及[精确载荷](FOUNDATION_PLANE_DIFF.json)是本轮生成的脱敏管理草稿，零写入、待逐项确认；不含完整工具响应/凭据。以下原交付证据保留，不因新范围重写已完成行为。

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
