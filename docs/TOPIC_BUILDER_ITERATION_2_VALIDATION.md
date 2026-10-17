# FINNS-REQ-014 第二轮验证记录

日期：2026-10-10。版本 0.5.1 / Unreleased。依据：[第二轮变更与接口契约](TOPIC_BUILDER_ITERATION_2.md)。首轮需规、原型、测试记录保留。本记录只描述本轮证据。

## 截图问题处理

| 问题 | 实际处理 | 验证 |
| --- | --- | --- |
| 创建页常驻说明、案例卡、问题式标题、具体案例输入提示 | 移除说明和三卡；流程条在内容区居中；采用指定标题和通用输入提示 | 正式 Vue 页面、桌面及手机截图 |
| 顶部缺少个人中心，资料和导航偏左，保存按钮常驻 | 顶部固定“个人中心”；导航与表单使用同一居中容器；仅资料有变化才显示保存，改回隐藏 | 浏览器修改/还原；前端测试覆盖失败及保存期间编辑 |
| 黄色提示不能修改 | 逐条编辑、添加、删除；随草稿保存；阅读只显示编辑后列表；原始模型提示单独保留 | 浏览器保存/刷新、阅读；Java 持久化与长度校验；接受/拒绝候选行为测试 |
| 添加模块直接创建单个文字块 | 基础模块、本人专题、收藏和市场示例选择面板；原 HTML 导入保留 | 四类模块自动化复制；浏览器 HTML 复制、收藏、插入、取消收藏；市场卡无导入操作 |

另修复两项验收中发现的问题：JSON 成员顺序不同导致已保存文档误报未保存；入口 HTML 一小时缓存导致更新后仍引用旧资源。比较时忽略对象成员顺序、保留数组顺序；入口 HTML 返回 no-store，哈希资源保留既有缓存策略。

## 自动化及数据库

- 前端：`cd frontend; E:/download/nodejs/node.exe --test tests/*.test.js`，29 项通过。包含问卷/候选/迟到响应、保存快照及导航保护、资料脏状态和失败、Key 输入清空、四种模块独立复制及收藏、来源分页、核验提示和新稿接受/拒绝、JSON 顺序与阅读顺序、共享 HTML 规则。
- 构建：`scripts/package.ps1` 构建正式 Vue 并运行 Java 回归、打包；另有最终资源构建日志。前端 60 个模块构建成功，未增加运行时依赖。
- Java：29 项回归通过，包含新增账号凭据、加密持久化及重新实例化解密、换主密钥拒绝、收藏快照/分页/越权、核验提示保存与旧稿兼容，以及首轮模型错误、草稿幂等/冲突/HTML/旧编辑器边界。
- PostgreSQL：`scripts/test-postgres.ps1 -DatabaseName finns_test_topic_builder_20261009 -Port 55439`，29 项通过。V10 首次迁移成功，后续上下文重复启动校验无新增迁移。浏览器库 `finns_test_topic_builder_browser_20261009` V10 首次与重复启动成功；SQL 查询 V10 success=true。
- 浏览器库迁移前备份：`target/iteration2-browser-before-v10.dump`（56,249 字节）。只操作隔离实例 55439，未连接或更新主库。
- API 实例验证：两个账号的配置与收藏隔离，未配置 503、CSRF 403、收藏越权 404、工作稿越权 403、冲突 409；无资料结构不调用模型；无效 Key 失败明确、删除后 AI 不可用；重新登录及服务重启恢复配置、收藏与编辑提示。完整结果 `target/iteration2-acceptance.json`。

自动化日志：`target/iteration2-frontend.log`、`iteration2-java.log`、`iteration2-postgres.log`、`iteration2-package-final.log`、`iteration2-build.log`。早期测试曾因 jsdom 缺少 Document 对象失败，补全环境后重跑通过；首次验收脚本将退出成功的 204 错当成 200，已修正脚本并通过重新登录/重启复核，没有再次调用收费模型。

## 真实 DeepSeek

本轮严格 3 次请求，没有重试。当前测试账号手工配置用户此前提供的 Key；产品没有自动迁移共享 Key。保存凭据本身没有调用模型。

| 请求 | 实际模型/结果 | 耗时 | token |
| --- | --- | --- | --- |
| 沟通 | deepseek-flash，200 | 1,499ms | prompt 271 / completion 135 / total 406 |
| 有资料生成 | deepseek-flash，200，经过业务结构校验 | 2,662ms | prompt 306 / completion 569 / total 875 |
| 第二账号的无效 Key 生成 | 上游鉴权失败，应用 502，明确引导替换 Key | 95ms | 无返回用量 |

成功调用合计 1,281 total_tokens；测试资料固定且不含敏感信息。生成输出通过服务端结构和容量验证；本轮主要验收个人 Key 路径，后续模块复制使用固定四类内容，不将这些固定内容冒称模型原稿。首轮租房、看番和工具学习内容质量检查见原验证记录，本轮没有重复三个完整真实案例。

## 浏览器证据及边界

正式应用 8082，登录验收账号；桌面默认视口、390×844 手机和 768×1024 平板。检查创建页、个人资料、AI 配置、核验提示、模块选择/收藏/市场、私人阅读。截图保存在忽略目录 `target/iteration2-evidence/`，JPEG 格式。

工作稿 10 为固定四类来源；工作稿 11 为独立副本，仅修改本轮测试稿，用户已有“看番路线汇总”等草稿未覆盖。HTML 来源复制后生成新 ID；收藏快照标题保持，当前标题独立变化；再次插入收藏后可独立编辑，取消收藏不删除已插入模块。键盘移动/缩放复制模块，保存后位置 `4% / 808px / 90% / 510px`，稳定 ID `4888ccd3-d79a-43e3-8c95-4737d1eee98a`。手机按阅读顺序堆叠，桌面构图保留。

未验证：真实触屏手势、其他浏览器、真人易用性、长时间运行、生产主密钥轮换/备份恢复演练、真实市场。鼠标拖动的详细历史证据见首轮，本轮着重验证复制后的布局和键盘操作。主站 8080、主库、远程发布、Git 提交/推送和 Plane 写入均未执行。


## 截图索引

| 页面 | 桌面 | 390px | 768px |
| --- | --- | --- | --- |
| 创建 | [截图](../target/iteration2-evidence/create-desktop.jpg) | [截图](../target/iteration2-evidence/create-mobile.jpg) | [截图](../target/iteration2-evidence/create-tablet.jpg) |
| 个人资料 | [截图](../target/iteration2-evidence/profile-desktop.jpg) | [截图](../target/iteration2-evidence/profile-mobile.jpg) | [截图](../target/iteration2-evidence/profile-tablet.jpg) |
| AI 配置 | [截图](../target/iteration2-evidence/ai-settings-desktop.jpg) | [截图](../target/iteration2-evidence/ai-settings-mobile.jpg) | [截图](../target/iteration2-evidence/ai-settings-tablet.jpg) |
| 编辑提示 | [截图](../target/iteration2-evidence/notes-desktop.jpg) | [截图](../target/iteration2-evidence/notes-mobile.jpg) | [截图](../target/iteration2-evidence/notes-tablet.jpg) |
| 模块收藏 | [截图](../target/iteration2-evidence/favorites-desktop.jpg) | [截图](../target/iteration2-evidence/favorites-mobile.jpg) | [截图](../target/iteration2-evidence/favorites-tablet.jpg) |
| 市场示例 | [截图](../target/iteration2-evidence/market-desktop.jpg) | [截图](../target/iteration2-evidence/market-mobile.jpg) | [截图](../target/iteration2-evidence/market-tablet.jpg) |

本人专题模块选择另有 [桌面](../target/iteration2-evidence/module-source-desktop.jpg) 和 [平板](../target/iteration2-evidence/module-source-tablet.jpg)；私人阅读 [手机](../target/iteration2-evidence/preview-mobile.jpg)、[平板](../target/iteration2-evidence/preview-tablet.jpg)。最后核对入口返回 no-store，实际浏览器资源为 `index-DxYMRJ5y.js` / `index-YZTO1c16.css`，最终控制台 error/warn 为空。真实触屏和跨浏览器仍未验证。
