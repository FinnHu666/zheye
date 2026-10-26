# FINNS 0.7.0｜文章外链图片与渲染统一（CT-L06 设计备选）

日期：2026-10-22。输入：[文章需规修订5 CT-R13](../../requirements/FOUNDATION_CONTENT_REQUIREMENTS.md)、[PUBLISH-D12/D13](../../requirements/PUBLISHING_DECISIONS.md)、验收 CT-A17。**状态：用户已选 A1 + B1 + D1 + E1（PUBLISH-D14，2026-10-22）；原型已按此实现，正式代码未实施。**

## 0. 已核实的事实（本轮）

| 事实 | 依据 |
| --- | --- |
| 正式阅读页/预览用自研 `frontend/src/lib/safeMarkdown.js` + `SafeMarkdown.vue`（令牌树渲染），图片目前显示“图片未加载”占位 | 源码 `SafeMarkdown.vue:16`、`safeMarkdown.js:15/50` |
| 原型编辑器为 Vditor 4.0.1（MIT），解析引擎 Lute；预览接口支持 `markdown.sanitize`、`lazyLoadImage`（占位图地址）、`transform(html)` 钩子、`renderers` | `node_modules/vditor/dist/types/index.d.ts` 第532/604/614/619行 |
| 体积（本机测得）：`lute.min.js` 3655KB（gzip 578KB）；`index.min.js` 289KB（gzip 69KB）；`index.css` 46KB（gzip 8KB） | Python gzip 级别9 计算 |
| 原型实测：Lute sanitize 清除了 `<script>`、`onerror`、`javascript:` 链接；但原始 `<img src=x>` 标签仍被保留渲染 | 2026-10-22 浏览器检查 |
| 服务端目前只在 `/auth/**` 设置 `Referrer-Policy: no-referrer`，无全站 CSP | `SpaController.java:18` |

## 1. 防盗链 / 引用来源（Referer）

外链图片由读者浏览器直接请求外站；无论哪种方案，**外站都能看到读者的 IP 与浏览器信息**（只有方案 A3 能避免）。

| 方案 | 做法 | 好处 | 代价/边界 |
| --- | --- | --- | --- |
| **A1（推荐）** | 每张图加 `referrerpolicy="no-referrer"` | 外站拿不到读者正在看的页面地址；零成本 | 少数开启“防盗链白名单”的图床会拒绝无 Referer 请求 → 显示失败占位 |
| A2 | 浏览器默认（只发站点域名） | 兼容性最好 | 外站知道读者来自本站；带白名单的图床照样拒绝 |
| A3 | 服务端图片代理/转存 | 隐藏读者 IP，可缓存，外站改图也不影响 | 带宽与存储成本、SSRF 风险、超出“不转存外部图片”的需规，需改需规，不建议 0.7.0 |

## 2. 懒加载与失败占位

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| **B1（推荐）** | 原生 `loading="lazy"` + `decoding="async"`；`max-width:100%`、`max-height:80vh` 等比缩放；加载失败替换为占位框：“图片无法加载”+ 说明文字 + 图片所在域名（不自动重试） | 无依赖；占位里不再发起请求 |
| B2 | Vditor `lazyLoadImage`（先显示一张占位图，滚到才加载）+ 同样的失败占位 | 只在 Vditor 渲染时可用；需准备一张本地占位图 |
| B3 | 不做懒加载；失败只隐藏图片 | 长文多图时首屏慢；读者不知道少了图 |

## 3. 敏感检测是否覆盖图片

图片内容本身**无法检测**（不做图像识别，需规排除外部服务）；外站在审核后**可以换掉同一地址的图片**，这是外链方案固有边界，补救靠人工下架。

| 方案 | 检测范围 | 说明 |
| --- | --- | --- |
| **D1（推荐）** | 图片说明（alt）+ 图片地址文本 | 读者看得见说明，地址里的文字也可能带违规词；复用现有三级管线，零新增成本 |
| D2 | 只检测说明文字 | 地址不检测 |
| D3 | 都不检测 | 说明文字可绕过敏感检测，不建议 |

可选加强（不在推荐内，需另定）：站长维护“允许的图片域名白名单”，不在名单的图片不显示——能降低换图和追踪风险，但作者可用的图床受限。

## 4. 编辑器 Vditor 与正式阅读页渲染统一

| 方案 | 做法 | 一致性 | 代价 |
| --- | --- | --- | --- |
| **E1（推荐）** | 阅读页、私人预览也改用 Vditor 预览（Lute）渲染；仅在文章相关页面按需加载；开启 sanitize，并在 `transform` 钩子里统一处理图片（只留 http/https 的 Markdown 图片、加 no-referrer/lazy、去掉原始 `<img>` 标签、失败占位） | 与编辑器同一解析器，所见即所得 | 首次打开文章页多下载约 650KB（gzip，可缓存）；资源需随站点自托管；需替换现有 SafeMarkdown 及其测试 |
| E2 | 阅读页保留自研 SafeMarkdown，只扩展图片支持；编辑器工具栏限制在 SafeMarkdown 支持的语法内 | 有不一致风险（表格、任务列表等需逐项对齐，需对照测试） | 体积小，改动集中 |
| E3 | 阅读页改用 markdown-it + DOMPurify，编辑器仍用 Vditor | 两个解析器，仍可能不一致 | 新增两个依赖 |

无论选哪种，过滤都发生在浏览器渲染端；可另加全站 CSP（`script-src 'self'`）作纵深防护，作为后续加固项，不在本次选择内。

## 5. 推荐组合与后续

推荐 **A1 + B1 + D1 + E1**。选定后：回写需规修订5（CT-R13 细化）与 CT-A17，更新原型（Vditor 预览加 transform 规则），再进入契约与实施（需另行授权）。未执行：真实图床兼容性抽样、gzip 后实际页面加载耗时、手机端。

## 6. 用户选择与原型落地（2026-10-22）

用户选择 A1（不发送来源）、B1（原生懒加载 + 失败占位框）、D1（检测图片说明与地址）、E1（阅读页/预览也用 Vditor 渲染）。原型实现：`components/VditorView.vue`（渲染前把原始 `<img` 转义为文字；`transform` 中只保留 http/https 图片并加 `referrerpolicy=no-referrer`、`loading=lazy`、`decoding=async`；加载失败替换为“图片无法加载：说明（域名）”；非 http/https 显示“图片地址不受支持”）；`components/VditorEditor.vue` 挂载时加文档级 `referrer=no-referrer`（正式实现改由服务端对文章页发 `Referrer-Policy` 头）。D1 在原型中天然成立：检测对象是包含图片语法的正文源码；正式后端需在契约中确认检测输入为 Markdown 源码（含 alt 与地址）。

原型验证（内置浏览器）：有效 https 图片加载成功（48px 测试图）、不存在的域名显示占位、`data:`/`javascript:` 图片显示“不受支持”、原始 `<img onerror>` 按文字显示且未执行；图片带 no-referrer 与 lazy 属性。**未验**：懒加载的实际延迟效果（浏览器面板处于隐藏状态，验证时改为立即加载）、真实图床白名单兼容性、手机端、加载耗时。原型测试 86/86。
