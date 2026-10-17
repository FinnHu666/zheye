<script>
import { nextTick } from 'vue'
import {
  compileHtmlBlock,
  HTML_BLOCK_VERSION,
  MAX_HTML_LENGTH,
  timelineExample,
} from '../../lib/topic-builder/html'
export default {
  props: { code: Object, title: String },
  computed: {
    compiled() {
      try {
        if (this.code?.rendererVersion !== HTML_BLOCK_VERSION)
          throw new Error('模块版本不支持，请重新检查代码。')
        return { ...compileHtmlBlock(this.code.source), error: '' }
      } catch (error) {
        return { error: error.message }
      }
    },
  },
}
</script>
<template>
  <div v-if="compiled.error" class="html-frame-error" role="alert">
    {{ compiled.error }}
  </div>
  <iframe
    v-else
    class="html-block-frame"
    sandbox=""
    referrerpolicy="no-referrer"
    :title="'HTML 模块：' + (title || '未命名模块')"
    :srcdoc="compiled.srcdoc"
    :style="{ '--code-frame-height': (code.frameHeight || 360) + 'px' }"
  ></iframe>
</template>
