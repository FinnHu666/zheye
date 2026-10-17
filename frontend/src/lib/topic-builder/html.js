import policyRules from './html-policy.json' with { type: 'json' }

export const HTML_BLOCK_VERSION = policyRules.rendererVersion
export const MAX_HTML_LENGTH = policyRules.maxLength
const allowedTags = new Set(policyRules.tags.split(' '))
const allowedAttributes = new Set(policyRules.attributes.split(' '))
const resourceCss = /@import\b|url\s*\(/i
const hasResource = (css) =>
  resourceCss.test(
    css
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\\([0-9a-f]{1,6})\s?|\\([^\r\n])/gi, (_, hex, char) =>
        hex
          ? String.fromCodePoint(Math.min(parseInt(hex, 16), 0x10ffff))
          : char,
      ),
  )
const policy =
  "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

// Parse only inside an inert template. User markup never enters the host document.
export function compileHtmlBlock(source) {
  if (typeof source !== 'string' || !source.trim())
    throw new Error('请先粘贴 HTML + CSS，或使用时间轴示例。')
  if (source.length > MAX_HTML_LENGTH)
    throw new Error('代码最多 60,000 字，请精简后再预览。')
  const template = document.createElement('template')
  template.innerHTML = source.replace(/\r\n?/g, '\n')
  let count = 0,
    visible = false
  function inspect(parent, depth = 0) {
    if (depth > policyRules.maxDepth)
      throw new Error('模块嵌套超过 40 层，请精简结构。')
    for (const node of [...parent.childNodes]) {
      if (++count > policyRules.maxNodes)
        throw new Error('模块节点过多，请拆成多个模块。')
      if (node.nodeType === 8) {
        node.remove()
        continue
      }
      if (node.nodeType === 3) {
        if (node.textContent.trim()) visible = true
        continue
      }
      if (node.nodeType !== 1) {
        node.remove()
        continue
      }
      const tag = node.localName.toLowerCase()
      if (tag === 'title') {
        node.remove()
        continue
      }
      if (
        tag === 'meta' &&
        (node.hasAttribute('charset') ||
          node.getAttribute('name')?.toLowerCase() === 'viewport') &&
        !node.hasAttribute('http-equiv')
      ) {
        node.remove()
        continue
      }
      if (tag !== 'style' && !allowedTags.has(tag))
        throw new Error(
          `首轮不支持 <${tag}>。请使用静态 HTML + CSS；脚本、Vue 标签和外部嵌入暂不支持。`,
        )
      if (tag === 'style') {
        if (node.attributes.length)
          throw new Error('请使用不带属性的 <style> 标签。')
        if (hasResource(node.textContent))
          throw new Error('CSS 外部资源与 url() 暂不支持，请使用本地样式。')
        continue
      }
      for (const attribute of [...node.attributes]) {
        const name = attribute.name.toLowerCase(),
          value = attribute.value
        if (name === 'href' && tag === 'a' && /^#[a-zA-Z0-9_-]+$/.test(value))
          continue
        if (
          name === 'src' &&
          tag === 'img' &&
          /^data:image\/(png|jpeg|gif|webp|avif);base64,[a-z0-9+/=\s]+$/i.test(
            value,
          )
        )
          continue
        if (!allowedAttributes.has(name) || name.startsWith('on'))
          throw new Error(
            `暂不支持属性 ${name}。事件、外部链接、脚本和外部资源不能导入。`,
          )
        if (name === 'style' && hasResource(value))
          throw new Error('行内样式中的 url() 暂不支持。')
      }
      if (tag === 'img' && !node.hasAttribute('src'))
        throw new Error(
          '图片需要内嵌的 PNG/JPEG/GIF/WebP/AVIF 数据，首轮不加载外部图片。',
        )
      if (tag === 'img' || tag === 'hr') visible = true
      inspect(node, depth + 1)
    }
  }
  inspect(template.content)
  if (!visible) throw new Error('代码中还没有可显示的内容，请添加正文或节点。')
  const html = template.innerHTML
  const srcdoc = `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="${policy}"><meta name="viewport" content="width=device-width, initial-scale=1"><style>*,*::before,*::after{box-sizing:border-box}html{color-scheme:light;background:#fff}body{margin:0;padding:16px;font:14px/1.7 system-ui,sans-serif;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body>${html}</body></html>`
  return { srcdoc, html, version: HTML_BLOCK_VERSION }
}

export const timelineExample = `<style>
.route { margin: 0; padding: 0 0 0 24px; list-style: none; border-left: 3px solid #9ab58b; }
.route li { position: relative; padding: 0 0 22px 12px; }
.route li::before { content: ""; position: absolute; left: -32px; top: 7px; width: 12px; height: 12px; border-radius: 50%; background: #43765a; }
.route strong { color: #245c45; font-size: 16px; }
.route p { margin: 6px 0 0; color: #65715e; }
@media (max-width: 400px) { .route { padding-left: 18px; } .route li::before { left: -26px; } }
</style>
<ol class="route">
  <li><strong>看房前</strong><p>明确预算、租期与通勤要求。</p></li>
  <li><strong>看房时</strong><p>观察房屋情况，把需要确认的事项记录下来。</p></li>
  <li><strong>入住前</strong><p>核对交接事项，保存自己的记录。</p></li>
</ol>`
