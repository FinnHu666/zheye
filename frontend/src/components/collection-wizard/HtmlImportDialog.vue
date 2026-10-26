<script setup>
// 导入自己的 HTML 模块：粘贴代码或选择 .html 文件 → “检查并预览”（复用专题搭建器的检查规则，只允许静态 HTML + CSS）→ 保存到“我的”。
// 预览放在无脚本沙箱 iframe（sandbox=""、不发送来源），用户代码不会进入本页面。
import { computed, nextTick, onMounted, ref } from 'vue'
import { MAX_HTML_LENGTH, compileHtmlBlock, timelineExample } from '../../lib/topic-builder/html'
import { MAX_FILE_BYTES, MAX_HEIGHT, MIN_HEIGHT, importIssue } from '../../lib/collectionHtmlModules'
const props = defineProps({ library: { type: Array, default: () => [] } })
const emit = defineEmits(['close', 'save'])
const name = ref(''), source = ref(''), height = ref(240), error = ref(''), fileNote = ref('')
const checked = ref(null) // { source, srcdoc }
const nameEl = ref(null)
const ready = computed(() => checked.value && checked.value.source === source.value)
onMounted(() => nextTick(() => nameEl.value?.focus()))
const check = () => {
  fileNote.value = ''
  try { const result = compileHtmlBlock(source.value); checked.value = { source: source.value, srcdoc: result.srcdoc }; error.value = '' } catch (e) { checked.value = null; error.value = e.message }
}
const useExample = () => { source.value = timelineExample; if (!name.value.trim()) name.value = '行动时间轴'; checked.value = null; error.value = ''; fileNote.value = '' }
const pickFile = async event => {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  checked.value = null
  if (!/\.html?$/i.test(file.name) && file.type !== 'text/html') { error.value = '请选择 .html 文件。'; return }
  if (file.size > MAX_FILE_BYTES) { error.value = `文件不能超过 ${Math.round(MAX_FILE_BYTES / 1024)}KB。`; return }
  try {
    const text = await file.text()
    if (text.length > MAX_HTML_LENGTH) { error.value = `代码最多 ${MAX_HTML_LENGTH.toLocaleString()} 字，请精简后再导入。`; return }
    source.value = text; if (!name.value.trim()) name.value = file.name.replace(/\.html?$/i, '').slice(0, 40)
    error.value = ''; fileNote.value = `已读取 ${file.name}，请点“检查并预览”。`
  } catch { error.value = '读取文件失败，请改为粘贴代码。' }
}
const save = () => {
  if (!ready.value) { error.value = '请先点“检查并预览”，通过后才能保存。'; return }
  const issue = importIssue({ name: name.value, height: height.value, library: props.library })
  if (issue) { error.value = issue; return }
  emit('save', { name: name.value.trim(), source: source.value, height: Number(height.value) })
}
</script>

<template>
  <div class="cwb-import-back" @click.self="emit('close')" @keydown.esc.stop="emit('close')">
    <section class="cwb-import-dialog" role="dialog" aria-modal="true" aria-labelledby="cwb-import-title">
      <header><h3 id="cwb-import-title">导入 HTML 模块</h3><button type="button" class="cwb-toggle" aria-label="关闭" @click="emit('close')"><svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></button></header>
      <p class="cw-hint">只支持静态 HTML + CSS：不能有脚本、事件、外部链接、外部图片或字体。检查通过后会在隔离的预览框里显示，并保存到“我的”。</p>
      <div class="cwb-import-row2">
        <label>模块名称<input ref="nameEl" v-model="name" maxlength="40" placeholder="例如：行动时间轴" /></label>
        <label>显示高度（px）<input v-model.number="height" type="number" :min="MIN_HEIGHT" :max="MAX_HEIGHT" step="20" /></label>
      </div>
      <label>HTML + CSS 代码<textarea v-model="source" rows="9" spellcheck="false" placeholder="<style>…</style>&#10;<div>…</div>" :maxlength="MAX_HTML_LENGTH" @input="checked = null"></textarea></label>
      <div class="cwb-import-actions"><label class="btn btn-outline cwb-file"><input type="file" accept=".html,.htm,text/html" @change="pickFile" />选择 .html 文件</label><button type="button" class="btn btn-outline" @click="useExample">插入示例</button><button type="button" class="btn btn-primary" :disabled="!source.trim()" @click="check">检查并预览</button></div>
      <p v-if="fileNote" class="cw-hint" role="status">{{ fileNote }}</p>
      <p v-if="error" class="cw-error" role="alert">{{ error }}</p>
      <div v-if="ready" class="cwb-import-preview"><span class="cw-hint">预览（隔离显示，不会运行脚本）</span><iframe sandbox="" referrerpolicy="no-referrer" title="HTML 模块预览" :srcdoc="checked.srcdoc" :style="{ height: Math.min(Math.max(Number(height) || 240, MIN_HEIGHT), 320) + 'px' }"></iframe></div>
      <footer><button type="button" class="btn btn-outline" @click="emit('close')">取消</button><button type="button" class="btn btn-primary" :disabled="!ready" @click="save">保存到“我的”</button></footer>
    </section>
  </div>
</template>
