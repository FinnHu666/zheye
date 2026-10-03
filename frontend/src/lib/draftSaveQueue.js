/** Serialize saves without replacing newer local edits with old responses. */
export function createDraftSaveQueue({ snapshot, send, acknowledge, state }) {
  let generation = 0, saved = 0, running = null
  const changed = () => { generation++; state('有未保存修改') }
  const flush = () => {
    if (running) return running
    running = (async () => {
      while (saved < generation) {
        const savingGeneration = generation
        state('保存中…')
        try {
          const result = await send(snapshot())
          acknowledge(result); saved = savingGeneration
          state(saved === generation ? '已保存' : '有未保存修改')
        } catch (error) { state('保存失败，输入已保留'); throw error }
      }
    })().finally(() => { running = null })
    return running
  }
  return { changed, flush, get dirty() { return saved < generation } }
}
