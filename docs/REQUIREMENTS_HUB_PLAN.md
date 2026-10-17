# FINNS-REQ-013：需求收集、评估与项目安排方案

日期：2026-10-03；更新：2026-10-04。状态：实施中（Plane Cloud OAuth、项目级 MCP 配置、收件箱项目和基本工作项读写已验证；Plane 中现有 `FINNS` 项目是示例项目，真实执行项目及跨项目安排待确认）。

## 2026-10-04 用户评审决定（优先于下方历史候选）

- 已确认：使用现成工具，独立于 FINNS 管理项目；目前仅个人使用；本地与远程按方便程度选择，长期明确远程使用。
- 因此取消 FINNS 原生模块实施方向，下方自建页面、模型和 MCP 工具仅保留为历史参考，不是后续开发任务。REQ-013 仅作为外部工具选型的历史追踪，不代表 FINNS 产品待开发功能。
- 本轮按 Plane Cloud + 官方远程 MCP 落地：网页和需求数据从第一天远程使用，免去自建服务维护；是否长期采用仍以真实使用体验为准。
- 官方有免费云端档位，先验证网页录入、搜索、状态、项目安排与 MCP 新增/查询；所选套餐的实际功能和网络可达性仍需登录试用，不承诺免费覆盖全部功能。首期不用高级自定义字段、自动化或付费迭代能力作为硬依赖。
- 当前组织：私人工作区 `finns`（`9626c056-e247-4a76-97f1-96802796a4da`）；“需求收件箱”项目 `INBOX`（`47daf7fb-95c7-4905-bcad-93a191a78e16`）。唯一 identifier 为 `FINNS` 的现有项目（`587f13f8-9bd0-4ab2-933c-e3ace27f647f`）描述为 Plane Demo Project，不是已确认的本地 FINNS 执行项目。MCP 没有工作项跨项目转移动作；Plane Web 的转移能力尚未实测。
- MCP 优先官方 HTTPS OAuth 接入；若当前客户端远程连接不方便，可用本地 stdio 适配器连接同一个 Plane Cloud 工作区，数据仍在远程。自托管是后续对数据控制或成本有明确要求时的替代路径；Cloud 到自托管的数据迁移不假定完整无损。
- 验证结果：OAuth 登录成功；MCP 可读取工作区与项目；“需求收件箱”项目创建并回读成功；测试工作项 `FINNS-8` 在 Plane Demo Project 中创建、读回、设为 Cancelled、可恢复归档及从活动列表排除均通过。测试项留在 Archived 中以便恢复，未做不可恢复删除。Plane 页面端手工录入、真实 FINNS 执行项目、跨项目安排以及长期网络/套餐体验尚未实测。
- 官方依据（2026-10-04 查阅）：https://plane.so/open-source 、https://plane.so/pricing 、https://developers.plane.so/dev-tools/mcp-server 。

### 本地项目接轨实施记录（2026-10-04）

- 已新增 `.codex/config.toml`：当前 FINNS 仓库在 Codex 受信任后注册 Plane 官方 HTTPS MCP，OAuth 已登录，写操作审批策略为 `writes`；未在仓库保存令牌。
- 已新增 `docs/PLANE_WORKFLOW.md`：首连步骤、收件箱/项目约定、将 Plane 工作项映射到本地 FINNS 需求记录及验收证据的流程。
- 已更新根 `AGENTS.md`，纠正 Git 状态并加入按用户指令操作 Plane 的规则；CHANGELOG 和 NEXT_ITERATION_PLAN 记录实施状态。
- `codex mcp list` / `codex mcp get plane` 显示项目配置已加载，Plane 状态为 `OAuth`；`git diff --check` 和仓库 endpoint/审批策略检查通过。通过受信任项目配置启动的 Codex CLI 已真实调用 `workspace`、`project`、`state`、`workitem` MCP 工具。
- Plane 写入：在既有工作区创建“需求收件箱”项目并回读校验；在描述为 Plane Demo Project 的示例项目创建 `FINNS-8`，按 UUID 读回后设为 Cancelled 并可恢复归档。归档列表可见且 `deleted_at=null`，活动列表不包含它；未做不可恢复删除，没有修改其他工作项或真实 FINNS 需求。
- 当前已打开的桌面对话未热加载 Plane 工具；重新打开或刷新已信任的仓库会话以重新发现项目 MCP。CLI 验证时环境中的 `CODEX_API_KEY` 覆盖了 ChatGPT 登录，因此在该进程临时清除；当本机审批策略为 `never` 时，测试调用使用 `--approve-for-me`。常规对话仍按项目 `writes` 审批策略逐项审批。
- 未改 FINNS 业务代码，未连接 FINNS 数据库，未运行 FINNS 构建或业务测试（与本轮文档/外部 MCP 配置范围不匹配）。Plane Web 手工录入与跨项目流转尚未实测。继续前需逐项确认是否新建真实 FINNS 执行项目（identifier 与现有示例项目冲突）及是否运行 INBOX→新项目的临时工作流测试。

## 用户原意与边界

在网页中快速记录未来需求，之后评估是否值得做，再安排到项目；允许 AI 通过 MCP 快速添加和查询需求。用户已选择独立现成工具、个人使用、长期远程；当前按 Plane Cloud 官方 MCP 连接进行接轨，最终采用以试用验收为准。

方案假设：先服务个人、可管理多个项目；原始需求默认私有；记录不等于批准开发。非目标：不在 FINNS 中开发内置需求管理功能、不连接或迁移 FINNS 数据库、不自动启动编码或部署。

## 开源选择与推荐

优先用 Plane 验证流程：其开源项目管理产品已有官方 MCP，可管理项目、工作项与周期。设置一个“需求收件箱”项目，所有随手记录先进入该项目；评估后转入目标项目并安排周期。转移能力和字段保留须在所选版本验收；无法转移时创建目标工作项并保存双向引用和唯一映射，禁止重试重复创建。

Plane 社区版与商业版功能不同，MCP 工具出现不表示当前版本可执行全部操作。官方文档对社区版推荐本地 stdio；OAuth 应用注册适用于 Cloud/Commercial Edition。若要求自托管社区版加远程 HTTP MCP，需额外验证受认证的网关方案，不将官方云端地址当成本地实例代理。

Fider 适合反馈、建议征集与优先级讨论；单独使用不作为完整项目执行方案。本次未验证其官方 MCP 能力。若主要服务个人且希望统一 FINNS 登录和交互，可在现有 Java 17 / Spring Boot 3.3.x / Vue 3 上自建轻量模块；不替换现有技术栈。

建议：先以 Plane + 官方本地 MCP 跑通真实流程；只有明确需要 FINNS 原生入口或特殊流程时，再选择自建。第一期只保留一个权威数据源，避免 FINNS 和 Plane 双向同步。

## 交互与数据草案（自建选项）

- 需求收件箱 `/me/requirements`：快速输入原文即可保存；标题可建议生成，项目和优先级可稍后补充。保存后返回稳定 ID 与详情链接。
- 需求详情 `/me/requirements/:id`：保留原文；单独记录整理说明、场景、价值、成本、验收条件、评估结论及理由。支持搜索、标签、暂缓、拒绝和重复需求关联。
- 项目页 `/me/projects` 与 `/me/projects/:id`：项目需求池、执行看板、迭代；确认采用后安排需求并关联执行任务。
- 集成设置 `/me/integrations`：管理 MCP 接入和授权范围，不向普通账号开放本地私人工具。

需求决策：待评估 → 已采纳 / 暂缓 / 不采纳。执行进度独立记录：未安排 → 已安排 → 实施中 → 待验收 → 完成。AI 完成代码只能进入待验收；完成需保存验收证据。项目文档仍遵循既有“已记录 / 待用户确认 / 待实施 / 实施中 / 已实施 / 验收通过 / 暂缓”协议，不与产品内双维度状态混淆。

概念实体：Project、Requirement、Iteration、Task、Activity。Requirement 保留原文、来源、创建者、归属、revision、评估与决策；Task 引用 Requirement，允许一条需求拆成多项任务。先支持一个主归属项目，跨项目关联后续处理。永久删除和复杂多人审批不列入首期。

网页和 MCP 共同调用同一业务服务，统一鉴权、归属、状态转换及审计。服务端从登录会话或集成凭据解析身份；客户端不能传 ownerId 越权。新增独立路由时同步 SPA 深链接回退。

## MCP 工具草案

以下为自建接口设计，并非已存在工具：

| 工具 | 用途 |
| --- | --- |
| capture_requirement(text, project_id?, source?, idempotency_key) | 保存原文并返回 ID、链接，默认待评估 |
| search_requirements(query, filters?, cursor?) | 搜索历史需求、辅助去重 |
| get_requirement(id) | 获取原文、评估、任务和历史 |
| list_projects(cursor?) | 查询可访问项目并消除同名歧义 |
| update_requirement(id, expected_revision, patch) | 更新整理信息，冲突不覆盖 |
| schedule_requirement(id, project_id, iteration_id?, expected_revision) | 仅允许已采纳需求进入执行安排 |

首期授权范围仅查询和新增待评估需求；评估建议不直接修改采用决定。后续再开放明确授权的编辑和安排。项目名称无法唯一匹配时保存到收件箱，不猜归属。幂等键与外部来源标识防止网络重试重复写入；相似内容只提示，不自动合并。

本地 AI 可使用 stdio 适配器访问本机 API，无需公网。外部客户端访问需其能到达的 HTTPS Streamable HTTP MCP 服务，配置认证、项目范围、凭据撤销与操作记录；“对外提供 MCP”不等于公开业务数据。具体 SDK 与 Java 17/Boot 3.3 兼容性在实施选型时验证，必要时用独立适配进程，不为 MCP 升级整个 FINNS。

## 实施顺序与验收

1. 选型试用：选定 Plane 或自建，以及本地或远程接入；确认需要的功能在所选版本可用。
2. 收集闭环：网页录入、刷新后可读、搜索和详情；首次只需原文即可保存。手机可用，账号间不可越权。
3. 决策闭环：采纳/暂缓/不采纳及理由，安排项目和周期，关联任务，保留原始需求与决策历史。
4. MCP 闭环：AI 新增返回可打开链接；网页与工具读到同一记录；相同幂等键重复调用只产生一条；越权被拒绝。
5. 交接闭环：按项目导出已批准需求、验收条件和上下文给执行 AI；完成回写附证据并待人工验收。不自动派发编码、提交或部署。

上述为 2026-10-03 初始候选；现成独立工具及长期远程方向已于 2026-10-04 确认，后续按本文最新评审决定推进，不再询问是否开发 FINNS 原生模块。

## 来源与本轮验证

2026-10-03 查阅官方资料，未部署实测第三方产品：

- Plane 官方 MCP 文档：https://developers.plane.so/dev-tools/mcp-server
- Plane 官方 MCP 源码（MIT）：https://github.com/makeplane/plane-mcp-server
- Plane 开源产品：https://github.com/makeplane/plane
- Fider 官方仓库及自托管说明：https://github.com/getfider/fider

已检查当前需求登记无 REQ-013、VERSION 为 0.5.1、Maven Java 17 与 Spring Boot 基线、最新交接记录。Git 实测 `git rev-parse --show-toplevel` 返回本项目，目录现在是 Git 仓库；开始本轮时已有 `docs/AI_HANDOFF.md` 未提交修改，予以保留。没有执行应用构建、业务测试、数据库检查或第三方连通性测试。
