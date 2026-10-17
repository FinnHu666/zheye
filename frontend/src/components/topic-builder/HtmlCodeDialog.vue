<script>
import { nextTick } from 'vue'
import {
  compileHtmlBlock,
  HTML_BLOCK_VERSION,
  MAX_HTML_LENGTH,
  timelineExample,
} from '../../lib/topic-builder/html'
export default {
  emits: ['close', 'commit'],
  props: {
    initialSource: { type: String, default: '' },
    initialTitle: { type: String, default: '' },
    initialHeight: { type: Number, default: 360 },
    editing: Boolean,
  },
  data() {
    return {
      source: this.initialSource,
      name: this.initialTitle,
      height: this.initialHeight,
      checkedSource: null,
      previewDocument: '',
      error: '',
      fileError: '',
      loading: false,
      device: 'desktop',
    }
  },
  computed: {
    ready() {
      return (
        this.checkedSource === this.source &&
        Boolean(this.previewDocument) &&
        !this.loading &&
        Boolean(this.name.trim())
      )
    },
    validHeight() {
      return (
        Number.isFinite(Number(this.height)) &&
        Number(this.height) >= 120 &&
        Number(this.height) <= 1200
      )
    },
  },
  mounted() {
    this.$refs.dialog.showModal()
    nextTick(() => this.$refs.name.focus())
  },
  beforeUnmount() {
    this.$refs.dialog?.close()
  },
  methods: {
    close() {
      if (!this.loading) this.$emit('close')
    },
    example() {
      this.source = timelineExample
      if (!this.name.trim()) this.name = '租房行动时间轴'
      this.error = ''
      this.fileError = ''
    },
    inspect() {
      try {
        const result = compileHtmlBlock(this.source)
        this.previewDocument = result.srcdoc
        this.checkedSource = this.source
        this.error = ''
      } catch (error) {
        this.error = error.message
        this.checkedSource = null
      }
    },
    commit() {
      if (!this.ready) return
      if (!this.validHeight) {
        this.error = '模块显示高度需要在 120—1200px 之间。'
        return
      }
      this.$emit('commit', {
        title: this.name.trim(),
        code: {
          rendererVersion: HTML_BLOCK_VERSION,
          source: this.source,
          frameHeight: Number(this.height),
        },
      })
    },
    async readFile(event) {
      const file = event.target.files?.[0]
      if (!file) return
      this.loading = true
      this.fileError = ''
      try {
        if (!/\.html?$/i.test(file.name))
          throw new Error('请选择 .html 或 .htm 文件。')
        if (file.size > 131072) throw new Error('文件最多 128 KiB。')
        const source = new TextDecoder('utf-8', { fatal: true }).decode(
          await file.arrayBuffer(),
        )
        if (source.length > MAX_HTML_LENGTH)
          throw new Error('代码最多 60,000 字。')
        this.source = source
        if (!this.name.trim())
          this.name = file.name.replace(/\.html?$/i, '').slice(0, 100)
        this.checkedSource = null
        this.error = ''
      } catch (error) {
        this.fileError =
          error instanceof TypeError
            ? '无法读取文件，请使用 UTF-8 编码。'
            : error.message
      } finally {
        this.loading = false
        event.target.value = ''
      }
    },
  },
}
</script>
<template>
  <dialog
    ref="dialog"
    class="code-dialog"
    aria-labelledby="code-dialog-title"
    @cancel.prevent="close"
  >
    <header class="code-dialog-header">
      <div>
        <span class="tiny-label">自定义 HTML + CSS</span>
        <h2 id="code-dialog-title">
          {{ editing ? '编辑模块代码' : '导入一个自己的模块' }}
        </h2>
        <p>先检查预览，确认后再添加。位置与大小仍由画布管理。</p>
      </div>
      <button
        type="button"
        class="close-button"
        aria-label="关闭代码导入"
        :disabled="loading"
        @click="close"
      >
        ×
      </button>
    </header>
    <div class="code-dialog-grid">
      <section class="code-source-panel">
        <div class="code-source-meta">
          <label
            >模块名称<input
              ref="name"
              v-model="name"
              maxlength="100"
              :disabled="loading" /></label
          ><label
            >显示高度（px）<input
              type="number"
              v-model.number="height"
              min="120"
              max="1200"
              :disabled="loading"
          /></label>
        </div>
        <p v-if="!validHeight" class="field-error" role="alert">
          显示高度需要在 120—1200px 之间。
        </p>
        <div class="code-source-actions">
          <button
            type="button"
            class="secondary"
            @click="example"
            :disabled="loading"
          >
            使用时间轴示例</button
          ><button
            type="button"
            class="text-button"
            @click="$refs.file.click()"
            :disabled="loading"
          >
            {{ loading ? '读取中…' : '导入 HTML 文件' }}</button
          ><input
            ref="file"
            type="file"
            accept=".html,.htm,text/html"
            hidden
            @change="readFile"
          />
        </div>
        <label
          >HTML + CSS 代码<textarea
            class="code-source-input"
            v-model="source"
            spellcheck="false"
            rows="16"
            :disabled="loading"
            placeholder="粘贴包含 HTML 和 <style> 的代码…"
          ></textarea></label
        ><span class="code-count"
          >{{ source.length.toLocaleString() }} / 60,000 字 · UTF-8 文件最多 128
          KiB</span
        >
        <p v-if="fileError" class="field-error" role="alert">{{ fileError }}</p>
        <p v-if="error" class="field-error" role="alert">{{ error }}</p>
        <button
          type="button"
          class="secondary code-check"
          @click="inspect"
          :disabled="loading"
        >
          检查并预览
        </button>
      </section>
      <section class="code-result-panel">
        <div class="code-preview-heading">
          <strong>模块预览</strong>
          <div class="segmented" aria-label="代码预览宽度">
            <button
              type="button"
              :class="{ active: device === 'desktop' }"
              @click="device = 'desktop'"
            >
              宽屏预览</button
            ><button
              type="button"
              :class="{ active: device === 'mobile' }"
              @click="device = 'mobile'"
            >
              窄屏预览
            </button>
          </div>
        </div>
        <p
          v-if="previewDocument && checkedSource !== source"
          class="code-preview-stale"
          role="status"
        >
          代码已修改，请重新检查；下方保留上一次预览。
        </p>
        <div
          class="code-preview-surface"
          :class="{ narrow: device === 'mobile' }"
        >
          <iframe
            v-if="previewDocument"
            class="code-dialog-frame"
            sandbox=""
            referrerpolicy="no-referrer"
            title="导入代码预览"
            :srcdoc="previewDocument"
            :style="{ height: (validHeight ? height : 360) + 'px' }"
          ></iframe>
          <div v-else class="code-preview-empty">
            你的代码会在这里呈现。<br />可以先体验时间轴示例。
          </div>
        </div>
        <p class="code-boundary">
          支持静态 HTML + CSS 和 details
          折叠。首轮不执行脚本、不加载外部依赖；未知标签和属性会提示。手机容器会适配宽度，内部响应式样式由代码定义，超出高度可滚动。
        </p>
      </section>
    </div>
    <footer class="code-dialog-footer">
      <button
        type="button"
        class="text-button"
        @click="close"
        :disabled="loading"
      >
        取消</button
      ><span>{{
        ready ? '代码已检查，可以添加' : '检查通过后才会更新页面'
      }}</span
      ><button
        type="button"
        class="primary"
        :disabled="!ready || !validHeight"
        @click="commit"
      >
        {{ editing ? '确认更新模块' : '添加到页面' }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.code-dialog {
  color: var(--ink);
  width: min(1120px, calc(100vw - 48px));
  max-height: calc(100dvh - 48px);
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: #fff;
  box-shadow: var(--shadow);
  overflow: auto;
}
.code-dialog::backdrop {
  background: #1d30285c;
  backdrop-filter: blur(3px);
}
.code-dialog-header {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;
  padding: 27px 30px 20px;
  border-bottom: 1px solid var(--line);
}
.code-dialog-header h2 {
  font-size: 24px;
  font-weight: 500;
  margin: 8px 0;
}
.code-dialog-header p {
  font-size: 12px;
  color: var(--muted);
  margin: 0;
}
.code-dialog-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
.code-source-panel {
  padding: 24px 28px;
  border-right: 1px solid var(--line);
  min-width: 0;
}
.code-source-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 125px;
  gap: 14px;
}
.code-source-meta label,
.code-source-panel > label {
  font-size: 12px;
}
.code-source-actions {
  display: flex;
  align-items: center;
  gap: 15px;
  flex-wrap: wrap;
  margin: 18px 0;
}
.code-source-actions button {
  font-size: 11px;
}
.code-source-input {
  font:
    12px/1.7 Consolas,
    'Microsoft YaHei',
    monospace;
  min-height: 260px;
  background: #f8faf5;
  tab-size: 2;
}
.code-count {
  display: block;
  margin-top: 8px;
  color: var(--muted);
  font-size: 10px;
}
.code-check {
  margin-top: 15px;
  font-size: 12px;
}
.code-result-panel {
  padding: 24px 24px;
  background: #f8f9f4;
  min-width: 0;
}
.code-preview-heading {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 18px;
  font-size: 12px;
}
.code-preview-surface {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
  max-width: 100%;
  margin: 0 auto;
}
.code-preview-surface.narrow {
  max-width: 320px;
}
.code-dialog-frame {
  display: block;
  border: 0;
  width: 100%;
  min-height: 120px;
  background: white;
}
.code-preview-empty {
  min-height: 320px;
  display: grid;
  place-content: center;
  text-align: center;
  color: var(--muted);
  font-size: 12px;
  line-height: 2;
}
.code-preview-stale {
  padding: 10px;
  background: #fff0d9;
  color: #86612e;
  font-size: 11px;
  border-radius: 8px;
}
.code-boundary {
  font-size: 11px;
  color: var(--muted);
  line-height: 1.9;
  margin: 16px 0 0;
}
.code-dialog-footer {
  position: sticky;
  bottom: 0;
  display: flex;
  gap: 15px;
  align-items: center;
  padding: 18px 28px;
  border-top: 1px solid var(--line);
  background: #fff;
  z-index: 1;
}
.code-dialog-footer > span {
  margin-left: auto;
  color: var(--muted);
  font-size: 11px;
}
@media (max-width: 760px) {
  .code-dialog {
    width: 100%;
    max-width: 100%;
    height: 100dvh;
    max-height: 100dvh;
    margin: 0;
    border: 0;
    border-radius: 0;
  }
  .code-dialog-header {
    padding: 20px 18px;
  }
  .code-dialog-header h2 {
    font-size: 21px;
  }
  .code-dialog-grid {
    grid-template-columns: 1fr;
  }
  .code-source-panel,
  .code-result-panel {
    padding: 20px 18px;
    border-right: 0;
  }
  .code-source-meta {
    grid-template-columns: minmax(0, 1fr) 110px;
    gap: 10px;
  }
  .code-source-input {
    font-size: 16px;
    min-height: 240px;
  }
  .code-dialog-footer {
    padding: 15px 18px;
  }
  .code-dialog-footer > span {
    display: none;
  }
  .code-dialog-footer > .primary {
    margin-left: auto;
  }
  .code-module-settings input {
    font-size: 16px;
  }
}
/* Creation flow and review states for requirements 0.5. */
.creation-progress {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 22px;
  padding: 0 0 25px;
  margin-bottom: 8px;
  color: #79867c;
  font-size: 12px;
}
.creation-progress span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.creation-progress b {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 1px solid #dce3d5;
  border-radius: 50%;
  font-size: 10px;
  font-weight: 500;
}
.creation-progress .active {
  color: #244d40;
  font-weight: 600;
}
.creation-progress .active b {
  background: #244d40;
  color: #fff;
  border-color: #244d40;
}
.requirements-recap {
  border: 1px solid #dfe5da;
  border-radius: 12px;
  background: #fafbf7;
  padding: 15px 18px;
  margin: 18px 0 24px;
  font-size: 13px;
}
.requirements-recap summary {
  cursor: pointer;
  color: #345346;
  line-height: 1.8;
}
.requirements-recap .summary-list {
  margin-top: 15px;
}
.pending-requirements {
  display: inline-block;
  color: #866137;
  font-size: 11px;
  margin-left: 12px;
}
.editor-grid.reading-mode {
  grid-template-columns: minmax(0, 1fr);
}
.reading-mode .preview-area {
  max-width: 980px;
  width: 100%;
  margin: auto;
}
.regeneration-review {
  max-width: 1100px;
  margin: auto;
}
.regeneration-review .topic-page {
  max-width: 940px;
  margin: 20px auto;
}
.regeneration-review .content-warning {
  line-height: 1.8;
}
.regeneration-review .original-source {
  padding: 16px;
  border: 1px solid #dfe5da;
  border-radius: 10px;
}
.review-decision {
  justify-content: flex-end;
  margin: 24px 0 40px;
}
.regeneration-review .workbench-header h1 {
  font-size: 28px;
}
@media (max-width: 760px) {
  .creation-progress {
    gap: 10px 12px;
    padding-bottom: 18px;
    font-size: 11px;
  }
  .creation-progress b {
    width: 19px;
    height: 19px;
  }
  .requirements-recap {
    padding: 12px;
  }
  .pending-requirements {
    margin-left: 0;
    display: block;
  }
  .regeneration-review .workbench-header h1 {
    font-size: 23px;
  }
  .confirm-dialog .button-row {
    flex-wrap: wrap;
  }
  .review-decision button {
    flex: 1;
  }
  .regeneration-review .workbench-header .button-row {
    flex-wrap: wrap;
  }
}
</style>

<style scoped>
button,
input,
textarea,
select {
  font: inherit;
}
button {
  cursor: pointer;
  color: inherit;
  border: 0;
  background: none;
}
button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
input,
textarea,
select {
  box-sizing: border-box;
  width: 100%;
  padding: 11px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: white;
  color: var(--ink);
}
label {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
label > input,
label > textarea {
  margin-top: 8px;
}
.primary,
.secondary {
  padding: 11px 16px;
  border-radius: 9px;
  font-size: 12px;
  min-height: 42px;
}
.primary {
  background: var(--green);
  color: white;
}
.secondary {
  border: 1px solid #cdd5c7;
  background: white;
}
.text-button {
  color: var(--green);
  font-size: 12px;
}
.tiny-label {
  font-size: 10px;
  color: var(--green);
}
.close-button {
  font-size: 28px;
}
.segmented {
  display: flex;
  padding: 3px;
  background: #e9ece3;
  border-radius: 9px;
}
.segmented button {
  padding: 7px 12px;
  font-size: 10px;
}
.segmented .active {
  background: white;
  border-radius: 7px;
}
.field-error {
  padding: 12px;
  background: #fff0e9;
  color: #a34f35;
}
</style>
