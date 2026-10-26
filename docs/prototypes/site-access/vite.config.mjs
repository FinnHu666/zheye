// 0.7.0 网站访问量 + 角色访问管理原型。
// 直接加载 frontend/src 的真实页面与样式；只把 0.7.0 需要改动的 4 个文件替换为 src/overlays/ 版本。
// 复用 frontend/node_modules，不新增依赖。
import { defineConfig } from '../../../frontend/node_modules/vite/dist/node/index.js'
import vue from '../../../frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs'
import { fileURLToPath } from 'node:url'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'

const here = (path) => fileURLToPath(new URL(path, import.meta.url))
const repo = here('../../../')
const realSrc = here('../../../frontend/src/').replace(/\\/g, '/')
const overlays = {
  'App.vue': here('./src/overlays/App.vue'),
  'pages/DiscoverPage.vue': here('./src/overlays/DiscoverPage.vue'),
  'components/PersonalNav.vue': here('./src/overlays/PersonalNav.vue'),
  'pages/CollectionEditorPage.vue': here('./src/overlays/CollectionEditorPage.vue'),
  'components/CollectionOutlineEditor.vue': here('./src/overlays/CollectionOutlineEditor.vue'),
  'pages/CollectionDetailPage.vue': here('./src/overlays/CollectionDetailPage.vue'),
  'pages/CollectionNewPage.vue': here('./src/overlays/CollectionNewPage.vue'),
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

// 原型专用：把本地 node_modules/vditor 以 /vendor/vditor 提供，Vditor 不再访问外部 CDN。
const vditorRoot = here('./node_modules/vditor')
const types = { '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.wasm': 'application/wasm' }
const vditorAssets = {
  name: 'finns-vditor-assets',
  configureServer(server) {
    server.middlewares.use('/vendor/vditor', (req, res, next) => {
      const file = normalize(join(vditorRoot, decodeURIComponent(req.url.split('?')[0])))
      if (!file.startsWith(vditorRoot) || !existsSync(file) || !statSync(file).isFile()) return next()
      res.setHeader('Content-Type', types[extname(file)] || 'application/octet-stream'); createReadStream(file).pipe(res)
    })
  },
}

export default defineConfig({
  root: here('.'),
  plugins: [overlayPlugin, vditorAssets, vue()],
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
