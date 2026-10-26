// FINNS-19 R1: a collection is edited as one Markdown text. "## 章节名" starts a chapter; text before the first chapter is the introduction;
// a line "[[文章:ID|标题]]" is a reference to the author's own published article; everything else is chapter text (Markdown).
const REF = /^\[\[文章:(\d+)(?:\|(.*))?\]\]\s*$/
let seq = 0
const id = () => `m${Date.now().toString(36)}${++seq}`

export function toMarkdown(draft) {
  const out = []
  if ((draft.description || '').trim()) out.push(draft.description.trim(), '')
  for (const s of draft.sections) {
    out.push(`## ${s.title}`)
    if ((s.description || '').trim()) out.push(s.description.trim())
    for (const e of s.entries) {
      if (e.kind === 'POST') out.push(`[[文章:${e.postId ?? ''}|${e.postTitle || ''}]]`)
      else if (e.kind === 'LINK') out.push(`[${e.title || e.externalUrl}](${e.externalUrl})`)
      else out.push(...[e.title ? `### ${e.title}` : '', e.body].filter(Boolean))
      if (e.annotation) out.push(`> ${e.annotation}`)
    }
    out.push('')
  }
  return out.join('\n').replace(/\n+$/, '\n')
}

export function fromMarkdown(text) {
  const sections = []; const intro = []; let cur = null, buf = [], lastRef = null
  const flush = () => { if (cur && buf.join('').trim()) cur.entries.push({ id: id(), kind: 'NOTE', title: '', body: buf.join('\n').trim(), annotation: '', externalUrl: '', postId: null, postTitle: '' }); buf = [] }
  for (const line of String(text ?? '').split('\n')) {
    const h = /^##\s+(.*)$/.exec(line), ref = REF.exec(line)
    if (lastRef && /^>\s?/.test(line) && !buf.length) { lastRef.annotation = line.replace(/^>\s?/, '').trim(); lastRef = null; continue }
    lastRef = null
    if (h) { flush(); cur = { id: id(), title: h[1].trim(), description: '', entries: [] }; sections.push(cur) }
    else if (!cur) intro.push(line)
    else if (ref) { flush(); lastRef = { annotation: '' }; cur.entries.push(Object.assign(lastRef, { id: id(), kind: 'POST', title: '', body: '', annotation: '', externalUrl: '', postId: ref[1] ? Number(ref[1]) : null, postTitle: ref[2] || '' })) }
    else buf.push(line)
  }
  flush()
  return { description: intro.join('\n').trim(), sections }
}

// Chapters with the offset of their heading line, for the directory (table of contents).
export function outlineOf(text) {
  const out = []; let at = 0
  String(text ?? '').split('\n').forEach((line, i) => { const h = /^##\s+(.*)$/.exec(line); if (h) out.push({ title: h[1].trim() || '未命名章节', line: i, offset: at }); at += line.length + 1 })
  return out
}
