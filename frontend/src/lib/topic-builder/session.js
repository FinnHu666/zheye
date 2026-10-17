export const clone = (value) => JSON.parse(JSON.stringify(value))
// JSON member order is incidental; array order (including reading order) is meaningful.
export const fingerprint = (value) => JSON.stringify(value, (_, item) =>
  item && typeof item === 'object' && !Array.isArray(item)
    ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]))
    : item,
)
export function mergeSuggestions(current, suggestions) {
  const merged = { ...current }
  for (const key of [
    'topic',
    'audience',
    'purpose',
    'goal',
    'styleNote',
    'constraints',
    'context',
  ]) {
    if (!merged[key]?.trim() && typeof suggestions[key] === 'string')
      merged[key] = suggestions[key]
  }
  return merged
}
// A canceled request never changes the active document, even if its transport completes later.
export class CandidateRequest {
  run = 0
  controller = null
  cancel() {
    this.run++
    this.controller?.abort()
    this.controller = null
  }
  async start(form, request) {
    this.cancel()
    const run = this.run,
      snapshot = clone(form)
    this.controller = new AbortController()
    try {
      const result = await request(snapshot, this.controller.signal)
      return run === this.run ? result : null
    } catch (error) {
      if (run === this.run) throw error
      return null
    } finally {
      if (run === this.run) this.controller = null
    }
  }
}
export class DraftSession {
  id = null
  revision = 0
  savedFingerprint = ''
  pendingCreate = null
  busy = false
  load(stored) {
    this.id = stored.id
    this.revision = stored.revision
    this.savedFingerprint = fingerprint(stored.document)
    this.pendingCreate = null
  }
  async save(document, request) {
    if (this.busy) return false
    this.busy = true
    try {
      let snapshot = clone(document),
        result
      if (!this.id) {
        this.pendingCreate ??= {
          idempotencyKey: crypto.randomUUID(),
          document: snapshot,
        }
        snapshot = this.pendingCreate.document
        result = await request('/api/me/topic-builder/drafts', {
          method: 'POST',
          body: JSON.stringify(this.pendingCreate),
        })
        this.pendingCreate = null
      } else {
        result = await request(
          `/api/me/collections/${this.id}/builder-document`,
          {
            method: 'PUT',
            body: JSON.stringify({
              revision: this.revision,
              document: snapshot,
            }),
          },
        )
      }
      this.id = result.id
      this.revision = result.revision
      this.savedFingerprint = fingerprint(snapshot)
      return true
    } finally {
      this.busy = false
    }
  }
}
