# FINNS 0.6.0 本机主站发布记录

日期：2026-10-10（Asia/Shanghai）。需求沿用 FINNS-REQ-014。

## 授权和范围

用户要求部署到生产环境，经明确选择确认：**本机 8080 主站，使用现有主库**。本记录中的主站指本机既有环境，不代表公网/远程生产服务。保留 loopback 绑定和既有数据库认证范围。

发布包含需规 0.5 对应的首轮专题私人草稿闭环与第二轮截图修正、个人 DeepSeek Key、提示编辑、模块复制和收藏。版本从 0.5.1 升为 0.6.0，VERSION、pom.xml、frontend/package.json、frontend/package-lock.json 同步。没有新增产品范围；新编辑器仍只保存私人草稿，市场仍是示例。

## 运行实例

| 项目 | 实际状态 |
| --- | --- |
| 主站 | http://127.0.0.1:8080/ ，Java 17 / Spring Boot 3.3.8 / postgres profile |
| 主应用 PID | 27896，2026-10-10 13:52 启动；后续停止前必须重新核对 PID 和 JAR 路径 |
| 主数据库 | jdbc:postgresql://127.0.0.1:5432/finns，角色 finns_app；原有集群 data/postgres |
| Flyway | V1–V10 success=true；本轮新增应用 V9、V10 |
| 会话 | FINNS_MAIN，与测试实例 FINNS_BUILDER_TEST 分开 |
| 保留的测试环境 | 8082 / PostgreSQL 55439，仍运行原 0.5.1 测试包，非当前发布主站 |
| 恢复演练 | finns_restore_check_release_060_20261010，5432；保留恢复和自动化测试数据；8083 演练应用已停止 |

发布目录：`data/deployments/20261010-topic-builder-0.6.0/`（Git 忽略）。JAR：`finns-0.6.0.jar`，SHA256：`5856A698D083688D68E912FAE1AA60E5D2F761DBB0E103ACA1E32BD54DCFC99D`。

入口资源仍为 `index-DxYMRJ5y.js` / `index-YZTO1c16.css`，与已验证第二轮源码一致；HTML 使用 no-store。主站日志 `main-server.log`、错误日志 `main-server-error.log`、PID 文件 `main.pid`。发布目录另有 VERSION、测试日志和部署清单。

## 备份与迁移证据

1. 主站和主库起初均未运行。仅启动既有 PostgreSQL 集群，没有初始化新主库；PostgreSQL 完成上次中断后的自动恢复，再进行备份。
2. 自定义格式备份 `finns-before-0.6.0.dump`，SHA256：`912AA32006BA7F3AB7CBA677277503680D0D1DC840FB2E251C27F94C56E9B159`；`pg_restore -l` 可读取，目录清单 `backup-contents.txt`。
3. 在独立命名的恢复库完整恢复，13 张业务表行数/摘要与主库一致。运行新版后成功 V8→V10，既有行摘要仍一致；随后在该库执行 29 项 PostgreSQL 回归。
4. 主站启动应用 V9/V10。`main-before.txt` 与 `main-after.txt` 相同，全部 13 张既有业务表保持原数据。审计去除新增的默认字段 editor_type 后比较 canonical JSON 摘要；该字段另验 5 个旧专题全部 OUTLINE。
5. 主库新增 collection_builder_documents、user_ai_credentials、user_module_favorites 均为空。没有导入测试账号、测试草稿、测试收藏或测试 Key；没有为了验收保存任何主库业务数据。

审计 SQL 为同目录 `audit.sql`，迁移查询 `main-migration.txt`，恢复前后摘要 `restore-before.txt` / `restore-after-migration.txt`。

加密主密钥仍来自服务端 Windows 用户环境 `FINNS_AI_CREDENTIAL_SECRET`。另备份到仓库外 `D:/env/finns-secrets/credential-master-20261010.dpapi.xml`，使用 Windows 用户 DPAPI，加上目录 ACL 限当前用户与 SYSTEM；已验证解密比对通过，没有输出或保存明文。该备份依赖同一 Windows 用户/机器，**不是跨机器恢复方案**；跨机器部署或主密钥轮换需要另行设计。主库账号初始没有个人 Key，作者需在个人 AI 配置页保存。

## 验证结果

| 检查 | 本轮结果与证据 |
| --- | --- |
| 前端行为测试 | 29/29，frontend-tests.log |
| Vue 构建、Java 测试与打包 | BUILD SUCCESS，Java 29/29，package.log |
| 恢复 PostgreSQL 回归 | 29/29，postgres-tests.log；仅向隔离恢复库写测试数据 |
| 迁移与重复校验 | 恢复副本首次 V9/V10，测试上下文重复校验通过；主库首次迁移通过 |
| 主站 HTTP | 7 个 SPA 地址 200，实际入口资源正确，HTML no-store；http-checks.json |
| 权限 | 新增三个个人 GET 未登录 401；无 CSRF 的 PUT 403；未写 Key |
| 原账号 | demo@aitome.dev 原密码登录、退出通过；未修改账号资料 |
| AI 与模块 API | 加密存储可用、个人 Key 未配置、收藏和 BUILDER 专题列表为空；auth-checks.json |
| 无资料生成 | structure-only 候选，3 个待补充模块，无收费请求且未保存 |
| 浏览器 | 主站登录→个人中心→AI 配置→AI 创建页，直达已加载新版；console warn/error 为空 |
| 页面证据 | 同目录 create-desktop.jpg、ai-settings.jpg |

主站统计 1 位创作者、4 份公开内容、4 个公开专题；个人 5 个专题中包括原有私人内容。没有为发布新增收费模型请求。真实 DeepSeek、移动端、HTML/自由画布等完整功能验收沿用第二轮记录，本轮未重复付费调用或完整手机流程。真实触屏、跨浏览器、长期运行和真人易用性仍未验证。

一个手工组合的恢复启动命令曾被自动审批拒绝（仅报告 blocked by policy）；随后改用已有的、限制数据库名与端口的隔离启动脚本，完成演练。没有绕过环境隔离或扩大监听范围。

## 启停与回退

启动已发布的固定包：

```powershell
./scripts/start-local-main.ps1 -ReleaseDirectory data/deployments/20261010-topic-builder-0.6.0
```

脚本只使用既有主库，拒绝占用中的 8080，读取服务端主密钥，隐藏启动 Java 并输出 PID；**返回 PID 不等于启动成功**，继续检查日志、端口、HTTP 与 Flyway。它不自动升级源码或重新打包，不创建数据库。后台进程不是 Windows 服务，没有配置开机自启。

停止前读取 main.pid，并核对该进程命令行指向上述部署 JAR、8080 监听归属一致，再停止该精确进程。不可直接使用历史 PID，也不可停止所有 Java/PostgreSQL 进程。

上一版固定包仍保留在 `data/deployments/20261003-ux-0.5.1/finns-0.5.1.jar`。若需回退：停止新版写入；先备份当前 V10 库并保留新版后数据；将本次 V8 备份恢复到**新命名的隔离库**、校验摘要，用上一版固定 JAR 指向该副本验证后再切换连接。不要直接用旧编辑器操作已迁移主库中的新文档，也不要自动覆盖/删除主库或 Flyway 历史。恢复副本已演练；完整线上回退未实际执行，回到发布前备份会遗漏发布后的写入，须单独处理。

本轮未提交/推送 Git、未写 Plane、未远程发布。工作区既有修改继续保留。相关历史见 [第二轮验证](TOPIC_BUILDER_ITERATION_2_VALIDATION.md)、[交接](AI_HANDOFF.md)、[数据库说明](DATABASE.md)。
