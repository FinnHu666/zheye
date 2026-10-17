<script>
import { nextTick, markRaw } from 'vue'
import { api } from '../lib/api'
import { styles, examples, blankForm } from '../lib/topic-builder/options'
import {
  clone,
  fingerprint,
  mergeSuggestions,
  CandidateRequest,
  DraftSession,
} from '../lib/topic-builder/session'
import editorMethods from '../lib/topic-builder/editor-methods'
import HtmlBlockFrame from '../components/topic-builder/HtmlBlockFrame.vue'
import HtmlCodeDialog from '../components/topic-builder/HtmlCodeDialog.vue'
import ModulePicker from '../components/topic-builder/ModulePicker.vue'
export default {
  components: { HtmlBlockFrame, HtmlCodeDialog, ModulePicker },
  data() {
    return {
      styles,
      examples,
      purposes: [
        {
          label: '避坑解决问题',
          icon: '◇',
          description: '识别问题，找到行动办法',
        },
        { label: '看番路线', icon: '▷', description: '安排作品与观看次序' },
        {
          label: '学习某项工具',
          icon: '⌘',
          description: '准备、实践与结果检查',
        },
        { label: '资源整理', icon: '▤', description: '把资料组织成可查阅目录' },
      ],
      questionnaireSteps: ['主题与人群', '页面风格', '资料与要求', '需求摘要'],
      form: blankForm(),
      generatedForm: blankForm(),
      generationBrief: blankForm(),
      chatInput: '',
      conversation: [],
      activeExample: 'rent',
      questionnaireOpen: false,
      questionStep: 0,
      formError: '',
      fileError: '',
      view: 'chat',
      blocks: [],
      selectedBlock: null,
      removedBlock: null,
      previewMode: 'edit',
      previewDevice: 'desktop',
      generationStage: 0,
      generationInput: null,
      candidate: null,
      pendingNavigation: null,
      generatedWithoutSource: false,
      currentId: null,
      savedNotice: false,
      storageError: '',
      toast: '',
      timers: [],
      focusOrigin: null,
      dragState: null,
      scrollDragFrame: 0,
      layoutMode: 'flow',
      narrowCanvas: innerWidth <= 760,
      codeDialogOpen: false,
      codeEditId: null,
      codeInitialSource: '',
      codeInitialTitle: '',
      codeInitialHeight: 360,
      briefRecovered: false,
      session: new DraftSession(),
      generator: markRaw(new CandidateRequest()),
      generationMeta: null,
      reviewNotes: [],
      reviewNotesExplicit: false,
      modulePickerOpen: false,
      favoriteBusy: false,
      capabilities: null,
      chatBusy: false,
      aiBusy: false,
      aiError: '',
      loading: false,
      chatController: null,
      routeBypass: false,
      preserveRouteDocument: false,
      loadRun: 0,
    }
  },
  computed: {
    privatePreview() {
      return Boolean(this.$route.meta.builderPreview)
    },
    canvasStacked() {
      return this.narrowCanvas || this.previewDevice === 'mobile'
    },
    canvasHeight() {
      return Math.max(
        360,
        ...this.blocks.map((b, i) => {
          const l = this.canvasLayout(b, i)
          return l.y + l.h + 28
        }),
      )
    },
    draggedBlock() {
      return this.blocks.find((b) => b.id === this.dragState?.id)
    },
    selectedStyle() {
      return this.styleFor(this.form.style)
    },
    audienceSuggestions() {
      return ['初次尝试的新手', '有一定经验的读者', '不限定人群']
    },
    activeBlock() {
      return this.blocks.find((b) => b.id === this.selectedBlock)
    },
    document() {
      return {
        schemaVersion: 1,
        form: this.generatedForm,
        confirmedForm: this.generationBrief,
        blocks: this.blocks,
        layoutMode: this.layoutMode,
        withoutSource: this.generatedWithoutSource,
        ...(this.reviewNotesExplicit ? { reviewNotes: this.reviewNotes } : {}),
        ...(this.generationMeta ? { generation: this.generationMeta } : {}),
      }
    },
    fingerprint() {
      return fingerprint(this.document)
    },
    isDirty() {
      return this.fingerprint !== this.session.savedFingerprint
    },
    hasUnsavedWork() {
      return (
        Boolean(this.generatedForm.topic && (this.isDirty || this.pendingRequirements)) ||
        Boolean(this.chatInput.trim()) ||
        (!this.generatedForm.topic &&
          fingerprint(this.form) !== fingerprint(blankForm()))
      )
    },
    pendingRequirements() {
      return fingerprint(this.form) !== fingerprint(this.generatedForm)
    },
  },
  watch: {
    async '$route.path'() {
      this.previewMode = this.privatePreview ? 'read' : 'edit'
      if (this.preserveRouteDocument) {
        this.preserveRouteDocument = false
        return
      }
      await this.loadRoute(this.$route)
    },
    layoutMode(v) {
      this.cancelBlockDrag()
      if (v === 'canvas')
        this.blocks.forEach((b, i) => {
          if (!b.layout) b.layout = this.defaultCanvasLayout(i)
        })
    },
    previewDevice() {
      this.cancelBlockDrag()
    },
    view() {
      this.cancelBlockDrag()
    },
    previewMode() {
      this.cancelBlockDrag()
    },
    questionStep() {
      nextTick(() => {
        if (this.$refs.dialog) this.$refs.dialog.scrollTop = 0
      })
    },
    questionnaireOpen(v) {
      this.cancelBlockDrag()
      document.body.style.overflow = v ? 'hidden' : ''
    },
    pendingNavigation(v) {
      document.body.style.overflow = v ? 'hidden' : ''
      if (v)
        nextTick(() =>
          document.querySelector('.confirm-dialog button')?.focus(),
        )
    },
    'generatedForm.style'(v) {
      if (this.view === 'workbench' && !this.questionnaireOpen)
        this.form.style = v
    },
    'generatedForm.topic'(v) {
      if (this.view === 'workbench' && !this.questionnaireOpen)
        this.form.topic = v
    },
    'generatedForm.density'(v) {
      if (this.view === 'workbench' && !this.questionnaireOpen)
        this.form.density = v
    },
  },
  async mounted() {
    window.addEventListener('keydown', this.handleDialogKeys)
    window.addEventListener('keydown', this.handleDragEscape)
    window.addEventListener('resize', this.updateNarrowCanvas)
    window.addEventListener('beforeunload', this.warnUnsaved)
    try {
      this.capabilities = await api('/api/me/topic-builder/capabilities')
    } catch (e) {
      this.storageError = e.message
    }
    await this.loadRoute(this.$route)
  },
  beforeUnmount() {
    this.generator.cancel()
    this.chatController?.abort()
    this.loadRun++
    this.cancelBlockDrag()
    this.timers.forEach(clearTimeout)
    window.removeEventListener('keydown', this.handleDialogKeys)
    window.removeEventListener('keydown', this.handleDragEscape)
    window.removeEventListener('resize', this.updateNarrowCanvas)
    window.removeEventListener('beforeunload', this.warnUnsaved)
    document.body.style.overflow = ''
  },
  async beforeRouteLeave() {
    return this.guardNavigation()
  },
  async beforeRouteUpdate() {
    return this.guardNavigation()
  },
  methods: {
    ...editorMethods,
    async loadRoute(route) {
      this.generator.cancel()
      this.aiBusy = false
      this.chatController?.abort()
      this.chatBusy = false
      this.candidate = null
      this.questionnaireOpen = false
      this.modulePickerOpen = false
      this.storageError = ''
      const run = ++this.loadRun
      if (!route.params.id) {
        this.form = blankForm()
        this.generatedForm = blankForm()
        this.generationBrief = blankForm()
        this.blocks = []
        this.generationMeta = null
        this.reviewNotes = []
        this.reviewNotesExplicit = false
        this.modulePickerOpen = false
        this.currentId = null
        this.session = new DraftSession()
        this.conversation = []
        this.chatInput = ''
        this.savedNotice = false
        this.selectedBlock = null
        this.generationInput = null
        this.view = 'chat'
        this.layoutMode = 'flow'
        this.session.savedFingerprint = this.fingerprint
        return
      }
      this.loading = true
      this.storageError = ''
      try {
        const stored = await api(
          '/api/me/collections/' + route.params.id + '/builder-document',
        )
        if (run !== this.loadRun) return
        if (stored.document.schemaVersion !== 1)
          throw new Error('页面文档版本不支持，请保留输入并联系管理员。')
        this.applyDocument(stored.document)
        this.session.load(stored)
        this.currentId = stored.id
        this.previewMode = route.meta.builderPreview ? 'read' : 'edit'
        this.view = 'workbench'
      } catch (e) {
        if (run === this.loadRun) {
          this.storageError = e.message
          this.view = 'chat'
        }
      } finally {
        if (run === this.loadRun) this.loading = false
      }
    },
    applyDocument(doc) {
      this.generatedForm = clone(doc.form)
      this.form = clone(doc.form)
      this.generationBrief = clone(doc.confirmedForm)
      this.blocks = clone(doc.blocks)
      this.layoutMode = doc.layoutMode
      this.generatedWithoutSource = doc.withoutSource
      this.generationMeta = clone(doc.generation || null)
      this.reviewNotes = clone(doc.reviewNotes ?? doc.generation?.warnings ?? [])
      this.reviewNotesExplicit = Object.hasOwn(doc, 'reviewNotes')
      this.selectedBlock = this.blocks[0]?.id
      this.removedBlock = null
    },
    async sendMessage() {
      const text = this.chatInput.trim()
      if (!text || this.chatBusy || this.aiBusy) return
      this.chatBusy = true
      this.storageError = ''
      this.chatController = new AbortController()
      const before = clone(this.form)
      const messages = [
        ...this.conversation.map(({ role, text }) => ({ role, text })),
        { role: 'user', text },
      ].slice(-20)
      try {
        const result = await api('/api/me/topic-builder/chat', {
          method: 'POST',
          body: JSON.stringify({ messages, form: before }),
          signal: this.chatController.signal,
        })
        this.form = mergeSuggestions(this.form, result.form)
        this.conversation.push(
          { role: 'user', text },
          { role: 'assistant', text: result.reply, action: true },
        )
        this.chatInput = ''
        this.openQuestionnaire()
      } catch (e) {
        if (e.name !== 'AbortError') {
          this.storageError = e.message
          this.notify(e.message)
        }
      } finally {
        this.chatBusy = false
        this.chatController = null
      }
    },
    async startGeneration(retry = false) {
      if (!this.validateBasics() || this.chatBusy) return
      if (!retry) this.generationInput = clone(this.form)
      this.candidate = null
      this.questionnaireOpen = false
      this.savedNotice = false
      this.view = 'generating'
      this.aiError = ''
      this.aiBusy = true
      try {
        const result = await this.generator.start(
          this.generationInput,
          (form, signal) =>
            api('/api/me/topic-builder/generate', {
              method: 'POST',
              body: JSON.stringify({ form }),
              signal,
            }),
        )
        if (!result) return
        this.candidate = clone(result.document)
        if (this.generatedForm.topic) this.view = 'generation-review'
        else this.acceptCandidate()
      } catch (e) {
        this.aiError = e.message
        this.view = 'generation-error'
      } finally {
        this.aiBusy = Boolean(this.generator.controller)
      }
    },
    cancelGeneration() {
      this.generator.cancel()
      this.aiBusy = false
      this.view = this.generatedForm.topic ? 'workbench' : 'chat'
      this.openQuestionnaire(3)
    },
    retryGeneration() {
      this.startGeneration(true)
    },
    acceptCandidate() {
      if (!this.candidate) return
      this.applyDocument(this.candidate)
      this.candidate = null
      this.view = 'workbench'
      this.previewMode = 'edit'
      this.savedNotice = false
    },
    keepOriginal() {
      this.candidate = null
      this.view = 'workbench'
      this.notify('已保留原稿，新要求仍在问卷中。')
    },
    async saveDraft(navigate = true) {
      this.storageError = ''
      try {
        if (!this.generatedForm.topic.trim()) {
          this.storageError = '请先生成工作稿再保存。'
          return false
        }
        const saved = await this.session.save(this.document, api)
        if (!saved) return false
        this.currentId = this.session.id
        this.savedNotice = true
        if (navigate !== false && !this.$route.params.id) {
          this.preserveRouteDocument = true
          this.routeBypass = true
          await this.$router.replace(
            '/me/collections/' + this.currentId + '/builder',
          )
        }
        this.notify(
          this.isDirty
            ? '本次提交已保存；后续修改仍未保存。'
            : '私人草稿已保存。',
        )
        return true
      } catch (e) {
        this.storageError = e.message
        this.notify(e.message)
        return false
      }
    },
    async guardNavigation() {
      if (this.codeDialogOpen) {
        this.notify('请先应用或取消模块代码编辑，再离开页面。')
        return false
      }
      if (this.routeBypass) {
        this.routeBypass = false
        return true
      }
      if (
        !this.hasUnsavedWork &&
        !this.candidate &&
        !this.aiBusy &&
        !this.chatBusy
      )
        return true
      this.focusOrigin = document.activeElement
      this.questionnaireOpen = false
      return new Promise((resolve) => {
        this.pendingNavigation = markRaw({ resolve })
      })
    },
    cancelNavigation() {
      const pending = this.pendingNavigation
      this.pendingNavigation = null
      pending?.resolve(false)
      nextTick(() => this.focusOrigin?.focus())
    },
    async resolveNavigation(save) {
      const pending = this.pendingNavigation
      if (!pending) return
      if (save) {
        if (!(await this.saveDraft(false))) return
        if (this.hasUnsavedWork) {
          this.storageError = this.pendingRequirements
            ? '问卷调整尚未生成，请继续编辑并确认需求，或明确放弃调整。'
            : '保存期间又有修改，请再次保存，或继续编辑。'
          return
        }
      }
      this.pendingNavigation = null
      this.generator.cancel()
      this.chatController?.abort()
      pending.resolve(true)
    },
    async goChat() {
      if (this.$route.path === '/me/collections/ai/new') {
        if (await this.guardNavigation()) await this.loadRoute(this.$route)
      } else await this.$router.push('/me/collections/ai/new')
    },
    handleResizeKeys(event, id) {
      if (
        !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(
          event.key,
        ) ||
        this.canvasStacked
      )
        return
      event.preventDefault()
      const block = this.blocks.find((b) => b.id === id)
      const l = clone(this.canvasLayout(block, this.blocks.indexOf(block))),
        step = event.shiftKey ? 10 : 1
      if (event.key === 'ArrowLeft') l.w = Math.max(20, l.w - step)
      if (event.key === 'ArrowRight') l.w = Math.min(100 - l.x, l.w + step)
      if (event.key === 'ArrowUp') l.h = Math.max(120, l.h - step * 10)
      if (event.key === 'ArrowDown') l.h = Math.min(1600, l.h + step * 10)
      block.layout = l
    },
  },
}
</script>
<template>
  <div
    class="topic-builder"
    :class="{ 'is-block-dragging': dragState?.active }"
  >
    <header class="topbar">
      <strong>AI 专题创作</strong
      ><RouterLink to="/me/collections">我的专题 →</RouterLink>
    </header>
    <p v-if="loading" class="storage-error" role="status">正在读取工作稿…</p>
    <div v-if="!loading" class="app-layout">
      <aside class="sidebar">
        <button class="new-topic" @click="goChat">＋ 开始一个专题</button>
        <RouterLink class="text-button" to="/me/collections"
          >查看我的专题 →</RouterLink
        >
        <div class="sidebar-bottom">
          <span class="tiny-label">让经验照亮别人</span>
          <p>把你走过的路，<br />写成他人少走的弯路。</p>
        </div>
      </aside>
      <main class="main-area">
        <p v-if="storageError" class="storage-error" role="alert">{{ storageError }}</p>
        <nav class="creation-progress" aria-label="专题创作流程">
          <span
            v-for="(step, index) in [
              '沟通想法',
              '问卷确认',
              '生成初稿',
              '编辑预览',
              '私人草稿',
            ]"
            :key="step"
            :class="{
              active: questionnaireOpen
                ? index === 1
                : view === 'chat'
                  ? index === 0
                  : [
                        'generating',
                        'generation-error',
                        'generation-review',
                      ].includes(view)
                    ? index === 2
                    : index === (isDirty ? 3 : 4),
            }"
            ><b>{{ index + 1 }}</b
            >{{ step }}</span
          >
        </nav>
        <section v-if="view === 'chat'" class="chat-view">
          <div class="intro">
            <span class="eyebrow"> <i> </i> 和 AI 一起整理你的经验</span>
            <h1>让你的经验，<br /><em>成为他人的指引。</em></h1>
            <p>
              说说主题、给谁看，以及你想帮他们完成什么。<br />我们会一起把想法变成清晰的页面。
            </p>
          </div>
          <div
            v-if="conversation.length"
            class="conversation"
            aria-live="polite"
          >
            <div
              v-for="(message, index) in conversation"
              :key="index"
              class="message"
              :class="message.role"
            >
              <span class="message-avatar">{{
                message.role === 'user' ? '我' : 'f'
              }}</span>
              <div>
                <p>{{ message.text }}</p>
                <div v-if="message.action" class="creation-invitation">
                  <span class="tiny-label">开始创建</span>
                  <strong>{{ form.topic || '确认你的专题需求' }}</strong>
                  <p>核对已预填的信息，补充尚未确定的答案。</p>
                  <button class="primary" @click="openQuestionnaire">
                    打开创建问卷 <span>↗</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
          <form class="composer" @submit.prevent="sendMessage">
            <label class="sr-only" for="chat-input"
              >告诉 AI 你想创建什么专题</label
            >
            <textarea
              id="chat-input"
              v-model="chatInput"
              rows="3"
              placeholder="描述专题的主题、面向人群和希望实现的目标……"
              maxlength="3000"
            ></textarea>
            <div class="composer-footer">
              <span>先聊想法，接着确认创建问卷</span>
              <button
                class="primary"
                :disabled="!chatInput.trim() || chatBusy || aiBusy"
              >
                {{ chatBusy ? '沟通中…' : '开始沟通' }} <span>↑</span>
              </button>
            </div>
          </form>
          <p v-if="capabilities && !capabilities.aiAvailable" class="ai-config-hint">添加自己的 Key 后即可与 AI 沟通。<RouterLink to="/me/ai-settings">前往 AI 配置 →</RouterLink></p>
          <p v-if="chatInput.trim()" class="secondary-text">发送后，本次输入会交给 DeepSeek 整理。</p>
          <button class="text-button" @click="openQuestionnaire()">
            直接填写创建问卷 →
          </button>
        </section>
        <section
          v-else-if="view === 'generating'"
          class="generation-view"
          aria-live="polite"
        >
          <span class="generation-symbol">✦</span>
          <span class="tiny-label">正在组织专题</span>
          <h1>把你的想法，<br />变成一页清晰的内容。</h1>
          <p>{{ form.topic }}</p>
          <div class="generation-steps">
            <div :class="{ done: generationStage >= 1 }">
              <span>{{ generationStage >= 1 ? '✓' : '1' }}</span
              >理解已确认的人群与用途
            </div>
            <div :class="{ done: generationStage >= 2 }">
              <span>{{ generationStage >= 2 ? '✓' : '2' }}</span
              >整理资料与内容顺序
            </div>
            <div :class="{ done: generationStage >= 3 }">
              <span>{{ generationStage >= 3 ? '✓' : '3' }}</span
              >应用「{{ selectedStyle.name }}」布局
            </div>
          </div>
          <p class="secondary-text">
            DeepSeek 正在处理，原始资料与已有编辑仍然保留。
          </p>
          <button class="secondary" @click="cancelGeneration">
            取消，返回需求摘要
          </button>
        </section>
        <section v-else-if="view === 'generation-error'" class="error-view">
          <span class="error-icon">!</span>
          <h1>这次生成没有完成</h1>
          <RouterLink to="/me/ai-settings">检查个人 AI 配置 →</RouterLink>
          <p class="field-error" role="alert">{{ aiError }}</p>
          <p>
            你的问卷、原始资料和已有编辑都还在。<br />可以重试，也可以返回修改需求。
          </p>
          <div class="button-row">
            <button class="primary" @click="retryGeneration">重新生成</button>
            <button class="secondary" @click="openQuestionnaire(3)">
              返回需求摘要
            </button>
            <button
              v-if="generatedForm.topic"
              class="text-button"
              @click="view = 'workbench'"
            >
              回到已有预览
            </button>
          </div>
        </section>
        <section
          v-else-if="view === 'generation-review' && candidate"
          class="regeneration-review"
        >
          <header class="workbench-header">
            <div>
              <span class="tiny-label">新初稿 · 尚未替换原稿</span>
              <h1>先看看这次的结果</h1>
              <p class="secondary-text">
                {{ candidate.blocks.length }} 个区块 ·
                {{ candidate.form.audience }} ·
                {{ styleFor(candidate.form.style).name }}
              </p>
            </div>
            <div class="button-row">
              <button class="secondary" @click="keepOriginal">保留原稿</button
              ><button class="primary" @click="acceptCandidate">
                采用新初稿
              </button>
            </div>
          </header>
          <div class="content-warning">
            采用后将替换当前内容区块、HTML
            模块与布局，进入可编辑预览。已保存的版本在再次保存前保持原样。
          </div>
          <details class="requirements-recap">
            <summary>核对本次确认的创建要求</summary>
            <dl class="summary-list">
              <div>
                <dt>主题与用途</dt>
                <dd>
                  {{ candidate.form.topic }} · {{ candidate.form.purpose }}
                </dd>
              </div>
              <div>
                <dt>目标读者</dt>
                <dd>{{ candidate.form.audience }}</dd>
              </div>
              <div>
                <dt>预期成果</dt>
                <dd>{{ candidate.form.goal || '未填写' }}</dd>
              </div>
              <div>
                <dt>呈现偏好</dt>
                <dd>
                  {{ styleFor(candidate.form.style).description }}
                  {{ candidate.form.styleNote
                  }}<small>当前使用预设样式，请核对呈现效果。</small>
                </dd>
              </div>
              <div>
                <dt>内容要求与背景</dt>
                <dd>
                  {{ candidate.form.constraints || '未填写内容要求' }} /
                  {{ candidate.form.context || '未填写背景' }}
                </dd>
              </div>
            </dl>
          </details>
          <article
            class="topic-page"
            :class="[
              candidate.form.style,
              { compact: candidate.form.density === 'compact' },
            ]"
          >
            <header class="topic-hero">
              <span class="topic-purpose">{{ candidate.form.purpose }}</span>
              <h2>{{ candidate.form.topic }}</h2>
              <p>{{ candidate.form.goal || '预期成果待补充' }}</p>
              <span class="topic-audience"
                >适合 {{ candidate.form.audience }}</span
              >
            </header>
            <div class="topic-content">
              <section
                v-for="(block, index) in candidate.blocks"
                :key="block.id"
                class="topic-block"
                :class="block.kind"
              >
                <span class="block-number">{{
                  String(index + 1).padStart(2, '0')
                }}</span>
                <div>
                  <span class="block-type">{{
                    {
                      text: '内容说明',
                      steps: '行动步骤',
                      question: '问题解答',
                    }[block.kind]
                  }}</span>
                  <h3>{{ block.title }}</h3>
                  <ol v-if="block.kind === 'steps'" class="content-steps">
                    <li v-for="(line, i) in lines(block.body)" :key="i">
                      {{ line }}
                    </li>
                  </ol>
                  <p v-else class="block-body">{{ block.body }}</p>
                </div>
              </section>
            </div>
            <footer class="topic-footer">AI 整理 · 内容待作者核验</footer>
          </article>
          <details class="original-source">
            <summary>核对本次原始资料</summary>
            <pre>{{
              candidate.form.source || '未提供资料，正文为待补充结构。'
            }}</pre>
          </details>
          <div class="button-row review-decision">
            <button class="secondary" @click="keepOriginal">保留原稿</button
            ><button class="primary" @click="acceptCandidate">
              采用新初稿
            </button>
          </div>
        </section>
        <section v-else class="workbench">
          <header class="workbench-header">
            <div>
              <span class="tiny-label">你的专题工作稿</span>
              <h1>{{ generatedForm.topic }}</h1>
              <div class="meta-tags">
                <span>{{ generatedForm.audience }}</span>
                <span>{{ generatedForm.purpose }}</span>
                <span>{{ styleFor(generatedForm.style).name }}</span>
              </div>
            </div>
            <div v-if="!privatePreview" class="workbench-actions">
              <span class="save-state" role="status">{{
                isDirty ? '有未保存的修改' : pendingRequirements ? '问卷有未应用的调整' : '已保存到服务器'
              }}</span>
              <button class="secondary" @click="openQuestionnaire">
                修改创建需求
              </button>
              <button
                class="primary"
                :disabled="session.busy"
                @click="saveDraft"
              >
                {{ session.busy ? '保存中…' : '保存私人草稿' }}
              </button>
            </div>
          </header>
          <div
            v-if="savedNotice && !isDirty"
            class="saved-notice"
            role="status"
          >
            <span>✓</span>
            <div>
              <strong>私人草稿已保存</strong>
              <p>可以从“我的专题”再次打开，仅本人可访问。</p>
            </div>
            <button @click="savedNotice = false" aria-label="关闭保存提示">
              ×
            </button>
          </div>
          <RouterLink
            v-if="privatePreview"
            class="text-button"
            :to="`/me/collections/${currentId}/builder`"
            >返回编辑 →</RouterLink
          >
          <section v-if="!privatePreview && previewMode === 'edit'" class="review-notes-editor" aria-label="核验提示编辑">
            <div class="review-notes-heading"><strong>核验提示</strong><span>可修改并随草稿保存</span><button type="button" class="text-button" :disabled="reviewNotes.length >= 20" @click="reviewNotesExplicit = true; reviewNotes.push('')">＋ 添加提示</button></div>
            <div v-for="(note,index) in reviewNotes" :key="index" class="review-note-row">
              <textarea v-model="reviewNotes[index]" :aria-label="`核验提示 ${index+1}`" maxlength="300" rows="2" @input="reviewNotesExplicit = true"></textarea>
              <button type="button" class="text-button" :aria-label="`删除核验提示 ${index+1}`" @click="reviewNotesExplicit = true; reviewNotes.splice(index,1)">删除</button>
            </div>
            <details v-if="generationMeta?.warnings?.length" class="raw-review-notes"><summary>查看模型原始提示</summary><p v-for="(note,index) in generationMeta.warnings" :key="index">{{ note }}</p></details>
          </section>
          <template v-else><p v-for="(note,index) in reviewNotes.filter(n => n.trim())" :key="index" class="content-warning">{{ note }}</p></template>
          <details v-if="!privatePreview" class="requirements-recap">
            <summary>
              查看生成时确认的创建要求
              <span v-if="pendingRequirements" class="pending-requirements"
                >问卷有调整，当前仍为上次结果</span
              >
            </summary>
            <p v-if="briefRecovered" class="secondary-text">
              旧演示草稿未单独记录生成摘要，此处按保存时信息回填。
            </p>
            <dl class="summary-list">
              <div>
                <dt>主题与用途</dt>
                <dd>
                  {{ generationBrief.topic }} · {{ generationBrief.purpose }}
                </dd>
              </div>
              <div>
                <dt>目标读者</dt>
                <dd>{{ generationBrief.audience }}</dd>
              </div>
              <div>
                <dt>预期成果</dt>
                <dd>{{ generationBrief.goal || '未填写' }}</dd>
              </div>
              <div>
                <dt>呈现偏好</dt>
                <dd>
                  {{ styleFor(generationBrief.style).name }} ·
                  {{
                    generationBrief.density === 'compact'
                      ? '紧凑密度'
                      : '适中留白'
                  }}<small>{{
                    styleFor(generationBrief.style).description
                  }}</small>
                </dd>
              </div>
              <div v-if="generationBrief.styleNote">
                <dt>风格补充</dt>
                <dd>
                  {{ generationBrief.styleNote
                  }}<small>风格补充供作者参考，当前使用预设样式。</small>
                </dd>
              </div>
              <div>
                <dt>内容要求</dt>
                <dd>
                  {{ generationBrief.constraints || '未填写'
                  }}<small>请核对 AI 整理后是否满足。</small>
                </dd>
              </div>
              <div>
                <dt>适用背景</dt>
                <dd>{{ generationBrief.context || '未填写' }}</dd>
              </div>
            </dl>
          </details>
          <div
            class="editor-grid"
            :class="{ 'reading-mode': previewMode === 'read' }"
          >
            <aside v-if="previewMode === 'edit'" class="structure-panel">
              <div class="panel-title">
                <strong>内容区块</strong>
                <span>{{ blocks.length }}</span>
              </div>
              <p class="secondary-text">
                {{
                  layoutMode === 'canvas'
                    ? '画布调整位置；这里调整手机阅读顺序。'
                    : '拖动 ⠿ 调整顺序，或用上下箭头移动。'
                }}
              </p>
              <div
                v-for="(block, index) in blocks"
                :key="block.id"
                :data-block-id="block.id"
                class="structure-item"
                :class="[
                  { selected: selectedBlock === block.id },
                  dropClass(block.id, 'structure'),
                ]"
              >
                <button class="structure-select" @click="selectBlock(block.id)">
                  <small>{{ String(index + 1).padStart(2, '0') }}</small>
                  <span>{{ block.title || '未命名内容' }}</span>
                </button>
                <div class="order-controls">
                  <button
                    type="button"
                    class="drag-handle"
                    :aria-label="'拖动排序：' + (block.title || '未命名内容')"
                    title="拖动排序；键盘上下键移动"
                    @click.stop
                    @pointerdown="beginBlockDrag($event, block.id, 'structure')"
                    @pointermove="updateBlockDrag"
                    @pointerup="finishBlockDrag"
                    @pointercancel="cancelBlockDrag"
                    @lostpointercapture="cancelBlockDrag"
                    @keydown="
                      handleBlockDragKeys($event, block.id, 'structure')
                    "
                  >
                    ⠿
                  </button>
                  <button
                    :disabled="index === 0"
                    @click="moveBlock(index, -1)"
                    :aria-label="`上移${block.title}`"
                  >
                    ↑
                  </button>
                  <button
                    :disabled="index === blocks.length - 1"
                    @click="moveBlock(index, 1)"
                    :aria-label="`下移${block.title}`"
                  >
                    ↓
                  </button>
                </div>
              </div>
              <button class="add-block" @click="modulePickerOpen = true">
                ＋ 添加内容区块
              </button>
              <button class="add-block add-code-block" @click="openHtmlCode()">
                〈/〉 导入 HTML 模块
              </button>
              <div v-if="activeBlock" class="block-settings">
                <span class="tiny-label">编辑所选区块</span>
                <button type="button" class="secondary" :disabled="favoriteBusy" @click="favoriteBlock">{{ favoriteBusy ? '收藏中…' : '收藏此模块' }}</button>
                <label
                  >区块标题<input v-model="activeBlock.title" maxlength="100" />
                </label>
                <label
                  >内容形式<select
                    v-model="activeBlock.kind"
                    :disabled="activeBlock.kind === 'html'"
                  >
                    <option v-if="activeBlock.kind === 'html'" value="html">
                      自定义 HTML + CSS
                    </option>
                    <option value="text">文字说明</option>
                    <option value="steps">步骤</option>
                    <option value="question">问题解答</option>
                  </select>
                </label>
                <label v-if="activeBlock.kind !== 'html'"
                  >正文<textarea
                    v-model="activeBlock.body"
                    rows="6"
                    maxlength="4000"
                  ></textarea>
                </label>
                <div v-else class="code-module-settings">
                  <p>模块内容通过源码编辑，画布单独保存位置与尺寸。</p>
                  <button class="secondary" @click="openHtmlCode(activeBlock)">
                    编辑 HTML + CSS
                  </button>
                  <label
                    >流式与手机显示高度（px）<input
                      type="number"
                      min="120"
                      max="1200"
                      :value="activeBlock.code?.frameHeight || 360"
                      @blur="setHtmlFrameHeight"
                    />
                  </label>
                  <small
                    >超出高度可在模块内滚动。自由画布高度使用下方尺寸设置。</small
                  >
                </div>
                <div v-if="layoutMode === 'canvas'" class="canvas-settings">
                  <span class="tiny-label">桌面位置与尺寸 · 手机自动堆叠</span>
                  <label
                    >横向位置（%）<input
                      type="number"
                      min="0"
                      max="80"
                      :value="
                        canvasLayout(activeBlock, blocks.indexOf(activeBlock)).x
                      "
                      @blur="setCanvasDimension('x', $event)"
                    />
                  </label>
                  <label
                    >纵向位置（px）<input
                      type="number"
                      min="0"
                      max="10000"
                      :value="
                        canvasLayout(activeBlock, blocks.indexOf(activeBlock)).y
                      "
                      @blur="setCanvasDimension('y', $event)"
                    />
                  </label>
                  <label
                    >宽度（%）<input
                      type="number"
                      min="20"
                      max="100"
                      :value="
                        canvasLayout(activeBlock, blocks.indexOf(activeBlock)).w
                      "
                      @blur="setCanvasDimension('w', $event)"
                    />
                  </label>
                  <label
                    >高度（px）<input
                      type="number"
                      min="120"
                      max="1600"
                      :value="
                        canvasLayout(activeBlock, blocks.indexOf(activeBlock)).h
                      "
                      @blur="setCanvasDimension('h', $event)"
                    />
                  </label>
                </div>
                <button class="delete-block" @click="deleteBlock">
                  删除此区块
                </button>
              </div>
              <button
                v-if="removedBlock"
                class="text-button"
                @click="undoDelete"
              >
                撤销删除
              </button>
            </aside>
            <div class="preview-area">
              <div v-if="!privatePreview" class="preview-toolbar">
                <div class="segmented" aria-label="编辑或预览">
                  <button
                    :class="{ active: previewMode === 'edit' }"
                    @click="previewMode = 'edit'"
                  >
                    编辑
                  </button>
                  <button
                    :class="{ active: previewMode === 'read' }"
                    @click="previewMode = 'read'"
                  >
                    阅读预览
                  </button>
                </div>
                <div class="segmented" aria-label="预览宽度">
                  <button
                    :class="{ active: previewDevice === 'desktop' }"
                    @click="previewDevice = 'desktop'"
                  >
                    桌面
                  </button>
                  <button
                    :class="{ active: previewDevice === 'mobile' }"
                    @click="previewDevice = 'mobile'"
                  >
                    手机
                  </button>
                </div>
                <div
                  v-if="previewMode === 'edit'"
                  class="segmented"
                  aria-label="页面布局"
                >
                  <button
                    :class="{ active: layoutMode === 'flow' }"
                    @click="layoutMode = 'flow'"
                  >
                    流式布局
                  </button>
                  <button
                    :class="{ active: layoutMode === 'canvas' }"
                    @click="layoutMode = 'canvas'"
                  >
                    自由画布
                  </button>
                </div>
                <label v-if="previewMode === 'edit'" class="style-select"
                  >风格<select v-model="generatedForm.style">
                    <option v-for="style in styles" :value="style.id">
                      {{ style.name }}
                    </option>
                  </select>
                </label>
              </div>
              <p v-if="layoutMode === 'canvas'" class="canvas-help">
                {{
                  canvasStacked
                    ? '手机按内容顺序自动堆叠；桌面位置和尺寸已保留。'
                    : previewMode === 'read'
                      ? '正在查看桌面构图。返回编辑可调整模块位置与尺寸。'
                      : '拖动模块右上角 ⠿ 移动，拖动右下角 ↘ 调整大小。Esc 取消；方向键微调，左侧可输入尺寸。'
                }}
              </p>
              <article
                class="topic-page"
                :class="[
                  generatedForm.style,
                  {
                    mobile: previewDevice === 'mobile',
                    compact: generatedForm.density === 'compact',
                  },
                ]"
              >
                <header class="topic-hero">
                  <span class="topic-purpose">{{ generatedForm.purpose }}</span>
                  <h2 v-if="previewMode === 'read'">
                    {{ generatedForm.topic }}
                  </h2>
                  <label v-else class="sr-only" for="edit-topic-name"
                    >专题名称</label
                  >
                  <input
                    v-if="previewMode === 'edit'"
                    id="edit-topic-name"
                    class="editable-title"
                    v-model="generatedForm.topic"
                    maxlength="80"
                  />
                  <p>
                    {{
                      generatedForm.goal || '在这里写下你希望读者完成的事情。'
                    }}
                  </p>
                  <span class="topic-audience"
                    >适合 {{ generatedForm.audience }}</span
                  >
                </header>
                <nav class="topic-outline" aria-label="专题目录">
                  <a
                    v-for="(block, index) in blocks"
                    :href="`#block-${block.id}`"
                    @click.prevent="scrollToBlock(block.id)"
                    >{{ String(index + 1).padStart(2, '0') }}
                    {{ block.title || '未命名内容' }}</a
                  >
                </nav>
                <div
                  class="topic-content"
                  :class="{
                    'free-canvas': layoutMode === 'canvas' && !canvasStacked,
                  }"
                  :style="
                    layoutMode === 'canvas' && !canvasStacked
                      ? { height: canvasHeight + 'px' }
                      : {}
                  "
                >
                  <section
                    v-for="(block, index) in blocks"
                    :id="`block-${block.id}`"
                    :key="block.id"
                    class="topic-block"
                    :style="canvasBlockStyle(block, index)"
                    :data-block-id="block.id"
                    :class="[
                      block.kind,
                      dropClass(block.id, 'preview'),
                      {
                        selected:
                          previewMode === 'edit' && selectedBlock === block.id,
                      },
                    ]"
                    @click="previewMode === 'edit' && selectBlock(block.id)"
                  >
                    <button
                      v-if="
                        previewMode === 'edit' &&
                        (layoutMode !== 'canvas' || !canvasStacked)
                      "
                      type="button"
                      class="drag-handle preview-drag-handle"
                      :aria-label="
                        '移动预览区块：' + (block.title || '未命名内容')
                      "
                      :title="
                        layoutMode === 'canvas'
                          ? '拖动自由移动；方向键微调'
                          : '拖动排序；键盘上下键移动'
                      "
                      @click.stop
                      @pointerdown="
                        layoutMode === 'canvas'
                          ? beginCanvasTransform($event, block.id)
                          : beginBlockDrag($event, block.id, 'preview')
                      "
                      @pointermove="updateBlockDrag"
                      @pointerup="finishBlockDrag"
                      @pointercancel="cancelBlockDrag"
                      @lostpointercapture="cancelBlockDrag"
                      @keydown="handlePreviewMoveKeys($event, block.id)"
                    >
                      ⠿
                    </button>
                    <span class="block-number">{{
                      String(index + 1).padStart(2, '0')
                    }}</span>
                    <div
                      :class="{ 'code-block-content': block.kind === 'html' }"
                    >
                      <span class="block-type">{{
                        {
                          text: '内容说明',
                          steps: '行动步骤',
                          question: '问题解答',
                          html: '自定义 HTML + CSS',
                        }[block.kind]
                      }}</span>
                      <h3>{{ block.title || '未命名内容' }}</h3>
                      <html-block-frame
                        v-if="block.kind === 'html'"
                        :code="block.code"
                        :title="block.title"
                      >
                      </html-block-frame>
                      <p v-else-if="block.kind === 'text'" class="block-body">
                        {{ block.body || '待补充内容' }}
                      </p>
                      <ol
                        v-else-if="block.kind === 'steps'"
                        class="content-steps"
                      >
                        <li
                          v-for="(line, lineIndex) in lines(block.body)"
                          :key="lineIndex"
                        >
                          {{ line }}
                        </li>
                      </ol>
                      <p v-else class="question-body">
                        {{ block.body || '待补充回答' }}
                      </p>
                    </div>
                    <button
                      v-if="
                        layoutMode === 'canvas' &&
                        !canvasStacked &&
                        previewMode === 'edit'
                      "
                      type="button"
                      class="canvas-resize-handle"
                      :aria-label="'调整大小：' + (block.title || '未命名内容')"
                      title="拖动调整大小；方向键微调"
                      @keydown="handleResizeKeys($event, block.id)"
                      @click.stop
                      @pointerdown="
                        beginCanvasTransform($event, block.id, 'resize')
                      "
                      @pointermove="updateBlockDrag"
                      @pointerup="finishBlockDrag"
                      @pointercancel="cancelBlockDrag"
                      @lostpointercapture="cancelBlockDrag"
                    >
                      ↘
                    </button>
                    <span
                      v-if="previewMode === 'edit' && layoutMode !== 'canvas'"
                      class="edit-hint"
                      >编辑 ↗</span
                    >
                  </section>
                  <div v-if="!blocks.length" class="empty-content">
                    还没有内容区块，添加一个开始编辑。
                  </div>
                </div>
                <footer class="topic-footer">
                  私人工作稿 · 内容待作者核验
                </footer>
              </article>
              <div v-if="!privatePreview" class="original-source">
                <details>
                  <summary>核对原始资料</summary>
                  <pre>{{ generatedForm.source || '尚未提供原始资料。' }}</pre>
                </details>
              </div>
            </div>
          </div>
        </section>
      </main>
    </div>

    <div
      v-if="questionnaireOpen"
      class="modal-backdrop"
      @click.self="closeQuestionnaire"
      @keydown.esc="closeQuestionnaire"
    >
      <section
        class="questionnaire"
        role="dialog"
        aria-modal="true"
        aria-labelledby="questionnaire-title"
        ref="dialog"
      >
        <header class="dialog-header">
          <div>
            <span class="tiny-label">创建专题</span>
            <h2 id="questionnaire-title">先确认，我们要做什么。</h2>
            <p>从对话中预填的信息都可以修改。</p>
          </div>
          <button
            class="close-button"
            @click="closeQuestionnaire"
            aria-label="关闭创建问卷"
          >
            ×
          </button>
        </header>
        <div class="dialog-layout">
          <nav class="questionnaire-steps" aria-label="创建问卷步骤">
            <button
              v-for="(label, index) in questionnaireSteps"
              :key="label"
              :class="{
                active: questionStep === index,
                completed: questionStep > index,
              }"
              @click="jumpStep(index)"
            >
              <span>{{ questionStep > index ? '✓' : index + 1 }}</span>
              <strong>{{ label }}</strong>
            </button>
            <p>选择决定起点，<br />生成后还能继续修改。</p>
          </nav>
          <form class="questionnaire-form" @submit.prevent="nextStep">
            <div v-if="questionStep === 0" class="form-stage">
              <span class="step-overline">01 / 创作意图</span>
              <h3>主题、读者和用途</h3>
              <label
                >专题主题 <span class="required-label">需要确认</span>
                <input
                  id="topic-field"
                  v-model="form.topic"
                  placeholder="例如：大学生实习短租指南"
                  maxlength="80"
                />
              </label>
              <label
                >主要给谁看？ <span class="required-label">需要确认</span>
                <input
                  id="audience-field"
                  v-model="form.audience"
                  placeholder="说说读者的身份、经验或背景"
                  maxlength="160"
                />
              </label>
              <div class="suggestions">
                <button
                  type="button"
                  v-for="audience in audienceSuggestions"
                  @click="form.audience = audience"
                >
                  {{ audience }}
                </button>
              </div>
              <fieldset class="purpose-field">
                <legend>
                  主要用途 <span class="required-label">需要确认</span>
                </legend>
                <div class="purpose-grid">
                  <label
                    v-for="purpose in purposes"
                    :key="purpose.label"
                    class="choice-card"
                    :class="{ selected: form.purpose === purpose.label }"
                  >
                    <input
                      type="radio"
                      v-model="form.purpose"
                      :value="purpose.label"
                      name="purpose"
                    />
                    <span class="purpose-symbol">{{ purpose.icon }}</span>
                    <strong>{{ purpose.label }}</strong>
                    <small>{{ purpose.description }}</small>
                  </label>
                </div>
                <label class="custom-purpose"
                  >也可以写自己的用途<input
                    id="purpose-field"
                    v-model="form.purpose"
                    maxlength="80"
                    placeholder="如：准备一场旅行"
                  />
                </label>
              </fieldset>
              <label
                >希望读者看完能做什么？ <span class="optional-label">可选</span>
                <textarea
                  v-model="form.goal"
                  rows="2"
                  maxlength="300"
                  placeholder="用一句话描述成果"
                ></textarea>
              </label>
            </div>
            <div v-else-if="questionStep === 1" class="form-stage">
              <span class="step-overline">02 / 呈现偏好</span>
              <h3>选择你希望的页面风格</h3>
              <p class="stage-description">
                用途决定内容方向，风格决定怎样呈现。可以自由组合。
              </p>
              <div class="style-grid">
                <label
                  v-for="style in styles"
                  :key="style.id"
                  class="style-card"
                  :class="{ selected: form.style === style.id }"
                >
                  <input
                    type="radio"
                    v-model="form.style"
                    :value="style.id"
                    name="style"
                  />
                  <div class="style-sample" :class="style.id">
                    <i class="sample-title"> </i>
                    <div class="sample-items">
                      <i> </i>
                      <i> </i>
                      <i> </i>
                    </div>
                  </div>
                  <span class="style-card-title">
                    <strong>{{ style.name }}</strong>
                    <span v-if="form.style === style.id">✓</span>
                  </span>
                  <p>{{ style.description }}</p>
                  <small>{{ style.suitable }}</small>
                </label>
              </div>
              <div class="form-two-col">
                <label
                  >信息密度<select v-model="form.density">
                    <option value="comfortable">适中留白，轻松阅读</option>
                    <option value="compact">紧凑一些，便于查阅</option>
                  </select>
                </label>
                <label
                  >补充风格偏好 <span class="optional-label">可选</span>
                  <input
                    v-model="form.styleNote"
                    maxlength="200"
                    placeholder="例如：偏暖色、少装饰"
                  />
                </label>
              </div>
              <p class="field-note">
                页面支持以上四种布局与两种密度。文字偏好记录在需求摘要，未实现的效果不会冒充已生效。
              </p>
            </div>
            <div v-else-if="questionStep === 2" class="form-stage">
              <span class="step-overline">03 / 你的资料</span>
              <h3>放入内容，补充重要要求</h3>
              <label
                >已有资料 <span class="optional-label">可稍后补充</span>
                <textarea
                  v-model="form.source"
                  rows="8"
                  maxlength="16000"
                  placeholder="粘贴笔记、文章或聊天整理内容…"
                ></textarea>
              </label>
              <div class="source-actions">
                <label class="file-button"
                  >选择 TXT / Markdown<input
                    type="file"
                    accept=".txt,.md,.markdown"
                    @change="readSourceFile"
                  />
                </label>
                <button
                  v-if="activeExample"
                  type="button"
                  class="text-button"
                  @click="fillExampleSource"
                >
                  使用当前案例资料
                </button>
                <span class="secondary-text">{{ form.source.length }} 字</span>
              </div>
              <p v-if="fileError" class="field-error" role="alert">
                {{ fileError }}
              </p>
              <p class="field-note">
                支持 UTF-8 文字文件，最多 16,000
                字。没有资料时只生成待补充结构。
              </p>
              <label
                >必须包含或希望避免的内容
                <span class="optional-label">可选</span>
                <textarea
                  v-model="form.constraints"
                  rows="2"
                  maxlength="500"
                  placeholder="例如：保留原始顺序；不需要检查清单"
                ></textarea>
              </label>
              <label
                >适用背景 <span class="optional-label">可选</span>
                <input
                  v-model="form.context"
                  maxlength="200"
                  placeholder="例如：地区、时间、工具版本、已有基础"
                />
              </label>
            </div>
            <div v-else class="form-stage summary-stage">
              <span class="step-overline">确认创作需求</span>
              <h3>这就是我们接下来要做的专题。</h3>
              <div class="summary-topic">
                <span>{{ form.purpose }}</span>
                <h4>{{ form.topic }}</h4>
                <p>{{ form.goal || '预期成果暂未填写，生成后可以补充。' }}</p>
              </div>
              <dl class="summary-list">
                <div>
                  <dt>目标读者</dt>
                  <dd>{{ form.audience }}</dd>
                </div>
                <div>
                  <dt>页面风格</dt>
                  <dd>
                    {{ selectedStyle.name }} ·
                    {{ form.density === 'compact' ? '紧凑密度' : '适中留白'
                    }}<small>{{ selectedStyle.description }}</small>
                  </dd>
                </div>
                <div v-if="form.styleNote">
                  <dt>风格补充</dt>
                  <dd>
                    {{ form.styleNote
                    }}<small>当前使用预设样式，生成后可切换。</small>
                  </dd>
                </div>
                <div>
                  <dt>原始资料</dt>
                  <dd>
                    {{
                      form.source.trim()
                        ? `${form.source.length} 字，生成后可核对原文`
                        : '尚未提供，只生成待补充结构'
                    }}
                  </dd>
                </div>
                <div v-if="form.constraints">
                  <dt>内容要求</dt>
                  <dd>{{ form.constraints }}</dd>
                </div>
                <div v-if="form.context">
                  <dt>适用背景</dt>
                  <dd>{{ form.context }}</dd>
                </div>
              </dl>
              <details v-if="form.source" class="original-source">
                <summary>查看完整原始资料（本次将发送给 DeepSeek）</summary>
                <pre>{{ form.source }}</pre>
              </details>
              <div class="summary-assurance">
                生成后先预览和修改，最后保存私人草稿。
              </div>
            </div>
            <p v-if="formError" class="field-error" role="alert">
              {{ formError }}
            </p>
            <footer class="dialog-footer">
              <button
                type="button"
                class="text-button"
                @click="
                  questionStep === 0 ? closeQuestionnaire() : questionStep--
                "
              >
                {{ questionStep === 0 ? '稍后继续' : '← 上一步' }}
              </button>
              <span>{{
                questionStep === 3 ? '答案确认后才生成' : '已填答案会保留'
              }}</span>
              <button class="primary">
                {{
                  questionStep === 3
                    ? '确认并生成页面 →'
                    : questionStep === 2
                      ? '查看需求摘要 →'
                      : '下一步 →'
                }}
              </button>
            </footer>
          </form>
        </div>
      </section>
    </div>
    <div
      v-if="pendingNavigation"
      class="modal-backdrop confirm-backdrop"
      @keydown.esc="cancelNavigation"
    >
      <section
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="leave-title"
      >
        <h2 id="leave-title">先留住这次的创作？</h2>
        <p>
          {{
            hasUnsavedWork
              ? '当前工作稿有未保存的修改。保存后可以继续；放弃只会丢弃本次未保存的改动。'
              : '新生成的结果尚未采用。离开后将丢弃候选结果，原稿保持原样。'
          }}尚未采用的新生成结果不会写入草稿。
        </p>
        <p v-if="storageError" class="field-error" role="alert">
          {{ storageError }}
        </p>
        <div class="button-row">
          <button class="secondary" @click="cancelNavigation">继续编辑</button>
          <button class="text-button" @click="resolveNavigation(false)">
            放弃未保存修改
          </button>
          <button
            class="primary"
            :disabled="session.busy || !generatedForm.topic"
            @click="resolveNavigation(true)"
          >
            保存并继续
          </button>
        </div>
      </section>
    </div>
    <div
      v-if="dragState?.active && dragState.type === 'sort'"
      class="block-drag-ghost"
      :style="{ left: dragState.x + 14 + 'px', top: dragState.y + 14 + 'px' }"
      aria-hidden="true"
    >
      ⠿ {{ draggedBlock?.title || '未命名内容' }}
    </div>
    <module-picker v-if="modulePickerOpen" @close="modulePickerOpen = false" @insert="insertBlock" />
    <html-code-dialog
      v-if="codeDialogOpen"
      :initial-source="codeInitialSource"
      :initial-title="codeInitialTitle"
      :initial-height="codeInitialHeight"
      :editing="Boolean(codeEditId)"
      @close="codeDialogOpen = false"
      @commit="commitHtmlCode"
    >
    </html-code-dialog>
    <div v-if="toast" class="toast" role="status">{{ toast }}</div>
  </div>
</template>
<style scoped src="../assets/topic-builder.css"></style>
