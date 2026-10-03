# FINNS

开发与 AI 接手入口：[项目约定](AGENTS.md)、[下一阶段需求方案](docs/NEXT_ITERATION_PLAN.md)、[AI 交接](docs/AI_HANDOFF.md)、[项目开发 skill](.agents/skills/finns-development/SKILL.md)。代码版本以 VERSION 为准；运行实例与数据库状态以最新交接为准。

当前本地开发版本：`0.5.1`（未远程发布）。FINNS 使用 Java 17 + Spring Boot 3 和 Vue 3，支持文章与话题、经验专题、讨论及会话登录。专题可通过粘贴、TXT/Markdown 文件、场景模板或本人专题复制创建，先保存私人工作稿，再主动发布。顶部“开始创作”提供专题入口；新建时可直接写第一段正文，小标题可选。

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

开发环境默认将数据保存在 `data/` 下的 H2 文件数据库。生产部署前应切换 PostgreSQL、Flyway、Redis 和对象存储，具体演进路线见 [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)。
