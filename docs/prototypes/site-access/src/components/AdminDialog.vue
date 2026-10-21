<script setup>
import { nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
const props = defineProps({ labelledby: String, describedby: String, busy: Boolean, wide: Boolean, context: { type: String, default: 'FINNS · 账号与权限' } })
const emit = defineEmits(['close'])
const panel = ref(null)
let previousFocus
const focus = () => { panel.value?.focus({ preventScroll: true }); const content = panel.value?.querySelector('.admin-dialog-content'); if (content) content.scrollTop = 0 }
defineExpose({ focus })
const close = () => { if (!props.busy) emit('close') }
function onKey(event) {
  if (event.key === 'Escape') { event.stopPropagation(); close(); return }
  if (event.key !== 'Tab') return
  const controls = [...panel.value.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),a[href]')]
  const first = controls[0], last = controls.at(-1)
  if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.value)) { event.preventDefault(); first?.focus() }
}
onMounted(async () => { previousFocus = document.activeElement; await nextTick(); focus() })
onBeforeUnmount(() => { if (previousFocus?.isConnected) previousFocus.focus() })
</script>
<template>
  <div class="overlay admin-overlay" @click.self="close">
    <section ref="panel" class="modal admin-dialog" :class="{ 'admin-dialog-wide': wide }" role="dialog" aria-modal="true" :aria-labelledby="labelledby" :aria-describedby="describedby" tabindex="-1" @keydown="onKey">
      <header class="admin-dialog-bar"><span>{{ context }}</span><button class="admin-dialog-close" type="button" aria-label="关闭" :disabled="busy" @click="close"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m7 7 10 10M17 7 7 17" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button></header>
      <div class="admin-dialog-content"><slot /></div>
    </section>
  </div>
</template>
