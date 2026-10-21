<script setup>
import { computed } from 'vue'
import { readingBlocks, markedSegments } from '../lib/reviewReading'
const props = defineProps({ text: String, terms: { type: Array, default: () => [] } })
const blocks = computed(() => readingBlocks(props.text))
</script>
<template>
  <div class="rr-document">
    <component v-for="(block,index) in blocks" :key="index" :is="block.kind==='heading'?'h'+Math.max(2,block.level):block.kind==='code'?'pre':block.kind==='quote'?'blockquote':'p'" :class="{'rr-list-line':block.kind==='list'}">
      <template v-for="(part,i) in markedSegments(block.text,terms)" :key="i"><mark v-if="part.hit">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template>
    </component>
  </div>
</template>
