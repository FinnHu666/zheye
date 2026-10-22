// 0.7.0 网站访问量 + 角色访问管理原型。
// 直接加载 frontend/src 的真实页面与样式；只把 0.7.0 需要改动的 4 个文件替换为 src/overlays/ 版本。
// 复用 frontend/node_modules，不新增依赖。
import { defineConfig } from '../../../frontend/node_modules/vite/dist/node/index.js'
import vue from '../../../frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs'
import { fileURLToPath } from 'node:url'

const here = (path) => fileURLToPath(new URL(path, import.meta.url))
const repo = here('../../../')
const realSrc = here('../../../frontend/src/').replace(/\\/g, '/')
const overlays = {
  'App.vue': here('./src/overlays/App.vue'),
  'pages/DiscoverPage.vue': here('./src/overlays/DiscoverPage.vue'),
  'components/PersonalNav.vue': here('./src/overlays/PersonalNav.vue'),
  'pages/CollectionEditorPage.vue': here('./src/overlays/CollectionEditorPage.vue'),
  'pages/MyContentPage.vue': here('./src/overlays/MyContentPage.vue'),
}

const overlayPlugin = {
  name: 'finns-prototype-overlay',
  enforce: 'pre',
  async resolveId(source, importer, options) {
    if (!source.endsWith('.vue')) return null
    const resolved = await this.resolve(source, importer, { ...options, skipSelf: true })
    if (!resolved) return null
    const id = resolved.id.replace(/\\/g, '/')
    const hit = Object.keys(overlays).find((rel) => id.toLowerCase() === (realSrc + rel).toLowerCase())
    return hit ? overlays[hit] : null
  },
}

export default defineConfig({
  root: here('.'),
  plugins: [overlayPlugin, vue()],
  resolve: {
    alias: {
      '@finns': here('../../../frontend/src'),
      vue: here('../../../frontend/node_modules/vue/dist/vue.runtime.esm-bundler.js'),
      'vue-router': here('../../../frontend/node_modules/vue-router/dist/vue-router.mjs'),
    },
  },
  define: { __VUE_OPTIONS_API__: true, __VUE_PROD_DEVTOOLS__: false, __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: false },
  server: { host: '127.0.0.1', port: 4175, strictPort: true, fs: { allow: [repo] } },
  build: { outDir: here('../../../target/prototypes/site-access'), emptyOutDir: true },
})
