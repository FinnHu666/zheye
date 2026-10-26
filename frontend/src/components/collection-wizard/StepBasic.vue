<script setup>
// 第一步：基础信息。名称、简介、适用人群、发布目标写入专题工作稿；封面、标签、分类是引子（暂存本浏览器）。
import { computed, ref } from 'vue'
import { AUDIENCES, CATEGORIES, GOALS, addTag, checkCover, joinAudience, parseAudience, suggestions } from '../../lib/collectionWizard'
const props = defineProps({
  draft: { type: Object, required: true },
  extras: { type: Object, required: true },
  attempted: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['stub'])
const tagInput = ref(''), coverError = ref(''), root = ref(null)
const audience = computed(() => props.draft.metadata?.audiences || parseAudience(props.draft.audience))
const toggleAudience = label => {
  const next = audience.value.includes(label) ? audience.value.filter(x => x !== label) : [...audience.value, label]
  props.draft.audience = joinAudience(next)
  props.draft.metadata = { ...props.draft.metadata, audiences: next }
}
const submitTag = () => { props.extras.tags = addTag(props.extras.tags, tagInput.value); tagInput.value = '' }
const removeTag = tag => { props.extras.tags = props.extras.tags.filter(t => t !== tag) }
const pickCover = event => {
  const file = event.target.files?.[0]; event.target.value = ''
  coverError.value = checkCover(file)
  if (coverError.value) return
  if (props.extras.cover) URL.revokeObjectURL(props.extras.cover)
  props.extras.cover = URL.createObjectURL(file)
  props.extras.coverFile = file
}
const clearCover = () => { if (props.extras.cover) URL.revokeObjectURL(props.extras.cover); props.extras.cover = ''; props.extras.coverId = null; props.extras.coverFile = null }
const tips = computed(() => suggestions(props.draft, props.extras))
const missing = field => props.attempted && ({
  name: !String(props.draft.name || '').trim(),
  description: !String(props.draft.description || '').trim(),
  audience: !String(props.draft.audience || '').trim(),
  goal: !String(props.draft.goal || '').trim()
}[field])
const focusField = field => root.value?.querySelector(`[data-field="${field}"]`)?.focus()
defineExpose({ focusField })
</script>

<template>
  <div ref="root" class="cw-basic">
    <section class="cw-card cw-form" aria-labelledby="cw-basic-title">
      <header class="cw-card-head"><span class="cw-card-icon" aria-hidden="true">信</span><div><h2 id="cw-basic-title">基本信息</h2><p>设置专题的基本信息，便于读者快速了解专题内容</p></div></header>
      <fieldset class="cw-fields" :disabled="disabled">
        <div class="cw-row" :class="{ invalid: missing('name') }">
          <label class="cw-label" for="cw-name"><b>*</b>专题标题</label>
          <div class="cw-control"><div class="cw-input-wrap"><input id="cw-name" v-model="draft.name" data-field="name" maxlength="80" autocomplete="off" placeholder="例如：AI 学习与工具实践指南" /><span class="cw-count">{{ draft.name.length }}/80</span></div><small v-if="missing('name')" class="cw-error" role="alert">请填写专题标题</small></div>
        </div>
        <div class="cw-row" :class="{ invalid: missing('description') }">
          <label class="cw-label" for="cw-desc"><b>*</b>专题简介</label>
          <div class="cw-control"><div class="cw-input-wrap"><textarea id="cw-desc" v-model="draft.description" data-field="description" maxlength="260" rows="4" placeholder="用几句话概括专题的价值和适用人群"></textarea><span class="cw-count">{{ draft.description.length }}/260</span></div><small v-if="missing('description')" class="cw-error" role="alert">请填写专题简介</small></div>
        </div>
        <div class="cw-row">
          <span class="cw-label">专题封面</span>
          <div class="cw-control cw-cover-row">
            <div v-if="extras.cover" class="cw-cover-box"><img :src="extras.cover" alt="专题封面预览" /><button type="button" class="cw-cover-remove" aria-label="移除封面" @click="clearCover">×</button></div>
            <label class="cw-upload"><input type="file" accept="image/jpeg,image/png" @change="pickCover" /><span class="cw-upload-icon" aria-hidden="true">↑</span><strong>点击上传封面图</strong><small>支持 JPG、PNG 格式，建议 16:9（如 1200 × 675）<br />文件大小不超过 2MB，上传后会居中裁成 16:9</small></label>
            <small v-if="coverError" class="cw-error" role="alert">{{ coverError }}</small>
          </div>
        </div>
        <div class="cw-row">
          <span class="cw-label">标签</span>
          <div class="cw-control"><div class="cw-tags"><span v-for="tag in extras.tags" :key="tag" class="cw-tag">{{ tag }}<button type="button" :aria-label="`移除标签 ${tag}`" @click="removeTag(tag)">×</button></span><input v-model="tagInput" class="cw-tag-input" maxlength="12" placeholder="+ 添加标签，按回车确认（最多 6 个）" aria-label="添加标签" @keydown.enter.prevent="submitTag" @blur="submitTag" /></div><small class="cw-hint">建议添加 3–6 个标签（最多 6 个），便于读者发现专题</small></div>
        </div>
        <div class="cw-row" :class="{ invalid: missing('audience') }">
          <span class="cw-label"><b>*</b>适用人群</span>
          <div class="cw-control"><div class="cw-checks" role="group" aria-label="适用人群"><label v-for="label in AUDIENCES" :key="label" class="cw-check"><input type="checkbox" :checked="audience.includes(label)" data-field="audience" @change="toggleAudience(label)" /><span>{{ label }}</span></label></div><small v-if="draft.audience && !audience.length" class="cw-hint">旧适用人群：{{ draft.audience }}；重新选择时会替换原文。</small><small v-if="missing('audience')" class="cw-error" role="alert">至少选择一类适用人群</small></div>
        </div>
        <div class="cw-row">
          <label class="cw-label" for="cw-category">专题分类</label>
          <div class="cw-control"><select id="cw-category" v-model="extras.category"><option value="">请选择分类</option><option v-for="c in CATEGORIES" :key="c" :value="c">{{ c }}</option></select></div>
        </div>
        <div class="cw-row" :class="{ invalid: missing('goal') }">
          <span class="cw-label"><b>*</b>发布目标</span>
          <div class="cw-control"><div class="cw-goals" role="radiogroup" aria-label="发布目标"><label v-for="g in GOALS" :key="g.key" class="cw-goal" :class="{ selected: draft.goal === g.key }"><input v-model="draft.goal" type="radio" name="cw-goal" data-field="goal" :value="g.key" /><span><strong>{{ g.key }}</strong><small>{{ g.hint }}</small></span></label></div><small v-if="missing('goal')" class="cw-error" role="alert">请选择发布目标</small></div>
        </div>
      </fieldset>
    </section>

    <aside class="cw-side" aria-label="专题预览与建议">
      <section class="cw-card cw-preview-card">
        <header class="cw-side-head"><h3>专题预览</h3></header>
        <div class="cw-cover-preview" :class="{ empty: !extras.cover }"><img v-if="extras.cover" :src="extras.cover" alt="" /><span v-else>{{ draft.name || '专题封面' }}</span></div>
        <h4>{{ draft.name || '未命名专题' }}</h4>
        <p>{{ draft.description || '专题简介会显示在这里。' }}</p>
        <div v-if="extras.tags.length || audience.length" class="cw-chips"><span v-for="tag in extras.tags" :key="tag">{{ tag }}</span><span v-for="a in audience" :key="a" class="aud">{{ a }}</span></div>
      </section>
      <section class="cw-card cw-tips">
        <header class="cw-side-head"><h3>填写建议</h3></header>
        <ul><li v-for="tip in tips" :key="tip.text" :class="{ done: tip.done }"><i aria-hidden="true">{{ tip.done ? '✓' : '○' }}</i>{{ tip.text }}</li></ul>
      </section>
    </aside>
  </div>
</template>
