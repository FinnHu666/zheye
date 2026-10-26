# FINNS 0.7.0｜文章 Markdown 编辑器与外链图片：正式实施契约（草案）

日期：2026-10-23。输入：[文章需规修订5 CT-R13](../../requirements/FOUNDATION_CONTENT_REQUIREMENTS.md)、[设计与用户选择](DESIGN_CONTENT_IMAGES.md)（PUBLISH-D12/D13/D14：Vditor 4.0.1；A1 不发送来源、B1 原生懒加载+失败占位、D1 检测说明与地址、E1 阅读/预览/编辑统一用 Vditor）、验收 [CT-A04/CT-A17](ACCEPTANCE_CONTENT.md)。**状态：用户已确认 U1 全站、U2 随构建产物提交、U3 授权 C1–C5（PUBLISH-D15，2026-10-23）；实施中。**

## 1. 范围

纳入：文章编辑页正文、文章私人预览、发现页文章详情（读者阅读）的渲染与编辑；外链图片规则；Vditor 依赖与自托管资源；全站 `Referrer-Policy` 头；检测输入确认；测试。
排除：传统专题（FINNS-19 延期，`CollectionDetailPage` 笔记仍按纯文本显示）、评论、图片上传/代理/转存、全站 CSP、数据库结构（**无迁移**）、Plane。

## 2. 已核实的现状（2026-10-23 源码）

| 项 | 现状 | 位置 |
| --- | --- | --- |
| 渲染 | 自研令牌树，HTML 显示为文字，图片显示“图片未加载”占位 | `frontend/src/lib/safeMarkdown.js`、`components/SafeMarkdown.vue` |
| 使用处 | 私人预览、发现页文章详情 | `pages/ArticlePreviewPage.vue:36/41`、`pages/DiscoverPage.vue:168` |
| 编辑 | 正文为 `<textarea>` | `pages/ArticleEditorPage.vue:176` |
| 检测输入 | 发布时检测 `[title, summary, body]`，body 为 Markdown 源码（含图片说明与地址），检测前 NFKC、去标点符号空白 | `ReviewService.submitArticleDraft`、`ReviewText.normalize` |
| 来源策略 | 仅 `/auth/**` 发 `Referrer-Policy: no-referrer` | `config/SpaController.java:18` |
| 构建产物 | `vite build` 输出到被 Git 跟踪的 `src/main/resources/static`（当前 3 个文件） | `frontend/vite.config.js` |
| 旧测试 | `frontend/tests/articles.test.js` 第45、58行两项断言旧渲染器（图片不加载等） | 需迁移 |

## 3. 前端契约

### 3.1 依赖与自托管资源
- `frontend/package.json` 新增 `"vditor": "4.0.1"`（精确版本，MIT；其运行依赖仅 `diff-match-patch`），更新 `package-lock.json`。安装经本机代理 7898、`--registry https://registry.npmjs.org/`。
- 资源**只自托管白名单**（vite 插件：dev 以 `/vendor/vditor` 中间件提供，build 复制到 `static/vendor/vditor/dist/`）：`js/lute/lute.min.js`（3.6MB，gzip 578KB）、`js/i18n/zh_CN.js`、`js/icons/ant.js`、`css/content-theme/light.css`、`index.css` 由打包引入。**不复制** mermaid、mathjax、katex、echarts、graphviz、highlight.js 等。
- 渲染配置关闭代码高亮、数学公式与图表渲染；这些语法按普通代码块/文本显示（与现状“代码以文本展示”一致）。任何不在白名单内的资源请求视为缺陷（测试 T-B3）。所有 `cdn` 指向 `/vendor/vditor`，不访问外部 CDN。

### 3.2 渲染（替换 SafeMarkdown）
- 新增 `lib/markdownRender.js`（纯函数，可单测）：
  - `escapeRawHtml(md)`：在**围栏代码与行内代码之外**把 `<` 转义为 `&lt;`，使原始 HTML（含 `<img>`、`<b>`）一律按文字显示，与现规则一致。
  - `imagePolicy(html)`：只保留 `http/https` 图片，加 `referrerpolicy="no-referrer"`、`loading="lazy"`、`decoding="async"`、类名 `md-img`；其他协议（`data:`、`javascript:`、相对地址等）替换为“图片地址不受支持：说明”。
  - `imageFallback(container)`：捕获 `img` 的 `error`，替换为“图片无法加载：说明（域名）”，不重试。
- 新增 `components/MarkdownView.vue`：`Vditor.preview(el, escapeRawHtml(md), { cdn, lang:'zh_CN', mode:'light', markdown:{ sanitize:true }, transform:imagePolicy, … })`；挂载时注册 `imageFallback`。**加载失败兜底**：Vditor/Lute 资源加载失败时显示纯文本原文并提示“排版组件未加载，以下为原文”。
- 替换使用处：`ArticlePreviewPage`（两处）、`DiscoverPage` 详情。迁移完成后删除 `safeMarkdown.js`、`SafeMarkdown.vue` 及其旧断言，新增等价/更新断言（见 §6）。
- 样式：`.md-img{max-width:100%;max-height:80vh;height:auto;object-fit:contain}`；失败/不支持占位框统一样式。

### 3.3 编辑器
- 新增 `components/MarkdownEditor.vue`：阅读模式 = Vditor 即时渲染（ir），源码模式 = `<textarea>`；两者编辑同一字符串（`v-model`）。工具栏：标题、粗体、斜体、删除线、无序/有序/任务列表、引用、分隔线、代码块、行内代码、链接、表格、撤销/重做；**无图片上传**、无本地缓存（`cache.enable=false`）。
- `ArticleEditorPage` 正文改用该组件；模式在地址 `?mode=source`（切换不触发离开保护）；字数计数、缺项定位（`data-field="body"`）、保存/发布/离开保护等既有逻辑不变。Vditor 加载失败时自动退回源码模式并提示。
- 已知边界：编辑器内对原始 HTML 的显示方式可能与阅读页（一律文字）不同；以阅读页/预览为准，在 G6 记录实际表现。

## 4. 服务端契约
- 新增 `ReferrerPolicyFilter`（`OncePerRequestFilter`）：对所有响应设置 `Referrer-Policy: no-referrer`（U1 推荐全站）；`SpaController` 中 `/auth/**` 的同名设置保留无冲突。
- 检测输入**不变**：已包含 Markdown 源码中的图片说明与地址（D1 已满足）；以测试固定该行为，防止日后改为“渲染后纯文本”而漏检。
- 无数据库、无接口、无 DTO 变化；旧文章正文无需迁移（图片语法按新规则显示，即 CT-A17 “旧文章含图片语法按新规则显示”）。

## 5. 安全与能力边界
- 过滤发生在浏览器渲染端（Lute sanitize + `escapeRawHtml` + `imagePolicy`）；服务端存原文、不渲染。纵深防护 CSP 未选（后续加固项）。
- 外站可见读者 IP/浏览器信息；外站可在审核后替换同一地址的图片（补救为人工下架）；图片内容不检测。
- 少数带 Referer 白名单的图床会加载失败（显示占位）。
- Vditor 子依赖/内置资源的许可未逐个核实（主包 MIT）；Lute 为 GopherJS 产物，无法在 Node 单测中运行，渲染一致性靠浏览器测试覆盖。

## 6. 测试映射

| 编号 | 覆盖 | 类型 |
| --- | --- | --- |
| T-F1 | `escapeRawHtml`：原始 `<img>`/`<script>`/`<b>` 变文字；围栏与行内代码内容不变；嵌套反引号 | 前端 node 单测 |
| T-F2 | `imagePolicy`：https/http 保留并带 3 个属性；`data:`/`javascript:`/相对地址/空地址替换为“不受支持”；不引入其他标签 | 前端 node 单测（jsdom） |
| T-F3 | `imageFallback`：error 事件后替换为“图片无法加载：说明（域名）”，非 `md-img` 不处理 | 前端 node 单测（jsdom） |
| T-F4 | 迁移原第45/58行断言：危险链接（含嵌套括号 `javascript:`）、代码保持文本——改为浏览器断言 B2 | 浏览器 |
| T-S1 | 所有页面与 `/api/**` 响应带 `Referrer-Policy: no-referrer` | 后端 MockMvc（H2） |
| T-S2 | 正文 `![演示拦截词](https://x.example/a.png)` 发布→送审；地址含规则词同样命中；仅图片无文字正文可发布检测通过 | 后端集成（H2） |
| T-B1 | 构建产物含白名单资源、不含 mermaid/mathjax 等目录 | 构建检查脚本 |
| T-B2 | 浏览器：编辑阅读/源码往返；预览与发现页渲染一致；图片成功/失败/不支持；`<script>`、`onerror`、`javascript:` 不执行；网络无外部 CDN 请求；390/768/桌面；直达刷新 | 浏览器（G6） |
| T-B3 | Vditor 资源不可用时：阅读页显示原文兜底、编辑器退回源码 | 浏览器 |
| 回归 | 后端 H2 全量、前端全量、`vite build` | 自动化 |

验收映射：CT-A17 ← T-F1–F3、T-S1、T-S2、T-B2；CT-A04（修订后）← T-F1、T-F4、T-B2；CT-A01–A05 其余保存/发布行为 ← 既有测试回归。PostgreSQL 不涉及（无结构变化），不跑 PG 演练。

## 7. 批次与回退

| 批次 | 内容 | 完成判据 |
| --- | --- | --- |
| C1 | 依赖、vite 插件（dev 中间件 + build 白名单复制）、T-B1 | 构建产物白名单正确 |
| C2 | `markdownRender.js`、`MarkdownView.vue`、替换两处、删除旧渲染器、T-F1–F4 | 前端测试通过 |
| C3 | `MarkdownEditor.vue`、编辑页接入 | 前端测试通过 |
| C4 | `ReferrerPolicyFilter`、T-S1、T-S2 | 后端相关测试通过 |
| C5 | 全量回归、浏览器 T-B2/T-B3、实施记录与交接 | 记录完成 |

回退：仅代码与静态资源变化，回退到上一构建即可，无数据影响。`vite build` 默认写入被跟踪的 `src/main/resources/static`；实施期间验证构建用 `--outDir` 临时目录，最终产物是否入库见 U2。

## 8. 契约自评（同作者，独立性有限）
- 引入依赖：体积（文章页首次 +约650KB gzip）、自托管、许可、加载失败兜底均已写入；需求要求的“一致”由同一解析器保证。
- 风险：Lute sanitize 允许部分原始 HTML → 以 `escapeRawHtml` 统一转文字；`escapeRawHtml` 的代码区识别是新逻辑，T-F1 重点覆盖。
- 未解决：编辑器内原始 HTML 显示差异（§3.3）；子依赖许可未逐个核实。

## 9. 需用户决定

| 编号 | 问题 | 推荐 |
| --- | --- | --- |
| U1 | `Referrer-Policy: no-referrer` 范围：全站，还是只文章相关页面 | 全站（实现最简单；副作用：读者从本站点外链时，目标站看不到来自 FINNS） |
| U2 | 构建产物入库：`static/vendor/vditor` 白名单资源（约 3.8MB，主要是 Lute）随构建产物提交到 Git，还是构建产物不入库、部署时构建 | 随构建产物提交（与现有做法一致，部署不依赖网络安装） |
| U3 | 实施授权：C1–C5（不含发布、Git 提交、Plane） | 授权 C1–C5 |
