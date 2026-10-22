// FINNS-18 prototype: safe Markdown preview. Produces a token tree only; templates render tokens with text interpolation,
// so raw HTML / scripts are shown as plain text and never executed. Images are never loaded (no remote requests).
const SAFE_LINK = /^(https?:\/\/|\/(?!\/)|#|mailto:)/i

export function inlineTokens(text, notes = { html: 0, images: 0, links: 0 }) {
  const out = []; let rest = String(text ?? '')
  // link target: no spaces, one level of balanced parentheses allowed (e.g. javascript:alert(1))
  const pattern = /(!\[([^\]]*)\]\(((?:[^()\s]|\([^()\s]*\))*)[^)]*\))|(\[([^\]]+)\]\(((?:[^()\s]|\([^()\s]*\))*)[^)]*\))|(\*\*([^*]+)\*\*)|(`([^`]+)`)/
  while (rest) {
    const m = rest.match(pattern)
    if (!m) { out.push({ k: 'text', text: rest }); break }
    if (m.index) out.push({ k: 'text', text: rest.slice(0, m.index) })
    if (m[1]) { notes.images += 1; out.push({ k: 'image', alt: m[2] || '图片' }) }
    else if (m[4]) {
      if (SAFE_LINK.test(m[6])) out.push({ k: 'link', text: m[5], href: m[6] })
      else { notes.links += 1; out.push({ k: 'blocked', text: m[5], href: m[6] }) }
    } else if (m[7]) out.push({ k: 'strong', text: m[8] })
    else out.push({ k: 'code', text: m[10] })
    rest = rest.slice(m.index + m[0].length)
  }
  return out
}

export function markdownBlocks(source = '') {
  const notes = { html: 0, images: 0, links: 0 }, blocks = []
  let paragraph = [], code = [], inCode = false
  const flush = () => { if (paragraph.length) { blocks.push({ k: 'p', inline: inlineTokens(paragraph.join('\n'), notes) }); paragraph = [] } }
  for (const line of String(source ?? '').split(/\r?\n/)) {
    if (/<\s*\/?\s*(script|style|iframe|object|embed|img|svg|link|form|input|a|div|span|b|i)\b|on\w+\s*=|javascript:/i.test(line)) notes.html += 1
    if (/^\s*```/.test(line)) { flush(); if (inCode) { blocks.push({ k: 'code', text: code.join('\n') }); code = [] } inCode = !inCode; continue }
    if (inCode) { code.push(line); continue }
    if (!line.trim()) { flush(); continue }
    const heading = line.match(/^(#{1,3})\s+(.+)$/)
    if (heading) { flush(); blocks.push({ k: 'h', level: heading[1].length, inline: inlineTokens(heading[2], notes) }); continue }
    if (/^[-*]\s+/.test(line)) { flush(); blocks.push({ k: 'li', inline: inlineTokens(line.replace(/^[-*]\s+/, ''), notes) }); continue }
    if (/^>\s?/.test(line)) { flush(); blocks.push({ k: 'quote', inline: inlineTokens(line.replace(/^>\s?/, ''), notes) }); continue }
    paragraph.push(line)
  }
  flush(); if (inCode) blocks.push({ k: 'code', text: code.join('\n') })
  return { blocks, notes }
}

export const noteText = notes => [
  notes.html && `${notes.html} 处类似 HTML/脚本的文字，已按纯文本显示，不会执行`,
  notes.images && `${notes.images} 张图片不会加载（首期不支持图片与外链图片）`,
  notes.links && `${notes.links} 个不安全链接已按文字显示`,
].filter(Boolean)
