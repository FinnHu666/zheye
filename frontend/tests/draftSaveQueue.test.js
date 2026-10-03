import test from 'node:test'
import assert from 'node:assert/strict'
import { createDraftSaveQueue } from '../src/lib/draftSaveQueue.js'

test('edits during a save are serialized using the acknowledged revision and retained locally', async () => {
  let local = { revision: 0, body: 'first' }, resolveFirst
  const sent = []
  const queue = createDraftSaveQueue({ snapshot: () => ({ ...local }), state: () => {}, acknowledge: result => { local.revision = result.revision }, send: async doc => {
    sent.push(doc)
    if (sent.length === 1) await new Promise(resolve => { resolveFirst = resolve })
    return { revision: doc.revision + 1, body: doc.body }
  } })
  queue.changed(); const saving = queue.flush()
  local.body = 'newer input'; queue.changed(); resolveFirst(); await saving
  assert.deepEqual(sent, [{ revision: 0, body: 'first' }, { revision: 1, body: 'newer input' }])
  assert.equal(local.body, 'newer input'); assert.equal(local.revision, 2); assert.equal(queue.dirty, false)
})

test('a failed save preserves dirty input and explicit retry saves it', async () => {
  let fails = true, acknowledgements = 0
  const queue = createDraftSaveQueue({ snapshot: () => ({ body: 'keep me' }), state: () => {}, acknowledge: () => acknowledgements++, send: async () => {
    if (fails) throw new Error('409 version conflict')
    return { revision: 2 }
  } })
  queue.changed(); await assert.rejects(queue.flush(), /409/)
  assert.equal(queue.dirty, true); assert.equal(acknowledgements, 0)
  fails = false; await queue.flush(); assert.equal(queue.dirty, false)
})
