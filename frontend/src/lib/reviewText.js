// 审批页展示用的纯函数：把不同类型的快照整理成可连续阅读的文本。

export const typeLabel = (value) => ({ ARTICLE: '文章', COLLECTION: '专题', COMMENT: '评论' }[value] || value)
export const stateLabel = (value) => ({ PENDING: '等待处理', APPROVED: '已通过', REJECTED: '已驳回', UPHELD: '已维持不通过', OVERTURNED: '已放行', INVALID: '提交已失效' }[value] || value)
export const levelLabel = (value) => ({ LOW: '低', MEDIUM: '中', HIGH: '高' }[value] || value)
export const levelClass = (value) => ({ HIGH: 'danger', MEDIUM: 'amber', LOW: 'neutral' }[value] || 'neutral')
// 作者侧一次提交的当前状态 → [文案, 徽标样式]
export function flowChip(flow) {
  if (!flow) return ['草稿', 'neutral']
  return ({ IN_REVIEW: ['待审核', 'amber'], SCHEDULED: ['待定时公开', 'amber'], AUTO_REJECTED: ['未通过 · 复核中', 'danger'], REJECTED: ['未通过', 'danger'], UPHELD: ['未通过', 'danger'], PUBLISHED: ['已发布', 'green'], INVALID: ['已失效', 'neutral'] }[flow.status] || ['处理中', 'neutral'])
}

// 专题冻结快照 → 文本：章节作 ## 标题，条目标题作 ### 标题，其余按原文。
export function outlineText(outline) {
  if (!outline) return ''
  const lines = []
  for (const section of outline.sections || []) {
    if (section.title) lines.push(`## ${section.title}`)
    if (section.description) lines.push(section.description)
    for (const entry of section.entries || []) {
      if (entry.title || entry.postTitle) lines.push(`### ${entry.title || entry.postTitle}`)
      if (entry.annotation) lines.push(`> ${entry.annotation}`)
      if (entry.body) lines.push(entry.body)
      if (entry.externalUrl) lines.push(entry.externalUrl)
    }
  }
  return lines.join('\n\n')
}

// 统一为 { title, summary, body, visibility }。
export function readable(type, content) {
  if (!content) return null
  if (type === 'COLLECTION') {
    const m=content.outline?.metadata,c=content.outline?.collection
    const extra=[c?.audience && `适用人群：${c.audience}`,c?.goal && `目标：${c.goal}`,m?.seoTitle && `SEO：${m.seoTitle}`,m?.tags?.length && `标签：${m.tags.join('、')}`].filter(Boolean).join('\n')
    return { title: content.name, summary: content.description, body: extra+'\n'+outlineText(content.outline), visibility: content.visibility,coverId:m?.coverId }
  }
  if (type === 'COMMENT') return { title: '评论', summary: '', body: content.body, visibility: 'PUBLIC' }
  return { title: content.title, summary: content.summary, body: content.body, visibility: 'PUBLIC' }
}

export const timeLabel = (value) => value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : ''

// 发布/评论/专题发布返回 202 时的提示：送审或自动不通过（不含命中词）。
export function outcomeNotice(result) {
  if (!result) return ''
  if (result.outcome === 'AUTO_REJECTED') return result.message || '未通过：包含不允许发布的内容，已转人工复核。'
  if (result.outcome === 'PENDING') return result.message || '内容已送审，审核通过后才会公开。'
  return result.message || ''
}

export const submitNotice = (error) => error?.message || '提交失败，请稍后再试'
