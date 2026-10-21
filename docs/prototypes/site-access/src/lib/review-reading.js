// Reading-only prototype helpers. Text is rendered by Vue interpolation, never raw HTML.
export function readingBlocks(text = '') {
  const blocks = []; let paragraph = [], code = [], inCode = false
  const flush = () => { if (paragraph.length) { blocks.push({ kind: 'paragraph', text: paragraph.join('\n') }); paragraph = [] } }
  for (const line of String(text).split(/\r?\n/)) {
    if (/^\s*```/.test(line)) { flush(); if (inCode) { blocks.push({ kind: 'code', text: code.join('\n') }); code = [] } inCode = !inCode; continue }
    if (inCode) { code.push(line); continue }
    if (!line.trim()) { flush(); continue }
    const heading = line.match(/^(#{1,3})\s+(.+)$/)
    if (heading) { flush(); blocks.push({ kind: 'heading', level: heading[1].length, text: heading[2] }); continue }
    if (/^[-*]\s+/.test(line)) { flush(); blocks.push({ kind: 'list', text: line.replace(/^[-*]\s+/, '') }); continue }
    if (/^>\s?/.test(line)) { flush(); blocks.push({ kind: 'quote', text: line.replace(/^>\s?/, '') }); continue }
    paragraph.push(line)
  }
  flush(); if (inCode) blocks.push({ kind: 'code', text: code.join('\n') })
  return blocks
}
export function markedSegments(text, terms = []) {
  const source = String(text || ''), needles = [...new Set(terms.filter(Boolean))].sort((a,b) => b.length-a.length)
  const output = []; let position = 0
  while (position < source.length) {
    let start = -1, term = ''
    for (const needle of needles) { const found = source.toLowerCase().indexOf(needle.toLowerCase(), position); if (found !== -1 && (start === -1 || found < start)) { start = found; term = needle } }
    if (start === -1) { output.push({ text: source.slice(position), hit: false }); break }
    if (start > position) output.push({ text: source.slice(position, start), hit: false })
    output.push({ text: source.slice(start,start+term.length), hit: true }); position = start+term.length
  }
  return output
}
