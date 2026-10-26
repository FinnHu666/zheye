import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve, join, normalize, extname, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { copyFileSync, createReadStream, existsSync, mkdirSync } from 'node:fs'

const projectRoot = fileURLToPath(new URL('.', import.meta.url))

// Vditor 自托管（契约 CONTRACT_CONTENT_IMAGES §3.1）：只提供白名单资源，不访问外部 CDN。
// dev 由中间件在 /vendor/vditor 提供；build 复制到输出目录的 vendor/vditor。
export const VDITOR_ASSETS = ['dist/js/lute/lute.min.js', 'dist/js/i18n/zh_CN.js', 'dist/js/icons/ant.js', 'dist/css/content-theme/light.css', 'dist/js/highlight.js/styles/github.min.css']
const vditorRoot = resolve(projectRoot, 'node_modules/vditor')
const types = { '.js': 'text/javascript', '.css': 'text/css' }
const vditorAssets = () => {
  let outDir
  return {
    name: 'finns-vditor-assets',
    configResolved(config) { outDir = config.build.outDir },
    configureServer(server) {
      server.middlewares.use('/vendor/vditor', (req, res, next) => {
        const rel = normalize(decodeURIComponent(req.url.split('?')[0])).replace(/^[\\/]+/, '').replace(/\\/g, '/')
        if (!VDITOR_ASSETS.includes(rel)) return next()
        res.setHeader('Content-Type', types[extname(rel)] || 'application/octet-stream'); createReadStream(join(vditorRoot, rel)).pipe(res)
      })
    },
    closeBundle() {
      for (const rel of VDITOR_ASSETS) {
        const from = join(vditorRoot, rel), to = join(outDir, 'vendor/vditor', rel)
        if (!existsSync(from)) throw new Error(`缺少 Vditor 资源：${rel}`)
        mkdirSync(dirname(to), { recursive: true }); copyFileSync(from, to)
      }
    },
  }
}

export default defineConfig({
  plugins: [vue(), vditorAssets()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  build: {
    outDir: resolve(projectRoot, '../src/main/resources/static'),
    emptyOutDir: true,
  },
})
