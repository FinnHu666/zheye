# FINNS 项目协作约定

本文件是本项目后续 AI 的固定入口。适用项目根目录及其子目录。

## 先读什么

1. 阅读 .agents/skills/finns-development/SKILL.md。
2. 执行新需求前阅读 docs/NEXT_ITERATION_PLAN.md；接手任务阅读 docs/AI_HANDOFF.md。
3. 以当前源码、VERSION、CHANGELOG.md 确认实际实现；历史 DEVELOPMENT_PLAN.md 仅供背景参考。

## 持续有效的约束

- 产品名 FINNS；当前基线为 Java 17、Spring Boot 3.3.x、Vue 3、Vue Router、Vite。技术栈升级须有需求依据和记录，不能因切换 AI 自行改成 Next.js 或静态 HTML 页面。
- 一级导航对应独立路由和页面；兼顾直达、刷新、前进后退和移动端。筛选控件可留在同一页面。
- 私人本地工具清单不因普通账号登录而开放，不展示在公共个人主页。
- 需求记录、方案假设、实际完成和验证结果必须区分。规划完成不能记为功能上线。
- 仅规划的任务不连接新数据库、不迁移业务数据、不改业务功能。实施请求则按已授权范围继续完成。
- 发版同步 VERSION、pom.xml、frontend/package.json、frontend/package-lock.json，记录 CHANGELOG.md；未发布的改动放 Unreleased。
- 每轮结束更新交接文档的状态、下一步和验证证据；没有执行的检查必须明确标记。
- 本机缺少依赖时优先使用用户指定的 D:/E: 工具目录或项目级可复现运行时；不得把工具安装到 C:。需要安装但无法遵守路径/授权约束时，先提供便携/项目级替代或报告阻塞。
- 不自动初始化、提交、推送 Git 或发布远程服务。本项目目前没有 Git 仓库，若状态变化须据实更新。

