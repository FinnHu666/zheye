import test from 'node:test'
import assert from 'node:assert/strict'
import { countChars, overLimit, missingForPublish, isBlank, sameFields, publicChip, workChip, publishLabel, errorKind, errorText, LIMITS } from '../src/lib/articles.js'

test('length counts code points, not UTF-16 units, and limits match the contract', () => {
  assert.equal(countChars('😀😀'), 2)
  assert.equal(countChars(null), 0)
  assert.deepEqual(LIMITS, { title: 120, summary: 280, body: 20000 })
  assert.deepEqual(overLimit({ title: '😀'.repeat(121), summary: 'a', body: '' }), ['title'])
  assert.deepEqual(overLimit({ title: '😀'.repeat(120), summary: '', body: '' }), [])
})

test('publishing needs all three fields while saving only needs one non-blank', () => {
  assert.deepEqual(missingForPublish({ title: 'a', summary: '  ', body: '' }), ['summary', 'body'])
  assert.equal(isBlank({ title: ' ', summary: '', body: '\n' }), true)
  assert.equal(isBlank({ title: 'x', summary: '', body: '' }), false)
  assert.equal(sameFields({ title: 'a' }, { title: 'a', summary: '', body: '' }), true)
})

test('status chips and publish label follow the server state', () => {
  assert.deepEqual(publicChip({ publicState: 'LIVE' }), ['已公开', 'green'])
  assert.deepEqual(publicChip({ publicState: 'DOWN' }), ['已被下架', 'danger'])
  assert.deepEqual(workChip({ work: 'RECHECK' }), ['未通过 · 复核中', 'danger'])
  assert.equal(publishLabel({ status: { publicState: 'LIVE' } }), '提交更新')
  assert.equal(publishLabel({ status: { publicState: 'WITHDRAWN' } }), '重新公开')
  assert.equal(publishLabel({ status: { publicState: 'LIVE' }, submitted: { version: 3 } }), '重新提交（旧提交作废）')
  assert.equal(publishLabel(null), '发布')
})

test('error codes map to page reactions and texts', () => {
  assert.equal(errorKind({ status: 401 }), 'session')
  assert.equal(errorKind({ status: 409, code: 'VERSION_CONFLICT' }), 'conflict')
  assert.equal(errorKind({ status: 409, code: 'REQUEST_REUSED' }), 'conflict')
  assert.equal(errorKind({ status: 503, code: 'DETECTION_UNAVAILABLE' }), 'retryable')
  assert.equal(errorKind({ status: 409, code: 'TAKEN_DOWN' }), 'state')
  assert.equal(errorKind({ status: 409, code: 'NOT_DELETABLE' }), 'state')
  assert.equal(errorKind({ status: 400, code: 'TOO_LONG' }), 'input')
  assert.equal(errorKind(new Error('x')), 'other')
  assert.match(errorText({ status: 401 }), /输入仍保留/)
  assert.match(errorText({ status: 503, code: 'DETECTION_UNAVAILABLE' }), /不会重复提交/)
  assert.equal(errorText({ status: 400, code: 'VALIDATION', message: '请补全标题' }), '请补全标题')
})
