<script setup>
// FINNS-17 R3：内容流转时间线。审批阶段真实展示；创建/草稿、更新发布、撤回为预留阶段（灰色占位），由 FINNS-18/19 接入时点亮。
import { computed } from 'vue'
const props = defineProps({ flow: Object, audience: { type: String, default: 'author' }, compact: Boolean, lit: Boolean })
const time = value => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
const stageLabel = { SUBMIT: '提交', CHECK: '检测', ROUTE: '分流', REVIEW: '人工', RESULT: '结果' }
const pendingKind = computed(() => props.flow?.status === 'SCHEDULED' ? '检测与审核已通过，等待定时公开' : props.flow?.status === 'IN_REVIEW' ? '等待审核员处理' : props.flow?.status === 'AUTO_REJECTED' ? '人工复核中' : '')
const waiting = computed(() => !!pendingKind.value)
const tone = event => event.stage === 'RESULT' ? (/已发布/.test(event.title) ? 'ok' : /失效|撤回|删除|取消/.test(event.title) ? 'muted' : 'bad') : event.stage === 'ROUTE' && /送审|不通过/.test(event.title) ? 'warn' : event.stage === 'REVIEW' ? 'info' : 'plain'
</script>
<template>
  <ol class="ft" :class="{ compact }" aria-label="内容流转过程">
    <li v-if="lit" class="ft-item ok"><span class="ft-dot" aria-hidden="true"></span><div><strong>私人草稿 → 提交</strong><small>发布时先保存当前输入，并冻结为提交版 v{{ flow?.version }}</small></div></li>
    <li v-else class="ft-item reserved"><span class="ft-dot" aria-hidden="true"></span><div><strong>创建与草稿</strong><small>预留阶段 · 由内容创作功能提供（FINNS-18 / 19）</small></div></li>
    <li v-for="event in flow?.events || []" :key="event.id" class="ft-item" :class="tone(event)">
      <span class="ft-dot" aria-hidden="true"></span>
      <div>
        <div class="ft-head"><span class="ft-stage">{{ stageLabel[event.stage] }}</span><strong>{{ event.title }}</strong><time :datetime="event.at">{{ time(event.at) }}</time></div>
        <p v-if="event.note">{{ event.note }}</p>
        <p v-if="event.detail && (audience === 'reviewer' || !compact)" class="ft-detail">{{ event.detail }}</p>
        <small v-if="event.actor">处理人：{{ event.actor }}</small>
      </div>
    </li>
    <li v-if="waiting" class="ft-item pending"><span class="ft-dot" aria-hidden="true"></span><div><strong>{{ pendingKind }}</strong><small>处理结果会出现在这里</small></div></li>
    <li v-if="!lit" class="ft-item reserved"><span class="ft-dot" aria-hidden="true"></span><div><strong>更新发布 · 撤回</strong><small>预留阶段 · 后续由内容创作功能提供</small></div></li>
  </ol>
</template>
