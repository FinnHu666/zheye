# FINNS-REQ-014 第二轮变更与接口契约

日期：2026-10-10。状态：已实施，自动化和隔离运行验证通过。依据：用户确认的第二轮计划；需规 0.5、原型 0.2 和首轮验收原文保留。本轮版本仍为 0.5.1，仅 8082 与隔离 PostgreSQL 验证。

## 变更及可观察结果

1. 创建页移除常驻说明和案例卡；流程条在内容区居中，标题“让你的经验，成为他人的指引。”，输入提示使用通用描述。
2. 账号入口显示“个人中心”；导航、资料居中；资料有变化才出现保存按钮，失败不丢输入。
3. 核验提示逐条编辑、添加、删除，随私人草稿保存。`reviewNotes` 为可选字符串数组（最多 20 条，每条 300 字）；字段缺省时使用 `generation.warnings`，空数组表示作者删除全部提示。原始 `generation.warnings` 保留，只有接受候选结果才重置当前提示。
4. 添加模块面板提供基础模块、本人专题、我的收藏、市场示例。复制单独模块时产生新 ID、按当前布局放置；HTML 继续执行既有校验。收藏是独立快照，保存后不跟随来源变化。
5. 个人 DeepSeek Key 按账号加密保存；未配置不回退共享 Key，无资料结构仍可创建。保存凭据不调用模型。

## 路由与 API

新增登录路由 `/me/ai-settings`；其他已有路由保持。

| API | 请求 | 返回/约束 |
| --- | --- | --- |
| GET /api/me/ai-settings/deepseek | 无 | configured、lastFour、storageAvailable；不返回完整密钥 |
| PUT /api/me/ai-settings/deepseek | `{key: string}` | 同上；1–512 个可见 ASCII 字符，保存不调用模型 |
| DELETE /api/me/ai-settings/deepseek | 无 | 清除本人凭据，重复删除安全 |
| GET /api/me/topic-builder/module-favorites?page=0&size=12 | 无 | items、page、size、hasNext；每项 id、block、createdAt |
| POST /api/me/topic-builder/module-favorites | `{block: object}` | 收藏独立快照，执行与草稿相同的模块校验 |
| DELETE /api/me/topic-builder/module-favorites/{id} | 无 | 只允许删除本人收藏；其他账号返回 404 |
| GET /api/me/collections?editorType=BUILDER&page=0&size=12 | 无 | 复用分页接口，只列本人已保存的画布专题 |

均沿用 Session、CSRF 与后端账号归属。未登录 401、新收藏越权 404（既有工作稿接口保持 403）、校验 400、服务端加密配置不可用 503；生成错误保留输入。

## 存储和密钥决定

追加 V10：个人凭据表、模块收藏表。AES-256-GCM 加密，随机 12 字节 nonce，账号与 provider 作为 AAD；主密钥为独立服务端环境变量 `FINNS_AI_CREDENTIAL_SECRET`（32 字节随机值的 Base64）。主密钥缺省或无效时拒绝存取密文，不存明文。部署必须备份此环境配置；替换主密钥需要另行制定重新加密方案。现有 `DEEPSEEK_API_KEY` 不作为产品回退，不自动写入任何账号。

保留原始 HTML 源码和已有隔离渲染，复用模块数、HTML 数、代码容量和完整文档 4 MiB 限制。不更改旧编辑器，不开放公开发布或真实市场。

## 验证计划与完成记录

已执行前端 29 项、Java 29 项、隔离 PostgreSQL 29 项和构建；V10 首次及重复启动、两个账号的凭据/收藏隔离、CSRF、重新登录及重启恢复通过；真实 AI 严格 3 次（两次成功，一次无效 Key 鉴权验证），没有重试。浏览器证据和未验证项见 [第二轮验证记录](TOPIC_BUILDER_ITERATION_2_VALIDATION.md)。
