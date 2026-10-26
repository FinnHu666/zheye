# FINNS 0.7.0｜FINNS-19 实施与验证

日期：2026-10-24（Asia/Singapore）。授权 PUBLISH-D21。工作目录 `E:/code/FINNS/AI-tome`，分支 `release/0.7.0`，HEAD `901047127ec3e9a92dad1cb5096a0acab1c4bcdf`，保留已有未提交修改；本会话串行整合。5199直接加载frontend，8099是模拟API，不能当真实业务验收。

## 实际变更

- 统一三步向导的创建和编辑入口，旧edit URL重定向；保留quick模板/导入/复制入口。补齐章节管理、章节迁移/排序、卡片/时间轴、保存失败保护、取消送审、撤回、草稿删除、真实创作/审核流转；修正390px顶栏网格区域重叠及站点中等宽度导航挤压。
- `CollectionMetadata` 保存标签、稳定分类key、SEO、结构化人群、不可变封面引用、章节展示样式。工作稿与发布/提交快照独立；旧客户端不带metadata时保留。完整复制携带创作元数据并复制封面图片；副本仍为默认PUBLIC范围的私人草稿，不继承定时/审核/发布结果。追加导入保留现有元数据。
- 封面真实上传、类型/字节/头部尺寸校验、16:9裁切与JPEG重编码、EXIF去除、作者/发布版/审核快照读取校验、nosniff/ETag/no-store；版主带原因单独移除并审计。不能凭审批权处置未提交的他人私人封面。未引用封面清理与保存使用封面行锁。
- 定时发布提交时检测并冻结，到点仅发布已通过的版本；人工审核较晚则审核后公开；重启补跑、条件/行锁、作者/权限/引用/下架最终校验，取消/撤回清除计划。`SCHEDULED`仅为API展示状态，不新增数据库状态枚举。默认关闭定时与章节样式开关，验证实例显式开启。
- 立即发布也使用送检的确切冻结快照；引用最终检查持有文章行锁，公开无效引用清空自定义标题、备注、ID及旧卡片字段。撤回保留自选关系和首次发布时间；旧回执不能重新公开撤回内容。
- 共享 `CollectionReader` 用于公开页、私人预览和向导预览。专题广场按发布侧分类过滤/分页/计数并显示封面；我的专题增加名称与状态筛选。SEO由专题HTML入口转义后注入，仅取有权读取的发布版。
- 附加迁移 V19（metadata/封面/定时/分类索引）与V20（活动时间线、人群精确匹配回填），旧audience原文保留。没有新条目枚举、外部服务或工具安装。

画布坐标与HTML模块维持原型明示的本浏览器实验边界；不包含在公开发布。轮播/评分榜/视频仍为占位。未把这些功能称为已实现发布能力。

## 验证记录

证据目录 `target/finns-19-implementation/` 已忽略，可重建/清理；核心结论在本文和验收表保留，原始截图/日志不保证永久保存。

| 检查 | 命令/证据 | 结果 |
| --- | --- | --- |
| H2全量 | `scripts/test.ps1`；package-accepted-last.log | 202/202，退出0 |
| PostgreSQL全量 | `scripts/test-postgres.ps1 -DatabaseName finns_test_collection_accept_20261024 -Port 55439`；postgres-completion.log | 202/202，退出0；部分已有测试按注解仍使用各自H2，主体用真实PostgreSQL17.11/Flyway/validate |
| 专题新增服务端测试 | CollectionLifecycleTests（12个测试方法） | 冻结/定时/审核、引用清理、撤回和自选、删除、封面、复制、取消、并发撤回/审批；H2及PostgreSQL通过；最终安全复查另见收尾记录 |
| 前端 | `node --test tests/*.test.js`；frontend-accepted-last.log | 86/86，退出0；7个向导组件行为用例覆盖无编辑不写、metadata保存、失败阻止发布、章节/模块归属、导入前保存、换号与会话失败保护 |
| 打包 | `scripts/package.ps1`；package-accepted-last.log | Vue构建与Maven package通过，202/202；正式静态资源与target/finns-0.7.0.jar已生成；最终安全修正后重打包记录见收尾 |
| 真实浏览器 | 5200代理8094，均loopback，隔离PostgreSQL | 创建→填写四项/标签/分类→保存→模块正文/章节样式→保存→预览→SEO保存→刷新→发布→公开页；公开页标题与内容匹配共享预览 |
| 视口 | Chrome真实viewport390/768/1440档（桌面innerWidth1441）；chrome-wizard-390/768/1440.png；此前另有同源iframe记录 | 内容编排均无横向溢出，390预览无溢出；手机默认顺序排列，窄屏顶栏已修正。不是实体手机测试 |
| 封面真实HTTP | cover_smoke.py / cover-smoke.json | 上传、EXIF去除、伪图/超大头尺寸拒绝、私人404→发布200→撤回404、nosniff通过 |
| 定时重启 | restart_smoke.py / restart-smoke.json | 定时快照落库后停止本轮8094进程，将测试到期时间设为过去，再启动；调度器补发，冻结正文保持，真实HTTP200 |
| 人群迁移 | MigrationFixture.java / audience-migration.json | 真实V18（含Java V7）→V19→V20；4条固定样例，2条全匹配/1条部分匹配/1条不匹配，原文全部保留；匹配率75%仅指该样本，非生产数据统计 |
| 旧包兼容 | 0.6.0包启动在finns_restore_check_collection_20261024；rollback-smoke.json | Flyway校验20迁移、启动成功；旧包建稿201/保存200/读取200。仅专题基础读写，不代表全部旧版本行为可安全回退 |

首轮PostgreSQL发现副本可见范围回归并修正（恢复默认PUBLIC私人稿）；在已留存测试数据的同一库重复运行时，3项旧全局数量/排序断言失败，保留原库并使用新命名隔离库复跑202通过。一次迁移夹具仅读取SQL文件，遗漏Java V7而失败，改用classpath完整迁移并在新库从V18重新演练通过。早期npm额外参数和测试202预期问题已更正；失败日志保留，未据失败结果标验收。

## 环境与交付边界

独立PostgreSQL仅监听127.0.0.1:55439，数据在target下；测试库均为finns_test_*或finns_restore_check_*。真实验收服务8094（classpath应用）与5200（Vite）只连浏览器测试库；5199/8099是历史原型端口，本轮不依赖其运行。旧包8095仅用于降级冒烟，当前未运行。无主库、真实数据迁移、外发邮件、Git提交推送或主站部署。

未执行/发布前置：实体手机/真实触摸、跨浏览器与屏幕阅读器、生产备份与恢复演练、生产规模负载；定时调度按单实例设计。开关默认关闭，发布时须指定环境并按交付流程开启。G8保持pending，功能验收/Plane完成不代表发布。

## 最终收尾核对（2026-10-24）

- 最终H2全量202项与Vue/Maven打包成功：`package-accepted-last.log`，11:35:38+08:00，退出0。真实PostgreSQL全量202项成功：`postgres-completion.log`，11:26:49+08:00，退出0。最后SEO单一description修正后，PostgreSQL专题/开关13项成功：`postgres-audit-last.log`，11:34:35+08:00，退出0。前端86项成功：`frontend-accepted-last.log`，退出0；无跳过/失败。
- 最终补齐：追加导入先保存、换号/失效会话保护；结构化人群统一读写、旧文本回退；新选文章预览标题/摘要；单一SEO description。上述变更均先于最终打包与对应回归。源码、测试和产物绑定见[SHA-256清单](COLLECTION_VALIDATION_SHA256.json)，不以HEAD代替未提交工作树。
- 8094已在最终编译后重启，真实HTTP核对专题190标题、恰好一个description、与最终index一致的资源URL且资源200，记录`runtime-final.json`。5200为开发预览，8094包含实际打包静态资源。
- Chrome直接视口390/768/1440档均检查无横向溢出；390默认顺序布局，追加导入进入带append参数页面，历史返回正确。截图位于证据目录。实体手机/触摸仍未验。
- 封面单独处置使用既有TAKE_DOWN审计类型，并写明只移除封面、正文保持；早期新增类型触发数据库约束，修正后专题测试通过，不把失败结果写为通过。
- 全仓`git diff --check`仍指出既有`docs/prototypes/site-access/src/pages/ContentEditorPage.vue:183`尾随空白；开始时baseline.patch已包含该问题。本轮文件单独检查，不改无关原型。

逐项结论见[验收结果](ACCEPTANCE_RESULT_COLLECTION.md)。功能实施、测试及本轮验收完成；G8发布继续pending，未授权部署。共享需规/验收定义仅更新当前状态，原批准范围与历史保留，因此刷新其证据哈希。
