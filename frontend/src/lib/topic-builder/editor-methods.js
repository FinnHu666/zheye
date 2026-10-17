import { nextTick } from 'vue'
import { api } from '../api'
import { clone } from './session'
import { styles, examples } from './options'
import { compileHtmlBlock } from './html'
const uid = () => crypto.randomUUID()
export default {
  openHtmlCode(block = null) {
    if (!block && this.blocks.length >= 50) {
      this.notify('最多添加 50 个模块。')
      return
    }
    if (
      !block &&
      this.blocks.filter((item) => item.kind === 'html').length >= 20
    ) {
      this.notify('最多添加 20 个 HTML 模块。')
      return
    }
    this.codeEditId = block?.id || null
    this.codeInitialSource = block?.code?.source || ''
    this.codeInitialTitle = block?.title || ''
    this.codeInitialHeight = block?.code?.frameHeight || 360
    this.codeDialogOpen = true
  },
  setHtmlFrameHeight(event) {
    const height = Number(event.target.value)
    if (!this.activeBlock?.code) return
    this.activeBlock.code.frameHeight = Number.isFinite(height)
      ? Math.round(Math.max(120, Math.min(1200, height)))
      : 360
    event.target.value = this.activeBlock.code.frameHeight
  },
  commitHtmlCode(payload) {
    try {
      compileHtmlBlock(payload.code.source)
    } catch (error) {
      this.notify(error.message)
      return
    }
    let block = this.codeEditId
      ? this.blocks.find((item) => item.id === this.codeEditId)
      : null
    if (this.codeEditId && !block) {
      this.notify('原模块已不存在，请取消后重新导入。')
      return
    }
    if (block) {
      block.title = payload.title
      block.code = clone(payload.code)
    } else {
      block = {
        id: uid(),
        kind: 'html',
        title: payload.title,
        body: '',
        code: clone(payload.code),
      }
      if (this.layoutMode === 'canvas')
        block.layout = {
          x: 3,
          y: Math.min(10000, this.canvasHeight),
          w: 90,
          h: Math.min(1600, payload.code.frameHeight + 140),
        }
      this.blocks.push(block)
    }
    this.selectedBlock = block.id
    this.previewMode = 'edit'
    this.codeDialogOpen = false
    this.savedNotice = false
    nextTick(() => this.scrollToBlock(block.id))
    this.notify(
      this.codeEditId
        ? '模块代码已更新，原位置与尺寸保留。'
        : 'HTML 模块已添加，可以继续移动和调整大小。',
    )
  },
  styleFor(id) {
    return styles.find((s) => s.id === id) || styles[0]
  },
  lines(body) {
    return body
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
      // The ordered list renders numbering; keep authored/model source unchanged.
      .map((s) => s.replace(/^(?:\d{1,3}[.、．)]|[（(]\d{1,3}[）)])\s+/, ''))
  },
  notify(text) {
    this.toast = text
    this.timers.push(
      setTimeout(() => {
        this.toast = ''
      }, 2600),
    )
  },
  chooseExample(id) {
    const example = examples.find((e) => e.id === id)
    this.activeExample = id
    this.chatInput = `我想创建「${example.topic}」，主要给${example.audience}看，用途是${example.purpose}，页面希望是${this.styleFor(example.style).name}。`
  },
  openQuestionnaire(step = 0) {
    this.focusOrigin = document.activeElement
    this.previousView = this.view
    this.questionStep = typeof step === 'number' ? step : 0
    this.formError = ''
    this.questionnaireOpen = true
    nextTick(() => {
      document.querySelector('.questionnaire .close-button')?.focus()
    })
  },
  closeQuestionnaire() {
    this.questionnaireOpen = false
    this.formError = ''
    nextTick(() => this.focusOrigin?.focus())
  },
  handleDialogKeys(event) {
    if (
      (!this.questionnaireOpen && !this.pendingNavigation) ||
      event.key !== 'Tab'
    )
      return
    const selector = this.pendingNavigation
      ? '.confirm-dialog button'
      : '.questionnaire button:not([disabled]),.questionnaire input:not([type=file]),.questionnaire select,.questionnaire textarea'
    const all = [...document.querySelectorAll(selector)].filter(
      (e) => e.getClientRects().length,
    )
    if (!all.length) return
    const first = all[0],
      last = all[all.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  },
  validateBasics() {
    const missing = !this.form.topic.trim()
      ? '专题主题'
      : !this.form.audience.trim()
        ? '目标人群'
        : !this.form.purpose.trim()
          ? '主要用途'
          : ''
    if (missing) {
      this.formError = `请先确认${missing}。`
      this.questionStep = 0
      nextTick(() =>
        document
          .getElementById(
            missing === '专题主题'
              ? 'topic-field'
              : missing === '目标人群'
                ? 'audience-field'
                : 'purpose-field',
          )
          ?.focus(),
      )
      return false
    }
    this.formError = ''
    return true
  },
  jumpStep(step) {
    if (step > 0 && !this.validateBasics()) return
    this.questionStep = step
    this.formError = ''
  },
  nextStep() {
    if (!this.validateBasics()) return
    if (this.questionStep < 3) {
      this.questionStep++
      return
    }
    this.startGeneration()
  },
  fillExampleSource() {
    const example = examples.find((e) => e.id === this.activeExample)
    if (example) this.form.source = example.source
    this.fileError = ''
  },
  async readSourceFile(event) {
    const file = event.target.files?.[0]
    if (!file) return
    try {
      if (!/\.(txt|md|markdown)$/i.test(file.name))
        throw new Error('请选择 TXT 或 Markdown 文件。')
      if (file.size > 1048576) throw new Error('文件最多 1 MiB。')
      const text = new TextDecoder('utf-8', { fatal: true }).decode(
        await file.arrayBuffer(),
      )
      if (text.length > 16000)
        throw new Error('最多接收 16,000 字，请分批体验。')
      this.form.source = text
      this.fileError = ''
    } catch (error) {
      this.fileError =
        error instanceof TypeError
          ? '无法读取，请使用 UTF-8 文字文件。'
          : error.message
    } finally {
      event.target.value = ''
    }
  },
  selectBlock(id) {
    this.selectedBlock = id
  },
  scrollToBlock(id) {
    document
      .getElementById(`block-${id}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  },
  moveBlock(index, direction) {
    const target = index + direction
    if (target < 0 || target >= this.blocks.length) return
    ;[this.blocks[index], this.blocks[target]] = [
      this.blocks[target],
      this.blocks[index],
    ]
  },
  beginBlockDrag(event, id, surface) {
    if (
      event.button !== 0 ||
      (surface === 'preview' && this.previewMode !== 'edit')
    )
      return
    this.cancelBlockDrag()
    event.preventDefault()
    event.currentTarget.focus()
    this.selectedBlock = id
    this.dragState = {
      type: 'sort',
      id,
      surface,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      x: event.clientX,
      y: event.clientY,
      active: false,
      targetId: null,
      after: false,
      axis: 'vertical',
      handle: event.currentTarget,
    }
    event.currentTarget.setPointerCapture(event.pointerId)
  },
  updateBlockDrag(event) {
    const drag = this.dragState
    if (!drag || event.pointerId !== drag.pointerId) return
    drag.x = event.clientX
    drag.y = event.clientY
    if (drag.type === 'canvas') {
      event.preventDefault()
      this.updateCanvasTransform()
      return
    }
    if (
      !drag.active &&
      Math.hypot(drag.x - drag.startX, drag.y - drag.startY) > 6
    ) {
      drag.active = true
      this.scrollDragFrame = requestAnimationFrame(this.scrollWhileDragging)
    }
    if (drag.active) {
      event.preventDefault()
      this.updateDropTarget()
    }
  },
  updateDropTarget() {
    const drag = this.dragState
    if (!drag?.active) return
    const selector =
      drag.surface === 'preview'
        ? '.topic-block[data-block-id]'
        : '.structure-item[data-block-id]'
    const target = document.elementFromPoint(drag.x, drag.y)?.closest(selector)
    drag.targetId = null
    if (!target || target.dataset.blockId === drag.id) return
    const rect = target.getBoundingClientRect()
    const peers = [...target.parentElement.children].filter(
      (el) => el.matches(selector) && el !== target,
    )
    const horizontal =
      drag.surface === 'preview' &&
      peers.some(
        (el) => Math.abs(el.getBoundingClientRect().top - rect.top) < 5,
      )
    drag.targetId = target.dataset.blockId
    drag.axis = horizontal ? 'horizontal' : 'vertical'
    drag.after = horizontal
      ? drag.x > rect.left + rect.width / 2
      : drag.y > rect.top + rect.height / 2
  },
  scrollWhileDragging() {
    const drag = this.dragState
    if (!drag?.active) return
    const edge = 64
    const delta =
      drag.y < edge
        ? -Math.min(16, (edge - drag.y) / 3)
        : drag.y > innerHeight - edge
          ? Math.min(16, (drag.y - innerHeight + edge) / 3)
          : 0
    if (delta) {
      window.scrollBy(0, delta)
      this.updateDropTarget()
    }
    this.scrollDragFrame = requestAnimationFrame(this.scrollWhileDragging)
  },
  finishBlockDrag(event) {
    const drag = this.dragState
    if (!drag || event.pointerId !== drag.pointerId) return
    if (drag.type === 'canvas') {
      const block = this.blocks.find((b) => b.id === drag.id)
      if (block && drag.active) {
        block.layout = this.roundCanvasLayout(drag.layout)
        this.notify(
          drag.action === 'resize' ? '已调整模块大小。' : '已调整模块位置。',
        )
      }
      const { id } = drag
      this.cancelBlockDrag()
      this.focusDragHandle(id, 'preview')
      return
    }
    if (drag.active && drag.targetId) {
      const index = this.blocks.findIndex((b) => b.id === drag.id)
      const target = this.blocks.findIndex((b) => b.id === drag.targetId)
      if (index >= 0 && target >= 0) {
        const [block] = this.blocks.splice(index, 1)
        const destination =
          this.blocks.findIndex((b) => b.id === drag.targetId) +
          (drag.after ? 1 : 0)
        this.blocks.splice(destination, 0, block)
        this.notify(
          `已将「${block.title || '未命名内容'}」移到第 ${destination + 1} 位。`,
        )
      }
    }
    const { id, surface } = drag
    this.cancelBlockDrag()
    this.focusDragHandle(id, surface)
  },
  cancelBlockDrag() {
    const drag = this.dragState
    this.dragState = null
    cancelAnimationFrame(this.scrollDragFrame)
    if (drag?.handle.hasPointerCapture(drag.pointerId))
      drag.handle.releasePointerCapture(drag.pointerId)
  },
  handleDragEscape(event) {
    if (event.key === 'Escape' && this.dragState) {
      event.preventDefault()
      this.cancelBlockDrag()
      this.notify('已取消移动，原顺序保留。')
    }
  },
  focusDragHandle(id, surface) {
    nextTick(() =>
      document
        .querySelector(
          `${surface === 'preview' ? '.topic-block' : '.structure-item'}[data-block-id="${id}"] .drag-handle`,
        )
        ?.focus({ preventScroll: true }),
    )
  },
  handleBlockDragKeys(event, id, surface) {
    if (!['ArrowUp', 'ArrowDown'].includes(event.key)) return
    event.preventDefault()
    const index = this.blocks.findIndex((b) => b.id === id)
    const direction = event.key === 'ArrowUp' ? -1 : 1
    if (index + direction < 0 || index + direction >= this.blocks.length) return
    this.moveBlock(index, direction)
    this.notify(`已移到第 ${index + direction + 1} 位。`)
    this.focusDragHandle(id, surface)
  },
  dropClass(id, surface) {
    const drag = this.dragState
    if (!drag?.active || drag.type !== 'sort' || drag.surface !== surface)
      return {}
    return {
      'drag-origin': drag.id === id,
      'drop-before': drag.targetId === id && !drag.after,
      'drop-after': drag.targetId === id && drag.after,
      'drop-horizontal': drag.targetId === id && drag.axis === 'horizontal',
    }
  },
  defaultCanvasLayout(index) {
    return {
      x: index % 2 ? 52 : 3,
      y: Math.floor(index / 2) * 270 + 20,
      w: 45,
      h: 240,
    }
  },
  roundCanvasLayout(layout) {
    const w = Number(layout.w.toFixed(1))
    return {
      x: Math.min(100 - w, Number(layout.x.toFixed(1))),
      y: Math.round(layout.y),
      w,
      h: Math.round(layout.h),
    }
  },
  canvasLayout(block, index) {
    return this.dragState?.type === 'canvas' && this.dragState.id === block.id
      ? this.dragState.layout
      : block.layout || this.defaultCanvasLayout(index)
  },
  canvasBlockStyle(block, index) {
    if (this.layoutMode !== 'canvas' || this.canvasStacked) return {}
    const layout = this.canvasLayout(block, index)
    return {
      left: layout.x + '%',
      top: layout.y + 'px',
      width: layout.w + '%',
      height: layout.h + 'px',
    }
  },
  updateNarrowCanvas() {
    this.cancelBlockDrag()
    this.narrowCanvas = innerWidth <= 760
  },
  beginCanvasTransform(event, id, action = 'move') {
    if (this.canvasStacked || this.previewMode !== 'edit' || event.button !== 0)
      return
    this.cancelBlockDrag()
    event.preventDefault()
    event.currentTarget.focus()
    this.selectedBlock = id
    const block = this.blocks.find((b) => b.id === id)
    const index = this.blocks.indexOf(block)
    const canvas = event.currentTarget.closest('.topic-content')
    if (!block || !canvas) return
    const layout = clone(this.canvasLayout(block, index))
    this.dragState = {
      type: 'canvas',
      id,
      surface: 'preview',
      action,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      x: event.clientX,
      y: event.clientY,
      scrollY: window.scrollY,
      canvasWidth: canvas.clientWidth,
      initial: clone(layout),
      layout,
      active: false,
      handle: event.currentTarget,
    }
    event.currentTarget.setPointerCapture(event.pointerId)
  },
  updateCanvasTransform() {
    const drag = this.dragState
    if (drag?.type !== 'canvas') return
    const dx = ((drag.x - drag.startX) / drag.canvasWidth) * 100,
      dy = drag.y - drag.startY + window.scrollY - drag.scrollY
    if (Math.abs(dx) + Math.abs(dy) > 2) drag.active = true
    const initial = drag.initial
    if (drag.action === 'move') {
      drag.layout = {
        ...initial,
        x: Math.max(0, Math.min(100 - initial.w, initial.x + dx)),
        y: Math.max(0, Math.min(10000, initial.y + dy)),
      }
    } else {
      drag.layout = {
        ...initial,
        w: Math.max(20, Math.min(100 - initial.x, initial.w + dx)),
        h: Math.max(120, Math.min(1600, initial.h + dy)),
      }
    }
  },
  handlePreviewMoveKeys(event, id) {
    if (this.layoutMode !== 'canvas') {
      this.handleBlockDragKeys(event, id, 'preview')
      return
    }
    if (
      this.canvasStacked ||
      !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)
    )
      return
    event.preventDefault()
    const index = this.blocks.findIndex((b) => b.id === id),
      block = this.blocks[index]
    const layout = clone(this.canvasLayout(block, index))
    const step = event.shiftKey ? 10 : 1
    if (event.key === 'ArrowLeft') layout.x = Math.max(0, layout.x - step)
    if (event.key === 'ArrowRight')
      layout.x = Math.min(100 - layout.w, layout.x + step)
    if (event.key === 'ArrowUp') layout.y = Math.max(0, layout.y - step * 10)
    if (event.key === 'ArrowDown')
      layout.y = Math.min(10000, layout.y + step * 10)
    block.layout = layout
    this.notify('已调整模块位置。')
  },
  setCanvasDimension(key, event) {
    const block = this.activeBlock
    if (!block) return
    const value = Number(event.target.value)
    if (!Number.isFinite(value)) return
    const layout = clone(this.canvasLayout(block, this.blocks.indexOf(block)))
    if (key === 'x') layout.x = Math.max(0, Math.min(100 - layout.w, value))
    if (key === 'y') layout.y = Math.max(0, Math.min(10000, value))
    if (key === 'w') layout.w = Math.max(20, Math.min(100 - layout.x, value))
    if (key === 'h') layout.h = Math.max(120, Math.min(1600, value))
    block.layout = layout
    event.target.value = layout[key]
  },
  async favoriteBlock() {
    if (!this.activeBlock || this.favoriteBusy) return
    this.favoriteBusy = true
    try {
      await api('/api/me/topic-builder/module-favorites', { method: 'POST', body: JSON.stringify({ block: clone(this.activeBlock) }) })
      this.notify('已收藏独立快照，可从“我的收藏”再次插入。')
    } catch (e) { this.storageError = e.message }
    finally { this.favoriteBusy = false }
  },
  insertBlock(source) {
    if (this.blocks.length >= 50) { this.notify('最多添加 50 个模块。'); return }
    if (source.kind === 'html') {
      if (this.blocks.filter(b => b.kind === 'html').length >= 20) { this.notify('最多添加 20 个 HTML 模块。'); return }
      try { compileHtmlBlock(source.code.source) } catch(e) { this.storageError = e.message; return }
    }
    const block = { id: uid(), kind: source.kind, title: source.title, body: source.body || '' }
    if (source.kind === 'html') block.code = clone(source.code)
    if (this.layoutMode === 'canvas') block.layout = { x: 3, y: Math.min(10000, this.canvasHeight), w: source.kind === 'html' ? 90 : 60, h: source.kind === 'html' ? Math.min(1600, source.code.frameHeight + 140) : 240 }
    this.blocks.push(block); this.selectedBlock = block.id; this.previewMode = 'edit'; this.modulePickerOpen = false; this.savedNotice = false
    this.notify('模块已添加，可独立编辑。')
  },
  addBlock() { this.insertBlock({kind:'text',title:'新内容',body:''}) },
  deleteBlock() {
    const index = this.blocks.findIndex((b) => b.id === this.selectedBlock)
    if (index < 0) return
    this.removedBlock = { index, block: clone(this.blocks[index]) }
    this.blocks.splice(index, 1)
    this.selectedBlock =
      this.blocks[Math.min(index, this.blocks.length - 1)]?.id
  },
  undoDelete() {
    if (!this.removedBlock || this.blocks.length >= 50) return
    const { index, block } = this.removedBlock
    this.blocks.splice(Math.min(index, this.blocks.length), 0, block)
    this.selectedBlock = block.id
    this.removedBlock = null
  },
  warnUnsaved(event) {
    if (this.hasUnsavedWork || this.candidate || this.codeDialogOpen) {
      event.preventDefault()
      event.returnValue = ''
    }
  },
}
