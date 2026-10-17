# FINNS

技术栈演进、关键选择的来由及历史方案边界见 [技术历史记录](docs/TECHNICAL_HISTORY.md)。

开发与 AI 接手入口：[项目约定](AGENTS.md)、[下一阶段需求方案](docs/NEXT_ITERATION_PLAN.md)、[AI 交接](docs/AI_HANDOFF.md)、[项目开发 skill](.agents/skills/finns-development/SKILL.md)。代码版本以 VERSION 为准；运行实例与数据库状态以最新交接为准。

产品迭代主线见 [版本路线图](docs/RELEASE_ROADMAP.md)：当前已发布 0.6.0，规划中的 0.7.0 为 AI 沟通与基础访问管理，0.8.0 为自定义评价模块与租房私人验证。目标版本不代表已开发/发布；需求 ID 仅内部追踪，文档使用修订号。

当前本机主站版本：`0.6.0`，访问 [8080 主站](http://127.0.0.1:8080/)（未远程发布）。FINNS 使用 Java 17 + Spring Boot 3 和 Vue 3，支持文章与话题、经验专题、讨论及会话登录。专题可通过粘贴、TXT/Markdown 文件、场景模板或本人专题复制创建，先保存私人工作稿，再主动发布。顶部“开始创作”提供专题入口；新建时可直接写第一段正文，小标题可选。

## AI 创建专题（0.6.0）

“我的专题”新增“AI 创建专题”，也可直达 `/me/collections/ai/new`。问卷确认后生成候选初稿，接受后编辑、预览并明确保存私人草稿；再次打开进入 `/me/collections/:id/builder`。支持自由画布、手机阅读顺序堆叠、HTML + CSS 模块。新编辑器首轮仅保存私人草稿；既有直接编写、导入和旧编辑器保留。

个人中心的 [AI 配置](http://127.0.0.1:8080/me/ai-settings) 保存本人 DeepSeek Key；后端通过独立 `FINNS_AI_CREDENTIAL_SECRET`（32 字节随机值的 Base64）加密持久化。仅使用当前账号 Key，不回退 `DEEPSEEK_API_KEY`，不自动分配历史共享 Key。可选 `FINNS_AI_BASE_URL`（默认 `https://api.deepseek.com`）与 `FINNS_AI_MODEL`（默认 `deepseek-flash`）。加密主密钥只配置在服务端环境并需备份；个人 Key 不回传浏览器，保存配置不调用模型。沟通和有资料生成会将本次输入发送给 DeepSeek，未配置时明确报错，无模拟回退。无资料可生成明确标为待补充的结构。

2026-10-10 按用户确认部署到本机 8080 / 5432 主库，V9/V10 已应用；备份恢复演练、13 张既有业务表校验和部署检查通过，见 [0.6.0 发布记录](docs/RELEASE_0.6.0.md)。8082 / 55439 继续作为隔离测试环境，测试账号、草稿和个人 Key 不导入主库。主站重启入口为 `scripts/start-local-main.ps1 -ReleaseDirectory data/deployments/20261010-topic-builder-0.6.0`，已有 8080 进程时脚本会拒绝重复启动；启动日志确认成功后才能认定恢复运行。

隔离验证仍可先构建和打包，再运行 `scripts/start-topic-builder-test.ps1 -DatabaseName finns_test_topic_builder_browser_20261009`。首轮技术验证与真实 DeepSeek 三例（6 次调用）通过；第二轮增加个人配置、可编辑提示和模块收藏复用，验证见 [第二轮记录](docs/TOPIC_BUILDER_ITERATION_2_VALIDATION.md)。真实触屏及跨浏览器尚未验证；完整结果见 [开发设计](docs/TOPIC_BUILDER_IMPLEMENTATION.md) 与 [验证记录](docs/TOPIC_BUILDER_VALIDATION.md)。

## 私人本地工具清单

访问 `/private/tools`，输入独立的私人密钥后查看本地 Skills 数量、说明、来源及更新时间，支持搜索、刷新和锁定。密钥只保存在页面内存，刷新页面后需要重新输入。普通网站账号不具备此权限，接口响应禁止缓存。

启动前在 PowerShell 设置 `$env:AITOME_TOOLS_KEY='你自行选择的长随机密钥'`。未配置密钥时接口默认拒绝访问。默认扫描 `D:/env/.codex/skills`；可用 `$env:AITOME_TOOLS_ROOTS='D:/env/.codex/skills;C:/Users/胡明飞/.agents/skills'` 指定多个目录。不会执行技能，也不会返回技能正文。远程部署时需使用 HTTPS 保护密钥。页面不加入公开导航，请直接收藏私人地址。

## 项目结构

- 后端：Java 17、Spring Boot 3，静态托管 Vue 构建产物。
- 前端：Vue 3、Vue Router、Vite，源代码位于 `frontend/`。
- 页面路由：`/`（发现）、`/collections`（专题）、`/vps`（VPS 指南）、`/private/tools`（私人本地工具）。一级导航必须对应独立路由和页面组件，不要把页面内容堆进首页锚点区。
- `scripts/run.ps1` 会在启动前构建 Vue 生产资源，确保运行页面与当前前端源码一致。

## 启动

Windows PowerShell：

```powershell
.\scripts\run.ps1
```

项目自带 `.mvn/settings.xml`，用于隔离本机 Maven 镜像配置并直接使用 Maven Central。启动后访问 <http://localhost:8080>。演示账号：`demo@aitome.dev`，密码：`aitome123`。

## 版本与迭代记录

- 根目录 `VERSION` 是 FINNS 的统一发布版本；发版时同步更新 `pom.xml`、`frontend/package.json` 和 `frontend/package-lock.json` 中的版本。
- 每次代码迭代先在 `CHANGELOG.md` 的 `Unreleased` 下记录新增、变更、修复或安全事项；发版时将条目归档到版本号和发布日期标题下。
- 使用语义化版本：`MAJOR.MINOR.PATCH`。不兼容变更升 MAJOR，兼容功能升 MINOR，兼容修复升 PATCH。
- 变更应小步提交，并在提交说明中写明范围与目的；发版前运行 `scripts/test.ps1` 和 `scripts/package.ps1`，检查变更记录、版本号及部署配置。

## 测试与打包

```powershell
.\scripts\test.ps1
.\scripts\package.ps1
```

打包脚本固定使用项目的 Java 17，避免机器默认 JDK 版本导致构建失败。

数据库配置需按启动入口区分：直接使用 application.yml 时连接 data/ 下的 H2 文件库；scripts/run.ps1 会启动本机 PostgreSQL 并选择 postgres profile，结构由 Flyway 管理。Redis 和对象存储仍是 DEVELOPMENT_PLAN.md 中的后续候选，不属于当前运行栈。详见 [数据库说明](docs/DATABASE.md) 和 [技术历史记录](docs/TECHNICAL_HISTORY.md)。
