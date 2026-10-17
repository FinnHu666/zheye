# FINNS-REQ-014 专题创作概要设计、详细设计与接口契约

日期：2026-10-09。状态：已实施，真实 DeepSeek 三例验证通过，真实触屏/跨浏览器/真人易用性待验证。依据：用户明确授权的开发测试方案、需规 0.5、原型 0.2；需规原文不修改。实际验证和未通过项见 [验证记录](TOPIC_BUILDER_VALIDATION.md)，设计本身不代表验收通过。

第二轮补充（2026-10-10）：下文保留首轮设计历史；个人凭据、V10、模块复用与可编辑提示以 [第二轮变更契约](TOPIC_BUILDER_ITERATION_2.md) 为准，原共享 Key 接入已被个人 Key 取代。

## 1. 概要设计

在现有 Java 17 / Spring Boot 3.3 / Vue 3 应用新增 AI 创建入口，复用账号、CSRF、专题身份及版本号。旧专题继续使用 OUTLINE 编辑器，新专题使用 BUILDER 编辑器，保存终点为私人草稿。模型调用不创建作品；作者接受候选结果后仍须明确保存。

新增创建 `/me/collections/ai/new`、编辑 `/me/collections/:id/builder`、私人预览 `/me/collections/:id/builder/preview`。现有 `/me/**` SPA 回退可覆盖这些路由。我的专题根据 editorType 选择入口。旧编辑、导入、复制及发布接口拒绝 BUILDER 文档，避免丢失布局或绕过首轮私人边界。不转换旧专题或浏览器原型草稿。

## 2. 文档、存储与事务

页面文档 v1：`{schemaVersion:1, form, confirmedForm, blocks, layoutMode, withoutSource}`。form 为当前页面元信息，confirmedForm 为生成时确认的快照；两者均包含 topic、audience、purpose、goal、style、density、styleNote、source、constraints、context。source 保留原文。blocks 数组即阅读顺序，每块保存 UUID id、kind(text/steps/question/html)、title、body，可选 layout；html 另存 code(source、rendererVersion:1、frameHeight)。坐标 `{x,y,w,h}` 使用百分比 x/w 与像素 y/h。

V9 为 community_collections 增加 editor_type，默认 OUTLINE；新增 collection_builder_documents，collection_id 主键/外键，schema_version、document(text)。实体对应新表，H2 测试可生成结构，PostgreSQL 使用 Flyway + validate。专题行继续保存作者、DRAFT、revision、名称、人群、目标和模块数。

首次保存事务锁定用户行，以 `userId:builder-create:idempotencyKey` 复用现有 operation 表，保存请求 SHA256；同键同内容返回原作品，不同内容返回409。更新事务以 owner_id + revision 条件增加版本，随后更新文档，失败整体回滚。客户端保存确认只针对发出请求的快照，不用响应覆盖新输入。

## 3. 接口契约

接口均需登录；写接口沿用 XSRF cookie/header。服务端不接受客户端作者身份。

| 接口 | 输入 | 输出 |
| --- | --- | --- |
| GET /api/me/topic-builder/capabilities | 无 | aiAvailable、model、styles、moduleKinds、limits、formLimits、layoutLimits、htmlPolicy（无密钥） |
| POST /api/me/topic-builder/chat | messages(role/text)、form | reply、form、usage、model、elapsedMs；已有非空答案由作者控制 |
| POST /api/me/topic-builder/generate | form（确认快照） | document、warnings、usage、model、elapsedMs；无持久化副作用 |
| POST /api/me/topic-builder/drafts | idempotencyKey(UUID)、document | 201：id、revision、updatedAt、document |
| GET /api/me/collections/{id}/builder-document | 路径 id | id、revision、updatedAt、document |
| PUT /api/me/collections/{id}/builder-document | revision、document | 更新后的 id、revision、updatedAt、document |

400 校验失败；401未登录；403归属/CSRF；404不存在；409版本/幂等/编辑器冲突；413超过4 MiB；429用户已有AI请求；502模型内容或上游失败；503未配置；504超时。模型错误不泄漏上游响应正文、Authorization 或输入资料。

## 4. AI 与校验

后端 HTTP 客户端直连 DeepSeek，base URL 默认 https://api.deepseek.com，model 默认 deepseek-flash；配置 FINNS_AI_BASE_URL、FINNS_AI_MODEL、DEEPSEEK_API_KEY。5秒连接、60秒请求超时，stream=false、thinking disabled、JSON output；chat 2048 / generate 8192输出token。每用户一项在途请求，无自动重试。日志仅请求标识、耗时、模型/用量和安全错误类型，不记录密钥或原文。接口未配置不伪装模拟成功。

沟通保留作者已有答案，抽取空字段供问卷确认。生成使用完整快照；资料作为数据而非系统指令，只组织已有资料，新增建议须标明来源类型，不捏造引用。无资料直接生成待补充结构，不向模型要求编造正文。模型仅输出普通块，服务端赋稳定UUID及完整文档并校验，不接受模型提供的作者、数据库ID或HTML。

长度沿用原型：主题80、人群160、用途80、目标300、偏好200、资料16000、要求500、背景200；模块标题100/正文4000，最多50块/其中20HTML。宽20–100%、x>=0且x+w<=100，y 0–10000px，h120–1600px；frameHeight120–1200px。HTML最多60000 UTF16字符、文件128KiB、嵌套40、节点2000；总请求4MiB。拒绝而不截断。

HTML规范以 frontend/src/lib/topic-builder/html-policy.json 为共同源，经Maven资源复制供后端读取；前端离线template解析、后端jsoup1.23.2独立解析。拒绝脚本、事件、未知标签属性和外部资源；原文保留。展示每次校验，空sandbox + no-referrer + CSP，只允许内部CSS及规定data图片，不把HTML注入宿主DOM。修改源码后重检才应用，应用保留UUID/布局。

## 5. 前端状态与验收

问卷和已确认生成输入分离；请求序号使取消/迟到结果失效。候选接受前保留原稿。预览与编辑共用渲染器；手机<=760px按数组顺序堆叠，桌面坐标不变。手柄Pointer Events支持鼠标/触控，键盘和输入框为替代操作；不以合成触控测试冒充真实手机测试。

保存明确触发，不自动保存；同一时间只发一项保存。首次保存的幂等键与请求快照绑定，网络失败重试同一快照；后续新输入在初次保存成功后再更新。保存冲突/失败保留输入；导航提供保存继续/放弃/继续编辑，关闭刷新使用浏览器提醒。

尚未应用的问卷答案和沟通输入同样参与离开保护；它们不能被“已保存页面”冒充已保存。代码弹窗内尚未应用的源码须先处理再离开。退出登录先通过路由保护，作者选择继续编辑时保留登录。首次保存改变 URL 时保留正在编辑的文档；创建、编辑、预览复用 Vue 组件时，根据已提交的路由变化加载对应页面，避免保留上一作品的界面。

验证：Node状态/保存行为、Java权限/事务/HTML/模拟模型、H2与隔离PostgreSQL/V9、正式前端构建、桌面/390px/768px浏览器、真实DeepSeek固定非敏感案例（最多9次）。记录实际模型/用量/耗时与未通过项。只使用隔离数据库及独立端口，不更新8080主站、主库或远程服务；版本保持0.5.1，改动记Unreleased。
