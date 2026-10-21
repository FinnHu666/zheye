<script setup>
// 分页：显示页码并支持跳转（用户评审意见：不能只有上一页/下一页）。page 为从 0 开始的页索引。
import { computed, ref } from 'vue'
const props = defineProps({ page: { type: Number, default: 0 }, size: { type: Number, default: 10 }, total: { type: Number, default: 0 } })
const emit = defineEmits(['go'])
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const shown = computed(() => {
  const count = pages.value, current = props.page, out = new Set([0, count - 1, current - 1, current, current + 1])
  const list = [...out].filter(n => n >= 0 && n < count).sort((a, b) => a - b), items = []
  list.forEach((n, i) => { if (i && n - list[i - 1] > 1) items.push({ gap: true, key: 'g' + n }); items.push({ n, key: n }) })
  return items
})
const jump = ref('')
function go(n) { if (n >= 0 && n < pages.value && n !== props.page) emit('go', n) }
function submitJump() { const n = Number(jump.value) - 1; if (Number.isInteger(n)) go(Math.min(Math.max(n, 0), pages.value - 1)); jump.value = '' }
</script>
<template>
  <nav class="pn" aria-label="分页"><span class="pn-total">共 {{ total }} 条 · 第 {{ page + 1 }} / {{ pages }} 页</span>
    <button class="btn btn-ghost" :disabled="page <= 0" @click="go(page - 1)">上一页</button>
    <template v-for="item in shown" :key="item.key"><span v-if="item.gap" class="pn-gap" aria-hidden="true">…</span><button v-else class="pn-num" :class="{ on: item.n === page }" :aria-current="item.n === page ? 'page' : undefined" @click="go(item.n)">{{ item.n + 1 }}</button></template>
    <button class="btn btn-ghost" :disabled="page + 1 >= pages" @click="go(page + 1)">下一页</button>
    <form class="pn-jump" @submit.prevent="submitJump"><label class="sr-only" for="pn-jump-input">跳转到页码</label><span>跳至</span><input id="pn-jump-input" v-model="jump" inputmode="numeric" pattern="[0-9]*" :placeholder="String(page + 1)" /><span>页</span><button class="btn btn-ghost" type="submit">跳转</button></form>
  </nav>
</template>
