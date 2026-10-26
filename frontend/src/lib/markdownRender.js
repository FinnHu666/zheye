// 文章 Markdown 渲染的安全规则（契约 CONTRACT_CONTENT_IMAGES §3.2，PUBLISH-D14）。
// Vditor/Lute 负责解析与 sanitize；这里补两条本站规则：原始 HTML 一律按文字显示，外链图片按 CT-R13 处理。
export const VDITOR_CDN = '/vendor/vditor'

// 在围栏代码、缩进代码与行内代码之外把 "<" 转义，使原始 HTML（含 <img>）按文字显示。
export function escapeRawHtml(markdown) {
  const lines = String(markdown ?? '').split('\n')
  let fence = null, prevBlank = true
  return lines.map((line) => {
    const open = /^\s{0,3}(`{3,}|~{3,})/.exec(line)
    if (fence) { if (open && open[1][0] === fence[0] && open[1].length >= fence.length) fence = null; prevBlank = false; return line }
    if (open) { fence = open[1]; prevBlank = false; return line }
    const indented = /^( {4}|\t)/.test(line) && prevBlank
    prevBlank = !line.trim() || (indented && prevBlank)
    if (indented) return line
    return escapeOutsideInlineCode(line)
  }).join('\n')
}
function escapeOutsideInlineCode(line) {
  let out = '', i = 0
  while (i < line.length) {
    if (line[i] === '`') {
      const run = /^`+/.exec(line.slice(i))[0], close = line.indexOf(run, i + run.length)
      if (close > -1 && line[close + run.length] !== '`') { out += line.slice(i, close + run.length); i = close + run.length; continue }
      out += run; i += run.length; continue
    }
    out += line[i] === '<' ? '&lt;' : line[i]; i++
  }
  return out
}

const hostOf = (src) => { try { return new URL(src).host } catch { return '' } }
function notice(doc, text) { const span = doc.createElement('span'); span.className = 'md-img-fail'; span.textContent = text; return span }

// 只保留 http/https 图片并加上 no-referrer、懒加载；其余协议换成“不受支持”提示；链接按白名单过滤。
export function imagePolicy(html, doc = globalThis.document) {
  const tpl = doc.createElement('template'); tpl.innerHTML = html
  for (const img of tpl.content.querySelectorAll('img')) {
    const src = (img.getAttribute('src') || '').trim(), alt = img.getAttribute('alt') || ''
    if (!/^https?:\/\/[^\s]+$/i.test(src)) { img.replaceWith(notice(doc, `图片地址不受支持${alt ? '：' + alt : ''}`)); continue }
    for (const name of [...img.getAttributeNames()]) if (!['src', 'alt', 'title'].includes(name)) img.removeAttribute(name)
    img.setAttribute('referrerpolicy', 'no-referrer'); img.setAttribute('loading', 'lazy'); img.setAttribute('decoding', 'async'); img.className = 'md-img'
  }
  // 链接只保留 http/https、站内绝对路径与锚点（与原规则一致）；其余（javascript:、//外站、data: 等）只留文字
  for (const a of tpl.content.querySelectorAll('a')) {
    const href = (a.getAttribute('href') || '').trim()
    if (!/^(https?:\/\/|\/(?!\/)|#)/i.test(href)) { a.replaceWith(doc.createTextNode(a.textContent)); continue }
    if (/^https?:/i.test(href)) { a.setAttribute('rel', 'noopener noreferrer nofollow'); a.setAttribute('target', '_blank') }
  }
  // 不加载额外渲染器（数学公式、图表、流程图等不在自托管白名单内）：去掉触发类名，按普通文本/代码显示
  for (const el of tpl.content.querySelectorAll('[class]')) {
    const kept = [...el.classList].filter((c) => !EXTRA_RENDERERS.test(c))
    if (kept.length !== el.classList.length) el.className = kept.join(' ')
  }
  return tpl.innerHTML
}
const EXTRA_RENDERERS = /^(language-(math|mermaid|echarts|flowchart|graphviz|markmap|mindmap|abc|plantuml|smiles|wavedrom)|vditor-math)$/

// 外链图片加载失败：换成“图片无法加载：说明（域名）”，不重试。返回解除监听的函数。
export function imageFallback(container) {
  const onError = (event) => {
    const img = event.target
    if (img?.tagName !== 'IMG' || !img.classList.contains('md-img')) return
    const alt = img.getAttribute('alt') || '', host = hostOf(img.getAttribute('src'))
    img.replaceWith(notice(img.ownerDocument, `图片无法加载${alt ? '：' + alt : ''}${host ? '（' + host + '）' : ''}`))
  }
  container.addEventListener('error', onError, true)
  return () => container.removeEventListener('error', onError, true)
}

export const previewOptions = (extra = {}) => ({
  cdn: VDITOR_CDN, lang: 'zh_CN', mode: 'light', theme: { current: 'light', path: `${VDITOR_CDN}/dist/css/content-theme` },
  icon: 'ant', hljs: { enable: false },
  markdown: { sanitize: true, inlineMath: false, mathBlockPreview: false },
  transform: (html) => imagePolicy(html), ...extra,
})
