# FINNS-REQ-014 正式开发验证记录

日期：2026-10-09。依据：用户明确授权的开发测试方案、需规 0.5、产品原型 0.2。

**状态：已实施；自动化、隔离浏览器及真实 DeepSeek 固定案例验证通过。真实触屏、跨浏览器和真人易用性仍未验收。** 本轮没有更新 8080 主站、主库或远程服务。

## 1. 实际交付

- 新增 AI 创建、工作台、私人预览三个正式 Vue 路由，接入现有会话、CSRF 和“我的专题”；旧直接编写、导入及编辑器保留。
- 沟通建议保留作者答案；完整问卷确认后使用快照生成。生成候选无数据库副作用，重新生成须接受才替换；失败、取消及迟到结果不覆盖原稿。无资料只创建明确待补充结构，不调用模型编造事实。
- 四种风格，文字/步骤/问答模块，增删、撤销、排序；自由画布支持实际鼠标拖动/缩放、键盘及数值输入，桌面位置独立于阅读顺序。760px 及以下按阅读顺序堆叠。
- HTML + CSS 检查导入、宽窄预览和源码更新；稳定 ID、阅读顺序和布局保留。共同规则 JSON 与样例用于前后端；服务端 jsoup 1.23.2 独立复验，展示使用空 sandbox/CSP iframe。
- 私人草稿服务端保存、幂等创建、revision 更新及 409 冲突保护；保存确认只针对提交快照，期间新输入保留。未应用问卷、沟通输入、代码弹窗和退出登录同样考虑离开保护。
- V9 新增编辑器类型和页面文档；旧记录默认 OUTLINE，旧修改/发布接口拒绝 BUILDER。未实施新作品公开发布、模板共享、旧专题转换或原型浏览器草稿迁移。

接口、字段限制、事务和状态设计见 [概要设计与接口契约](TOPIC_BUILDER_IMPLEMENTATION.md)。需规文件未修改，其 SHA256 仍为 `508A49258DA9D9AC9082E47C1C3DCFD35874C4CFF41E42CBACB1353A7B261842`。

## 2. 最终自动化结果

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Node 行为、HTML 与实际 Vue/Router 组件测试 | 25/25，通过 | `target/topic-builder-node-tests.log`；`frontend/tests/topic-builder*.test.js`、`topic-html.test.js` |
| Java/H2 完整回归 | 26/26，通过 | `target/topic-builder-java-tests.log`，18:25:01 BUILD SUCCESS |
| 隔离 PostgreSQL 17.11 完整回归 | 26/26，通过 | `target/topic-builder-postgres-tests.log`，18:28:22 BUILD SUCCESS |
| Vue/Vite 生产构建 | 通过，56 modules | 静态资源 `index-Bcnrie6w.js`、`index-C_kDEADG.css` |
| Maven 最终打包 | 通过 | `target/topic-builder-package.log`，18:48:56；`target/finns-0.5.1.jar` |
| npm audit | 0 vulnerabilities | 本轮执行；增加 jsdom 26.1.0，更新原有 source-map-js 到修复版本 |
| 需规、版本、差异检查 | 通过 | 需规 hash 不变；VERSION/pom/npm/lock 为 0.5.1；`git diff --check` 退出 0，仅换行提示 |

复现入口（PowerShell，项目根目录；使用已有 D:/E: 工具）：

```powershell
$env:JAVA_HOME = 'D:/java/jdk/jdk-17.0.12'
$env:Path = "E:/download/nodejs;E:/program/codebasic/apache-maven-3.9.6/bin;$env:JAVA_HOME/bin;$env:Path"
Push-Location frontend
npm test
npm run build
Pop-Location
./scripts/test.ps1
./scripts/test-postgres.ps1 -DatabaseName finns_test_topic_builder_20261009 -Port 55439
mvn -s .mvn/settings.xml -DskipTests package
```

最终打包跳过重复测试，是在上述最终 Java/H2 和 PostgreSQL 回归已通过后进行；打包不代表真实 AI 验收。脚本遇到原生命令需核对退出码。隔离 PostgreSQL 必须预先存在且 owner 为 finns_app，测试保留数据，不自动清库。

新增 Java 的 5 个分组测试覆盖多项行为：新增/更新/幂等重试及同键跨账号、版本冲突、未登录/跨账号/CSRF、私人边界/旧接口、模型请求契约、每用户并发、鉴权/限流/空结果/无效 JSON/缺失字段/截断/超限/请求及响应体超时、HTML 13 个共同样例/版本/深度/节点/代码与模块容量、4 MiB 请求限制。常规测试只访问动态端口的本地模拟模型，不调用收费模型。

前端测试直接挂载实际 Vue 页面及 Vue Router，验证问卷保留、候选接受/拒绝、路由复用、首次保存 URL 切换、保存中继续编辑、离开取消/保存继续、未应用问卷保护、预览/编辑/前进后退；状态测试另验取消/迟到请求与幂等失败重试。它们不能代替真实触屏验收。

## 3. 数据库与浏览器证据

独立集群 `target/topic-builder-pg`，监听 `127.0.0.1:55439`。自动化库 `finns_test_topic_builder_20261009`、浏览器库 `finns_test_topic_builder_browser_20261009` 均成功应用 V1–V9。首次迁移及随后多次启动通过；最终服务器日志确认 schema version 9、No migration necessary、Hibernate validate 成功。

浏览器验收使用正式 Java/Vue 应用 8082，曾显式配置 `scripts/topic-builder-fixture-server.mjs` 的固定模型接口 55440。生成结果始终显示“隔离环境固定模型数据，仅用于验证交互和保存，未调用 DeepSeek”。这三个案例只证明交互、数据与权限闭环，不证明真实生成质量。

| 案例 | 实际验证 |
| --- | --- |
| 大学生实习短租指南（ID 1） | 沟通→问卷/资料/摘要→生成→普通块/HTML→画布→保存；刷新恢复、候选拒绝保留原稿；私人阅读预览隐藏编辑按钮 |
| 新手动画观看路线（ID 2） | 沟通、看番用途、时间线风格、资料确认、生成；新增/删除/撤销模块、保存到我的专题 |
| 工具入门实践指南（ID 3） | 沟通、工具学习用途、文档风格、生成/编辑/保存/刷新；双页面并发保存，旧页 409 且输入保留 |

SQL 核对三份作品均 BUILDER / DRAFT，模块数量 4/4/3，与列表一致。租房草稿 revision 2；模块移动/缩放后布局保存为 `x=0,y=100,w=49.2,h=290`，另一模块位于 `x=52,y=20`，阅读顺序仍由数组控制。HTML 模块 `3f819e77-e642-402d-9041-5d09ab801e80` 编辑源码后身份和 `x=3,y=558,w=90,h=500` 保留，原始源码从数据库读取恢复。

桌面实际鼠标手柄拖动/缩放、键盘微调、左侧输入、阅读顺序调整、HTML 修改后应用禁用/重新检查/更新均通过。390px 手机按阅读顺序堆叠且无页面横向溢出；768px 平板仍保留自由画布且无页面横向溢出。手机和桌面构图切换不修改保存的布局。

最终打包重新启动后，已重新登录并打开三份草稿列表/租房工作台；再次验证未配置 DeepSeek 的明确错误和输入保留，以及退出时取消保护后继续保持登录。最终浏览器 error/warn 控制台为空（模型错误以页面消息显示）。

截图为本地忽略产物，未加入 Git：

![最终正式工作台](../target/topic-builder-proof/desktop-final.png)

[390px 手机截图](../target/topic-builder-proof/mobile-390.png) · [768px 平板截图](../target/topic-builder-proof/tablet-768.png)。手机/平板截图来自同轮正式应用固定模型阶段；最终构建额外调整离开保护和错误展示，不改变布局规则。

## 4. 真实 AI：6 次调用通过，内容与恢复已核对

18:45 后用户明确提供密钥并要求配置。已使用隐藏输入配置当前 Windows 用户级 DEEPSEEK_API_KEY，仅重启隔离 8082 服务。密钥未写仓库、浏览器、运行参数或日志。后续本机启动脚本会从用户级环境读取；密钥已出现在聊天中，建议用户验收后轮换。

此前缺配置的 not-run / 0 次结果是历史状态；本轮真实调用共 6 次，无自动重试，模型全部返回 deepseek-flash。只发送脚本中的固定非敏感资料；报告保存在忽略目录 target/topic-builder-real-ai.json。

| 案例 | 沟通耗时 / total_tokens | 生成耗时 / total_tokens | 草稿 |
| --- | --- | --- | --- |
| 大学生短租准备 | 1096ms / 272 | 1571ms / 633 | ID 4，4 模块 |
| 虚构作品观看路线 | 894ms / 286 | 1785ms / 573 | ID 5，5 模块 |
| 文字工具第一次实践 | 1084ms / 273 | 1975ms / 657 | ID 6，3 模块 |

共 2694 total_tokens，逐项 prompt/completion/total 用量和实际返回文档在报告中。三个结果均已完成创建、同键重试不重复、修改第一块标题、版本更新与重新 GET 精确恢复；均为本人私人 DRAFT。原固定模型 ID 1–3 保留，没有冒充真实生成结果。

人工核对结果：租房忠实整理预算/租期/通勤/看房/交接，具体金额和细节待补充；看番保留甲→乙、丙的顺序待补充，未添加真实年份/人物/其他作品；工具保留原始五步，名称/版本/按钮待补充，额外核对问题标为 AI 建议。沟通建议仍须作者核对，观看路线例中模型没有自动填主题，问卷可手动补齐，不能把抽取当作确定答案。

最终浏览器重新登录后分别打开真实租房、看番和工具结果，确认顺序/待补充/建议标识及步骤编号正常；原始模型正文保留在数据库中，error/warn 控制台为空。截图：[真实租房](../target/topic-builder-proof/real-ai-rent.png)、[真实看番](../target/topic-builder-proof/real-ai-anime.png)、[真实工具](../target/topic-builder-proof/real-ai-tools.png)。

真实输出暴露步骤重复编号：模型正文已包含“1. ”，平台 ol 又编号。已修复为展示时去掉明确数字列表前缀，保留原始正文，年份不被误删；实际 Vue 测试验证显示及保存原文，25/25 前端测试和构建再次通过，重新打包。Java/PG 业务未改，沿用本轮上一阶段 26/26 证据，未无理由重跑。

验收脚本现支持 FINNS_AI_TEST_EMAIL / FINNS_AI_TEST_PASSWORD 登录指定隔离账号，否则注册临时账号；新增创建、幂等、修改和恢复检查。node --check 通过，脚本已用真实结果跑通，无需再次调用模型。首轮已使用 6/9 次，剩余 3 次；后续重跑前先检查报告与累计数量，不能直接再运行完整六次脚本。

真实触屏、其他浏览器、实际关闭浏览器的原生提醒、文件选择器全路径，以及真人创作者易用性尚未验收；现有合成测试和 Pointer Events 实现不表示这些项目通过。

## 5. 交付运行边界

- 最终应用：`http://127.0.0.1:8082/me/collections`，Java PID 27380，exec 会话 53360；cookie FINNS_BUILDER_TEST；日志 `target/topic-builder-final-server.log`。默认官方 DeepSeek 地址及 deepseek-flash，已读取用户级密钥，无模拟回退。
- 隔离 PostgreSQL：PID 63248，loopback 55439；本地测试 trust 认证，不作为生产配置。数据保留供复核。
- 原有独立原型：4173 / PID 64408，保留未改；它仍是浏览器演示，不是此次服务端实现。
- 8080 主站及主库未启停、未连接迁移；无远程发布、Plane 操作、Git 初始化/提交/推送。既有工作区修改保留。
- 各 PID 是本次记录，后续停止/启动前须重新核实对应命令行与端口，不能直接按过期 PID 操作。
