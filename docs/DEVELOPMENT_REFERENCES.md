# 开发规范研究依据

检索日期：2026-09-29（补充 OpenAI Skill 设计/评估、工程评审与秘密管理资料）。只采用官方规范、官方产品文档与 OWASP 原始资料。未下载或执行网络上的第三方 skill。项目 skill 是基于下列依据和当前代码编写的项目约定，不声称存在一份通用 skill 能保证所有 AI 自动遵循。

| 来源 | 采用的原则 | 应用位置 |
| --- | --- | --- |
| [OpenAI：Skills](https://developers.openai.com/plugins/concepts/skills) | Skill 是可复用工作流：用简明名称/描述定位适用请求，将操作步骤与输出要求放入说明，可选资源只在需要时加载 | FINNS Skill 保留项目专属触发边界，正文定义规划/实施/诊断流程，不复制通用手册 |
| [OpenAI：系统化评估 Agent Skills](https://developers.openai.com/blog/eval-skills) | 用真实场景检查 Skill 是否按预期触发；纳入正例和不应触发的相邻负例，检查实际行为而非只看生成文本 | Skill 流程修改时做规划边界与实现边界的正反例检查；复杂改动再考虑建立独立 eval 集 |
| [Spring Boot 3.3 数据库初始化](https://docs.spring.io/spring-boot/3.3/how-to/data-initialization.html) | 使用一种结构迁移机制，配置 ddl-auto；PostgreSQL 需要数据库特定 Flyway 模块 | PostgreSQL/Flyway 方案 |
| [PostgreSQL SQL Dump](https://www.postgresql.org/docs/current/backup-dump.html) | 逻辑导出及恢复作为备份手段；版本兼容按执行时文档核实 | PostgreSQL 恢复演练；并不用于直接导出 H2 |
| [Flyway Engine Release Notes](https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine) | Flyway 10.20.0 加入 PostgreSQL 17 支持；项目采用 10.20.1 并在隔离 PostgreSQL 上复验 | 消除 Flyway 10.10 对 PostgreSQL 17 的支持范围告警 |
| [Vue Router 导航守卫](https://router.vuejs.org/guide/advanced/navigation-guards) | 路由进入前判断并重定向 | 私有页面登录回跳 |
| [Vue Router 路由元信息](https://router.vuejs.org/guide/advanced/meta) | 路由元数据承载登录要求 | requiresAuth 规划 |
| [OWASP 授权指南](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) | 每次请求验证权限，按资源归属控制 | /api/me 和专题操作 |
| [Semantic Versioning 2.0.0](https://semver.org/) | 版本表达兼容性与变更类型；0.x 为初始开发阶段 | FINNS 候选版本分步发布 |
| [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) | 人可读的版本变化、发布日期与 Unreleased | 已有 CHANGELOG 流程 |
| [Google Engineering Practices：Code Review 标准](https://google.github.io/eng-practices/review/reviewer/standard.html) | 变更应改善整体代码健康；在前进速度与重要质量要求间做清晰取舍 | 以小而可验证的闭环实施，避免顺手扩大重构范围 |
| [Google Engineering Practices：Code Review 检查项](https://google.github.io/eng-practices/review/reviewer/looking-for.html) | 按改动选择单元/集成/E2E 测试；检查测试在行为损坏时是否会失败 | FINNS Skill 的风险匹配验证与测试有效性要求 |
| [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html) | 凭据需要最小权限、明确生命周期；避免日志泄漏并记录如何管理 | 数据库与本地服务配置不得提交真实凭据；脚本及验证输出不得泄密 |

版本注意：当前实际项目是 Boot 3.3.8，3.3 文档入口展示该系列后续补丁版本，不代表本轮升级依赖；“current”资料也不能当成锁定依赖版本。实现前核对选定 PostgreSQL、JDBC 和 Flyway 的兼容矩阵。

由本项目决定而非来源直接规定的事项：采用 PostgreSQL、/my/collections 路径、个人中心字段、候选发布号、性能指标。它们是可调整的工程方案。AGENTS.md 固定跨会话入口，skill 固定工作流程，需求文档固定验收，交接文档固定当前状态；采用这些分工是为了减少重复口述和冲突。

本次网络复核只抽取能落到 FINNS 流程中的原则，并非照搬组织级流程：Skill 本身明确适用范围、可重复工作流及输出契约；用正反例发现触发和权限边界漂移；保持变更范围可审阅、测试与生产代码同一迭代交付、测试要能捕获目标行为回归、敏感配置遵循最小暴露。外部资料不能取代本项目的数据库、版本、路由、数据保护和授权约束。

