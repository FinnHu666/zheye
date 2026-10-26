<script setup>
// 第三步：预览发布。预览与检查基于当前工作稿；可见范围、发布操作走已有接口；定时发布、SEO 是引子。
import { computed, ref } from 'vue'
import CollectionReader from '../CollectionReader.vue'
import { CATEGORIES, CATEGORY_KEYS } from '../../lib/collectionWizard'
import { basicIssues, completeness, summarize } from '../../lib/collectionWizard'
const props = defineProps({
  capabilities: { type: Object, default: () => ({}) },
  posts: { type: Array, default: () => [] },
  draft: { type: Object, required: true },
  extras: { type: Object, required: true },
  issues: { type: Array, default: () => [] },
  canRestrict: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  published: { type: Boolean, default: false }
})
const emit = defineEmits(['save', 'publish', 'goto', 'stub'])
const device = ref('desktop')
const stats = computed(() => summarize(props.draft))
const progress = computed(() => completeness(props.draft))
const basic = computed(() => basicIssues(props.draft))
const linkIssues = computed(() => props.issues.filter(i => i.step === 'content' && ['externalUrl'].includes(i.field)))
const contentIssues = computed(() => props.issues.filter(i => i.step === 'content'))
const checks = computed(() => [
  { label: '基础信息', state: basic.value.length ? 'todo' : 'ok', text: basic.value.length ? `还有 ${basic.value.length} 项待补充` : '标题、简介、适用人群和发布目标已完整', issue: basic.value[0] },
  { label: '内容编排', state: contentIssues.value.length ? 'todo' : 'ok', text: contentIssues.value.length ? `还有 ${contentIssues.value.length} 处待补充` : `共 ${stats.value.entries} 个内容模块`, issue: contentIssues.value[0] },
  { label: '链接检测', state: linkIssues.value.length ? 'todo' : 'ok', text: linkIssues.value.length ? '有链接地址不完整' : '链接格式正确（不检测对方站点是否可访问）', issue: linkIssues.value[0] }
])
const readyToPublish = computed(() => !props.issues.length)
const preview = computed(() => ({ collection: props.draft, sections: props.draft.sections.map(section => ({...section,entries:section.entries.map(entry => { if(entry.kind!=='POST')return entry;const post=props.posts.find(p=>String(p.id)===String(entry.postId));return post?{...entry,postTitle:post.title,postSummary:post.summary,postAuthor:post.authorName}:entry })})), metadata: { ...props.draft.metadata, ...props.extras, category: CATEGORY_KEYS[CATEGORIES.indexOf(props.extras.category)] || '', sectionLayouts: props.draft.sections.map(s => s.displayLayout || 'LIST') } }))
const address = computed(() => `${globalThis.location?.origin || ''}/collections/${props.draft.id}`)
const visibilityPending = computed(() => props.published)
</script>

<template>
  <div class="cw-publish">
    <section class="cw-card cw-preview-pane">
      <header class="cw-canvas-head"><div><h2>专题页面预览</h2><p>请在发布前仔细检查页面内容、排版和链接，确保信息准确无误。</p></div>
        <div class="cw-device" role="tablist"><button type="button" role="tab" :aria-selected="device === 'desktop'" :class="{ active: device === 'desktop' }" @click="device = 'desktop'">电脑端预览</button><button type="button" role="tab" :aria-selected="device === 'mobile'" :class="{ active: device === 'mobile' }" @click="device = 'mobile'">手机端预览</button></div></header>
      <div class="cw-browser" :class="device">
        <div class="cw-browser-bar"><i></i><i></i><i></i><span>{{ address }}</span></div>
        <CollectionReader :outline="preview" />
      </div>
    </section>

    <aside class="cw-side">
      <section class="cw-card">
        <header class="cw-side-head"><h3>预览检查</h3></header>
        <ul class="cw-checks-list"><li v-for="c in checks" :key="c.label" :class="c.state"><i aria-hidden="true">{{ c.state === 'ok' ? '✓' : c.state === 'todo' ? '!' : 'i' }}</i><div><strong>{{ c.label }}</strong><small>{{ c.text }}</small></div><button v-if="c.issue" type="button" class="cw-link" @click="emit('goto', c.issue)">去补充</button></li></ul>
        <div v-if="issues.length" class="cw-issues" role="alert"><strong>发布前还有 {{ issues.length }} 处需要补充</strong><ul><li v-for="(issue, i) in issues" :key="i"><button type="button" @click="emit('goto', issue)">{{ issue.message }} →</button></li></ul></div>
      </section>

      <section class="cw-card">
        <header class="cw-side-head"><h3>内容完整度</h3><b class="cw-percent">{{ progress.percent }}%</b></header>
        <div class="cw-progress" role="progressbar" :aria-valuenow="progress.percent" aria-valuemin="0" aria-valuemax="100"><span :style="{ width: progress.percent + '%' }"></span></div>
        <ul class="cw-progress-items"><li v-for="item in progress.items" :key="item.label" :class="{ done: item.done }"><i aria-hidden="true">{{ item.done ? '✓' : '○' }}</i>{{ item.label }}</li></ul>
      </section>

      <section class="cw-card cw-settings">
        <header class="cw-side-head"><h3>发布设置</h3></header>
        <div class="cw-set-row"><span>可见范围</span><div class="cw-set-opts"><label><input v-model="draft.visibility" type="radio" name="cw-visibility" value="PUBLIC" />公开发布<small>所有人可访问</small></label><label :class="{ off: !canRestrict }"><input v-model="draft.visibility" type="radio" name="cw-visibility" value="RESTRICTED" :disabled="!canRestrict" />受限<small>仅拥有“阅读受限专题”权限的角色可见</small></label></div></div>
        <p v-if="visibilityPending && draft.visibility !== draft.publishedVisibility" class="cw-hint">已保存到工作稿；更新发布后，公开版本才会改变。</p>
        <div class="cw-set-row"><span>发布时间</span><div class="cw-set-opts"><label><input v-model="extras.scheduled" type="radio" name="cw-when" :value="false" />立即发布</label><label :class="{ off: !capabilities.schedulingEnabled }"><input v-model="extras.scheduled" type="radio" name="cw-when" :value="true" :disabled="!capabilities.schedulingEnabled" />定时发布<span v-if="!capabilities.schedulingEnabled">（未开启）</span></label></div></div>
        <div v-if="extras.scheduled" class="cw-schedule-setting">
          <label class="cw-set-row"><span>公开时间</span><input v-model="extras.scheduledTime" type="datetime-local" aria-describedby="cw-schedule-hint" required /></label>
          <p id="cw-schedule-hint" class="cw-hint">5分钟后至1年内，按提交版本公开。</p>
        </div>
        <p class="cw-hint">封面不进行自动图像检测，请确认拥有使用权且内容适合公开。</p><label class="cw-set-row seo"><span>SEO 标题</span><div class="cw-input-wrap"><input v-model="extras.seoTitle" maxlength="60" :placeholder="draft.name || '专题标题'" /><span class="cw-count">{{ extras.seoTitle.length }}/60</span></div></label>
        <div class="cw-publish-actions"><button type="button" class="btn btn-outline" :disabled="busy" @click="emit('save')">保存草稿</button><button type="button" class="btn btn-primary" :disabled="busy" @click="emit('publish')">{{ busy ? '正在处理…' : published ? '更新发布' : '发布专题' }}</button></div>
        <p v-if="!readyToPublish" class="cw-hint">仍有待补充内容；点击发布时会带你前往对应位置，也可以先保存草稿。</p>
      </section>
    </aside>
  </div>
</template>
