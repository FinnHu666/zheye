# FINNS 项目协作约定

本文件是本项目后续 AI 的固定入口。适用项目根目录及其子目录。

## 先读什么

1. 阅读 .agents/skills/finns-development/SKILL.md。
2. 执行新需求前阅读 docs/NEXT_ITERATION_PLAN.md；接手任务阅读 docs/AI_HANDOFF.md。
3. 以当前源码、VERSION、CHANGELOG.md 确认实际实现；历史 DEVELOPMENT_PLAN.md 仅供背景参考。
4. 需求排期、产品原型评审、开发交付、Plane 同步或 AI 产物归档时，按 [finns-delivery-workflow](.agents/skills/finns-delivery-workflow/SKILL.md) 执行阶段门禁及记录；普通问答不因此新增流程或远程操作。

5. 接手、继续开发、跨阶段及交付前，按交付 skill 的 references/progress-check.md 检查 docs/delivery-progress.json；说明当前阶段、证据缺口、AI 下一步及用户需确认事项。普通问答除外。

## 持续有效的约束

- 技术栈演进、选型依据与未采用方案见 [docs/TECHNICAL_HISTORY.md](docs/TECHNICAL_HISTORY.md)；它提供背景，不覆盖源码、版本文件或最新交接。
- 产品名 FINNS；当前基线为 Java 17、Spring Boot 3.3.x、Vue 3、Vue Router、Vite。技术栈升级须有需求依据和记录，不能因切换 AI 自行改成 Next.js 或静态 HTML 页面。
- 一级导航对应独立路由和页面；兼顾直达、刷新、前进后退和移动端。筛选控件可留在同一页面。
- 私人本地工具清单不因普通账号登录而开放，不展示在公共个人主页。
- 需求记录、方案假设、实际完成和验证结果必须区分。规划完成不能记为功能上线。
- 仅规划的任务不连接新数据库、不迁移业务数据、不改业务功能。实施请求则按已授权范围继续完成。
- 发版同步 VERSION、pom.xml、frontend/package.json、frontend/package-lock.json，记录 CHANGELOG.md；未发布的改动放 Unreleased。
- 需求汇总和排期按明确产品版本组织，以 docs/RELEASE_ROADMAP.md 及 docs/iterations/<version>/ 的计划/需规为入口；需求 ID 仅内部追踪，文档用修订号，不混同产品版本。当前迭代内功能必须有目标版本；规划不提前修改发布版本或宣称上线。
- 每轮结束更新交接文档的状态、下一步和验证证据；没有执行的检查必须明确标记。
- 新功能及显著交互变化先形成具体产品原型，并记录用户对版本和范围的确认，再进入对应业务开发；概设、详设/接口与验收映射在实施前齐备。已确认行为的小修复可复用原证据并记录适用理由，不能自行扩大为新功能。
- 新需求评审通过后、实际原型制作前，主动处理 Plane 同步检查点：提出所需授权及具体差异，逐项确认后写入/回读；无需变更或用户明确延期也须记录。需求批准不代替写入批准，等待时可准备原型资料，不静默跳过节点。具体规则见交付 skill，Plane 连接及写入仍遵守下方授权约定。
- 本机缺少依赖时优先使用用户指定的 D:/E: 工具目录或项目级可复现运行时；不得把工具安装到 C:。需要安装但无法遵守路径/授权约束时，先提供便携/项目级替代或报告阻塞。
- 不自动初始化、提交、推送 Git 或发布远程服务。本项目已纳入 Git；若状态变化须据实更新。
- Plane 是独立需求管理工具，使用说明见 `docs/PLANE_WORKFLOW.md`。仅当用户给出 Plane 工作项或要求操作 Plane 时才通过 MCP 查询/变更；先核实工作项内容，管理状态以 Plane 为准、实施和验收证据以本地项目文档为准。任何 MCP 写操作均须本轮用户明确提出并逐次确认。

