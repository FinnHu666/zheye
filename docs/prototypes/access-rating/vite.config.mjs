import { defineConfig } from '../../../frontend/node_modules/vite/dist/node/index.js'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  resolve: { alias: { vue: fileURLToPath(new URL('../../../frontend/node_modules/vue/dist/vue.esm-bundler.js', import.meta.url)) } },
  define: { __VUE_OPTIONS_API__: true, __VUE_PROD_DEVTOOLS__: false, __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: false },
  server: { host: '127.0.0.1', port: 4174, strictPort: true },
  build: { outDir: fileURLToPath(new URL('../../../target/prototypes/access-rating', import.meta.url)), emptyOutDir: true }
})
