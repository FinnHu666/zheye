import test from 'node:test'
import assert from 'node:assert/strict'
import { readingBlocks, markedSegments } from '../src/lib/reviewReading.js'
import { outlineText, readable, outcomeNotice, submitNotice, flowChip, levelLabel, stateLabel } from '../src/lib/reviewText.js'

test('reading blocks keep structure and never produce raw HTML', () => {
  const blocks = readingBlocks('# 标题\n\n正文 <script>alert(1)</script>\n\n- 条目\n> 引用\n```\ncode\n```')
  assert.deepEqual(blocks.map(b => b.kind), ['heading', 'paragraph', 'list', 'quote', 'code'])
  assert.equal(blocks[1].text, '正文 <script>alert(1)</script>')
})

test('hits are highlighted case-insensitively without altering the text', () => {
  const parts = markedSegments('Contains BadWord here', ['badword'])
  assert.equal(parts.map(p => p.text).join(''), 'Contains BadWord here')
  assert.deepEqual(parts.filter(p => p.hit).map(p => p.text), ['BadWord'])
})

test('collection outline is flattened into a continuous reading text', () => {
  const text = outlineText({ sections: [{ title: '第一章', description: '简介', entries: [{ title: '条目', annotation: '批注', body: '正文' }] }] })
  assert.match(text, /^## 第一章/)
  assert.match(text, /### 条目/)
  assert.match(text, /> 批注/)
  const view = readable('COLLECTION', { name: '专题', description: '说明', visibility: 'RESTRICTED', outline: { sections: [] } })
  assert.equal(view.title, '专题'); assert.equal(view.visibility, 'RESTRICTED')
  assert.equal(readable('ARTICLE', null), null)
})

test('submission outcomes: pending and auto-reject are notices, high sensitivity never shows words', () => {
  assert.equal(outcomeNotice({ outcome: 'PENDING', message: '内容已送审' }), '内容已送审')
  assert.match(outcomeNotice({ outcome: 'AUTO_REJECTED' }), /未通过/)
  assert.ok(!outcomeNotice({ outcome: 'AUTO_REJECTED', matches: ['词'] }).includes('词'))
  assert.equal(outcomeNotice({ outcome: 'PUBLISHED', message: '已发布' }), '已发布')
  assert.equal(submitNotice(new Error('网络错误')), '网络错误')
})

test('flow chips and labels cover every flow status and level', () => {
  assert.deepEqual(flowChip({ status: 'IN_REVIEW' }), ['待审核', 'amber'])
  assert.equal(flowChip({ status: 'AUTO_REJECTED' })[0], '未通过 · 复核中')
  assert.equal(flowChip({ status: 'PUBLISHED' })[1], 'green')
  assert.equal(flowChip(null)[0], '草稿')
  assert.equal(levelLabel('HIGH'), '高'); assert.equal(stateLabel('OVERTURNED'), '已放行')
})
