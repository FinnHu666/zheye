import test from 'node:test'
import assert from 'node:assert/strict'
import { publicationIssues, importReadiness } from '../src/lib/collectionDraft.js'

test('body-only notes publish without forcing headings; incomplete links identify their field and position', () => {
  const draft = { name: '经验', sections: [{ title: '第一章', entries: [{ kind: 'NOTE', title: '', body: '实际经验' }] }, { title: '资料', entries: [{ kind: 'LINK', title: '参考', externalUrl: 'javascript:alert(1)' }] }] }
  assert.deepEqual(publicationIssues(draft), [{ section: 1, entry: 0, field: 'externalUrl', message: '资料 · 第 1 条：填写完整的 http/https 链接' }])
  draft.sections[1].entries[0].externalUrl = 'https://example.com'
  assert.deepEqual(publicationIssues(draft), [])
})

test('changes to raw source prevent saving a stale outline and current chapter counts unblock a reduced outline', () => {
  const doc = { name: '导入', sections: Array.from({ length: 51 }, () => ({ title: '章节', entries: [] })) }
  assert.equal(importReadiness(doc, 'original', 'original').canSave, false)
  doc.sections.pop()
  assert.equal(importReadiness(doc, 'original', 'original').canSave, true)
  assert.equal(importReadiness(doc, 'changed', 'original').canSave, false)
  assert.equal(importReadiness(doc, 'changed', 'changed').canSave, true)
  assert.equal(importReadiness(doc, 'changed', 'changed', { sections: [{ entries: [] }] }).canSave, false)
})
