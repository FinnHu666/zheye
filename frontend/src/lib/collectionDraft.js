const blank = value => !String(value || '').trim()

export function publicationIssues(document) {
  const issues = []
  const add = (section, entry, field, message) => issues.push({ section, entry, field, message })
  if (blank(document.name)) add(null, null, 'name', '填写专题名称')
  if (!document.sections.length) add(null, null, 'sections', '添加一个章节，再写一段内容')
  let count = 0
  document.sections.forEach((section, si) => {
    if (blank(section.title)) add(si, null, 'sectionTitle', `第 ${si + 1} 章：填写章节名称`)
    section.entries.forEach((entry, ei) => {
      count++
      const location = `${section.title || `第 ${si + 1} 章`} · 第 ${ei + 1} 条`
      if (entry.kind === 'NOTE' && blank(entry.body)) add(si, ei, 'body', `${location}：写下正文，或删除空内容`)
      if (entry.kind === 'LINK') {
        if (blank(entry.title)) add(si, ei, 'title', `${location}：填写链接标题`)
        let valid = false
        try { const url = new URL(entry.externalUrl); valid = ['http:', 'https:'].includes(url.protocol) && !!url.hostname } catch {}
        if (!valid) add(si, ei, 'externalUrl', `${location}：填写完整的 http/https 链接`)
      }
      if (entry.kind === 'POST' && !entry.postId) add(si, ei, 'postId', `${location}：选择一篇文章`)
    })
  })
  if (document.sections.length && !count) add(0, null, 'entries', '写下第一段内容后即可发布；也可以先保存草稿')
  return issues
}

export function importReadiness(document, source, parsedSource, target = null) {
  if (!document) return { canSave: false, message: '' }
  if (source !== parsedSource) return { canSave: false, message: '原文已经修改，请重新整理后再保存，避免导入旧内容。' }
  const sections = [...(target?.sections || []), ...document.sections]
  if (sections.length > 50 || sections.reduce((n, s) => n + s.entries.length, 0) > 500) {
    return { canSave: false, message: '专题最多 50 章、500 条。请删除多余章节或分批导入；数量调整后可以直接保存。' }
  }
  return { canSave: !blank(document.name), message: blank(document.name) ? '填写专题名称后即可保存。' : '' }
}
