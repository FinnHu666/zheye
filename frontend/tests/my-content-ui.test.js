import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

const browser = new JSDOM('<div id="test-app"></div>', { url: 'http://localhost/', pretendToBeVisual: true })
for (const key of ['window', 'document', 'Document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Event', 'MouseEvent', 'history', 'location']) globalThis[key] = browser.window[key]
globalThis.innerWidth = 1200
globalThis.innerHeight = 900
globalThis.requestAnimationFrame = browser.window.requestAnimationFrame.bind(browser.window)
globalThis.cancelAnimationFrame = browser.window.cancelAnimationFrame.bind(browser.window)
const output = resolve('../target/my-content-ui-tests')
await build({
  configFile: false, plugins: [vue()], logLevel: 'silent',
  build: { outDir: output, emptyOutDir: true, lib: { entry: resolve('tests/my-content-ui-entry.js'), formats: ['es'], fileName: () => 'my-content-ui.mjs' } },
})
const { createApp, nextTick, h, RouterView, createRouter, createMemoryHistory, MyContentPage, currentUser } = await import(new URL('../../target/my-content-ui-tests/my-content-ui.mjs', import.meta.url))
const flush = async () => { for (let i = 0; i < 8; i++) { await new Promise((r) => setTimeout(r, 0)); await nextTick() } }
const buttonByText = (text) => [...document.querySelectorAll('button')].find((e) => e.textContent.replace(/\s+/g, '') === text)

const flow = (id, status, title) => ({ id, type: 'ARTICLE', title, version: 1, level: 'MEDIUM', status, startedAt: '2026-10-20T01:00:00Z', taskId: null, events: [{ id: id * 10, stage: 'SUBMIT', title: '已提交', note: '提交版 v1', detail: '', actor: '', at: '2026-10-20T01:00:00Z' }] })

test('my content shows articles and collections in one page, each with its own flow', async () => {
  const calls = []
  globalThis.fetch = async (url) => {
    calls.push(String(url))
    let value = {}
    if (url.startsWith('/api/review/mine/unpublished')) value = { items: [flow(1, 'AUTO_REJECTED', '被拦截的文章')] }
    else if (url.startsWith('/api/me/posts')) value = { items: [{ id: 11, type: 'ARTICLE', title: '已发布文章', summary: '摘要', likes: 2, createdAt: '2026-10-19T01:00:00Z' }], page: 0, size: 20, hasMore: false }
    else if (url.startsWith('/api/review/mine/content?type=ARTICLE')) value = { items: { 11: [flow(2, 'PUBLISHED', '已发布文章')] } }
    else if (url.startsWith('/api/me/collections?')) value = { items: [{ id: 21, name: '我的专题', description: '说明', ownerName: 'x', itemCount: 3, status: 'PUBLISHED', editorType: 'OUTLINE' }], page: 0, size: 20, hasMore: false }
    else if (url.startsWith('/api/review/mine/content?type=COLLECTION')) value = { items: { 21: [{ ...flow(3, 'IN_REVIEW', '我的专题'), type: 'COLLECTION' }] } }
    else if (url.startsWith('/api/me/collections/selected')) value = { version: 1, items: [{ id: 31, name: '别人的专题', description: '说明', ownerName: '作者', itemCount: 2 }] }
    else if (url.startsWith('/api/collections')) value = { items: [], page: 0, size: 20, total: 0 }
    return { ok: true, status: 200, json: async () => value }
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['collection.manage', 'profile.manage', 'selection.manage'] }
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/me/posts', component: MyContentPage }] })
  await router.push('/me/posts')
  const app = createApp({ render: () => h(RouterView) })
  app.use(router)
  app.mount('#test-app')
  await flush()
  try {
    const text = () => document.body.textContent.replace(/\s+/g, '')
    assert.match(text(), /审核中与未通过/); assert.match(text(), /被拦截的文章/); assert.match(text(), /已发布文章/)
    assert.deepEqual([...document.querySelectorAll('[role=tab]')].map((e) => e.textContent.trim()), ['文章与话题', '我创建的专题', '自选专题'])
    assert.ok(!document.querySelector('.mc-flow'), '流转默认收起')
    buttonByText('审核流转').click(); await flush()
    assert.match(document.querySelector('.mc-flow').textContent, /已提交/)
    buttonByText('我创建的专题').click(); await flush()
    assert.equal(router.currentRoute.value.query.type, 'COLLECTION')
    assert.match(text(), /我的专题/); assert.match(text(), /待审核/)
    buttonByText('审核流转').click(); await flush()
    assert.match(document.querySelector('.mc-flow').textContent, /已提交/)
    assert.ok(calls.some((u) => u.startsWith('/api/review/mine/content?type=COLLECTION&ids=21')))
    buttonByText('自选专题').click(); await flush()
    assert.equal(router.currentRoute.value.query.type, 'SELECTED')
    assert.match(text(), /别人的专题/); assert.match(text(), /发现更多专题/)
    assert.ok(!document.querySelector('.personal-nav a[href="/my/collections"]'), '自选专题不再是个人中心的独立入口')
  } finally { app.unmount() }
})
