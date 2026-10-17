import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CandidateRequest,
  DraftSession,
  fingerprint,
  mergeSuggestions,
} from '../src/lib/topic-builder/session.js'
const deferred = () => {
  let resolve, reject
  const promise = new Promise((a, b) => {
    resolve = a
    reject = b
  })
  return { promise, resolve, reject }
}

test('loaded documents ignore JSON property ordering but preserve reading order', () => {
  assert.equal(fingerprint({generation:{warnings:['提示']},reviewNotes:['编辑']}), fingerprint({reviewNotes:['编辑'],generation:{warnings:['提示']}}))
  assert.notEqual(fingerprint({blocks:['a','b']}), fingerprint({blocks:['b','a']}))
})
test('AI suggestions preserve answers changed during chat', () => {
  assert.deepEqual(
    mergeSuggestions(
      { topic: '我的主题', audience: '', style: 'cards', source: '原文' },
      { topic: 'AI主题', audience: '新手', style: 'docs', source: '编造' },
    ),
    { topic: '我的主题', audience: '新手', style: 'cards', source: '原文' },
  )
})
test('generation freezes questionnaire and ignores canceled/late results', async () => {
  const request = new CandidateRequest(),
    form = { topic: '原主题', source: '原文' },
    first = deferred()
  let submitted
  const old = request.start(form, (data) => {
    submitted = data
    return first.promise
  })
  form.topic = '新主题'
  assert.equal(submitted.topic, '原主题')
  request.cancel()
  first.resolve({ blocks: ['旧结果'] })
  assert.equal(await old, null)
  const second = await request.start(form, async (data) => ({
    form: data,
    blocks: ['候选'],
  }))
  assert.equal(second.form.topic, '新主题')
  assert.equal(form.blocks, undefined)
})
test('late failure cannot replace next successful candidate', async () => {
  const request = new CandidateRequest(),
    old = deferred(),
    a = request.start({}, () => old.promise)
  assert.deepEqual(await request.start({}, async () => ({ blocks: ['新'] })), {
    blocks: ['新'],
  })
  old.reject(new Error('超时'))
  assert.equal(await a, null)
})
test('save acknowledges submitted snapshot; layout and reading order survive reload', async () => {
  const session = new DraftSession(),
    done = deferred(),
    doc = {
      form: { topic: '初稿' },
      blocks: [
        { id: 'b', layout: { x: 52, y: 20, w: 45, h: 240 } },
        { id: 'a', layout: { x: 3, y: 20, w: 45, h: 240 } },
      ],
    }
  let payload
  const save = session.save(doc, async (path, options) => {
    payload = JSON.parse(options.body)
    return done.promise
  })
  assert.equal(session.busy, true)
  doc.form.topic = '保存期间修改'
  done.resolve({ id: 15, revision: 0 })
  assert.equal(await save, true)
  assert.notEqual(session.savedFingerprint, fingerprint(doc))
  assert.equal(payload.document.form.topic, '初稿')
  const reopened = new DraftSession()
  reopened.load({ id: 15, revision: 0, document: payload.document })
  assert.deepEqual(
    payload.document.blocks.map((b) => b.id),
    ['b', 'a'],
  )
  assert.equal(payload.document.blocks[0].layout.x, 52)
  assert.equal(reopened.savedFingerprint, fingerprint(payload.document))
})
test('ambiguous first save retries same idempotent snapshot and retains newer input', async () => {
  const session = new DraftSession(),
    doc = { topic: '第一次' },
    requests = []
  await assert.rejects(
    session.save(doc, async (p, o) => {
      requests.push(o.body)
      throw new Error('连接中断')
    }),
  )
  doc.topic = '后来修改'
  await session.save(doc, async (p, o) => {
    requests.push(o.body)
    return { id: 1, revision: 0 }
  })
  assert.equal(requests[0], requests[1])
  assert.notEqual(session.savedFingerprint, fingerprint(doc))
  assert.equal(doc.topic, '后来修改')
  await session.save(doc, async (p, o) => {
    assert.match(p, /1\/builder-document/)
    assert.equal(JSON.parse(o.body).revision, 0)
    return { id: 1, revision: 1 }
  })
  assert.equal(session.revision, 1)
  assert.equal(session.savedFingerprint, fingerprint(doc))
})
test('conflicts preserve revision, saved fingerprint and input', async () => {
  const session = new DraftSession()
  session.load({ id: 1, revision: 2, document: { title: '旧' } })
  const current = { title: '我的修改' },
    saved = session.savedFingerprint
  await assert.rejects(
    session.save(current, async () => {
      throw new Error('409')
    }),
  )
  assert.equal(session.revision, 2)
  assert.equal(session.savedFingerprint, saved)
  assert.equal(current.title, '我的修改')
  assert.equal(session.busy, false)
})
