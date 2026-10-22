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
const { createApp, nextTick, h, RouterView, createRouter, createMemoryHistory, MyContentPage, ArticleEditorPage, ArticlePreviewPage, currentUser } = await import(new URL('../../target/my-content-ui-tests/my-content-ui.mjs', import.meta.url))
const flush = async () => { for (let i = 0; i < 8; i++) { await new Promise((r) => setTimeout(r, 0)); await nextTick() } }
const buttonByText = (text) => [...document.querySelectorAll('button')].find((e) => e.textContent.replace(/\s+/g, '') === text)
const text = () => document.body.textContent.replace(/\s+/g, '')
const json = (value, status = 200) => ({ ok: status < 400, status, json: async () => value })
const setValue = async (selector, value) => { const el = document.querySelector(selector); el.value = value; el.dispatchEvent(new window.Event('input', { bubbles: true })); await flush() }
const mount = async (routes, path) => {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  const app = createApp({ render: () => h(RouterView) })
  app.use(router); app.mount('#test-app')
  await flush()
  return { app, router }
}

const flow = (id, status, title) => ({ id, type: 'COLLECTION', title, version: 1, level: 'MEDIUM', status, startedAt: '2026-10-20T01:00:00Z', taskId: null, events: [{ id: id * 10, stage: 'SUBMIT', title: '已提交', at: '2026-10-20T01:00:00Z' }] })
const articleRow = (id, over = {}) => ({ id, postId: null, title: `文章${id}`, summary: '摘要', version: 1, status: { publicState: 'NONE', work: 'SAVED' }, updatedAt: '2026-10-20T01:00:00Z', hasPublished: false, publicTitle: null, canCancel: false, canWithdraw: false, canDelete: true, ...over })
const articleView = (id, over = {}) => ({ ...articleRow(id), body: '正文', createdAt: '2026-10-19T01:00:00Z', published: null, submitted: null, flows: [], ...over })
const editorRoutes = [
  { path: '/me/posts', component: MyContentPage },
  { path: '/me/posts/new', component: ArticleEditorPage },
  { path: '/me/posts/:id(\\d+)/edit', component: ArticleEditorPage },
  { path: '/me/posts/:id(\\d+)/preview', component: ArticlePreviewPage },
]

test('my content: article tab lists drafts from /api/me/articles with filters in the address; collections keep their own flow', async () => {
  const calls = []
  globalThis.fetch = async (url, init = {}) => {
    calls.push(`${init.method || 'GET'} ${url}`)
    if (url.startsWith('/api/me/articles?')) return json({ items: [articleRow(1, { title: '我的草稿' }), articleRow(2, { title: '已公开文章', postId: 9, status: { publicState: 'LIVE', work: 'SAVED' }, hasPublished: true, publicTitle: '已公开文章', canWithdraw: true, canDelete: false }), articleRow(3, { title: '审核中文章', status: { publicState: 'NONE', work: 'REVIEWING' }, canCancel: true, canDelete: false })], page: 0, size: 20, total: 3, hasMore: false })
    if (url.startsWith('/api/me/collections?')) return json({ items: [{ id: 21, name: '我的专题', description: '说明', ownerName: 'x', itemCount: 3, status: 'PUBLISHED', editorType: 'OUTLINE' }], page: 0, size: 20, hasMore: false })
    if (url.startsWith('/api/review/mine/content?type=COLLECTION')) return json({ items: { 21: [flow(3, 'IN_REVIEW', '我的专题')] } })
    if (url.startsWith('/api/me/collections/selected')) return json({ version: 1, items: [{ id: 31, name: '别人的专题', description: '说明', ownerName: '作者', itemCount: 2 }] })
    if (url.startsWith('/api/collections')) return json({ items: [], page: 0, size: 20, total: 0 })
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['collection.manage', 'profile.manage', 'selection.manage', 'post.create'] }
  const { app, router } = await mount([{ path: '/me/posts', component: MyContentPage }], '/me/posts')
  try {
    assert.deepEqual([...document.querySelectorAll('[role=tab]')].map((e) => e.textContent.trim()), ['文章', '我创建的专题', '自选专题'])
    assert.ok(calls.includes('GET /api/me/articles?state=ALL&q=&page=0&size=20'))
    assert.ok(!calls.some((u) => u.includes('/api/me/posts') || u.includes('/api/review/mine/unpublished')), '文章页签不再读旧接口')
    assert.match(text(), /我的草稿/); assert.match(text(), /已公开文章/); assert.match(text(), /审核中文章/)
    const rows = [...document.querySelectorAll('.mc-row')]
    const actions = (row) => [...row.querySelectorAll('.mc-actions .btn')].map((e) => e.textContent.replace(/\s+/g, ''))
    assert.deepEqual(actions(rows[0]), ['编辑', '私人预览', '删除草稿'])
    assert.deepEqual(actions(rows[1]), ['编辑', '私人预览', '撤回'])
    assert.deepEqual(actions(rows[2]), ['编辑', '私人预览', '取消送审'])
    assert.ok(document.querySelector('a[href="/me/posts/new"]'), '写文章入口')

    buttonByText('已公开').click(); await flush()
    assert.equal(router.currentRoute.value.query.state, 'LIVE')
    assert.ok(calls.includes('GET /api/me/articles?state=LIVE&q=&page=0&size=20'), '状态筛选由服务端完成')
    await setValue('.ct-search input', '测试')
    document.querySelector('.ct-toolbar').dispatchEvent(new window.Event('submit', { cancelable: true, bubbles: true })); await flush()
    assert.equal(router.currentRoute.value.query.q, '测试')
    assert.ok(calls.some((u) => u.includes('state=LIVE') && u.includes('q=' + encodeURIComponent('测试'))))

    buttonByText('我创建的专题').click(); await flush()
    assert.equal(router.currentRoute.value.query.type, 'COLLECTION')
    assert.match(text(), /我的专题/); assert.match(text(), /待审核/)
    buttonByText('审核流转').click(); await flush()
    assert.match(document.querySelector('.mc-flow').textContent, /已提交/)
    buttonByText('自选专题').click(); await flush()
    assert.equal(router.currentRoute.value.query.type, 'SELECTED')
    assert.match(text(), /别人的专题/)
  } finally { app.unmount() }
})

test('my content: withdraw and delete need a confirmation and call the matching endpoint', async () => {
  const calls = []
  globalThis.fetch = async (url, init = {}) => {
    calls.push(`${init.method || 'GET'} ${url}`)
    if (url.startsWith('/api/me/articles?')) return json({ items: [articleRow(1, { title: '草稿一' }), articleRow(2, { title: '公开一', canWithdraw: true, canDelete: false, status: { publicState: 'LIVE', work: 'SAVED' } })], page: 0, size: 20, total: 2, hasMore: false })
    if (url === '/api/me/articles/2/withdraw') return json({ message: '已撤回，公开入口不再显示', article: articleView(2) })
    if (url === '/api/me/articles/1' && init.method === 'DELETE') return json({ message: '私人草稿已删除。' })
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage', 'post.create'] }
  const { app } = await mount([{ path: '/me/posts', component: MyContentPage }], '/me/posts')
  try {
    buttonByText('撤回').click(); await flush()
    assert.ok(document.querySelector('[role=dialog]'), '撤回前有确认')
    assert.ok(!calls.some((c) => c.includes('/withdraw')), '确认前不发请求')
    buttonByText('确认撤回').click(); await flush()
    assert.ok(calls.includes('POST /api/me/articles/2/withdraw'))
    assert.match(text(), /已撤回，公开入口不再显示/)
    buttonByText('删除草稿').click(); await flush()
    buttonByText('先不').click(); await flush()
    assert.ok(!calls.includes('DELETE /api/me/articles/1'), '取消确认不删除')
    buttonByText('删除草稿').click(); await flush()
    buttonByText('确认删除').click(); await flush()
    assert.ok(calls.includes('DELETE /api/me/articles/1'))
  } finally { app.unmount() }
})

test('editor: publish creates the draft first, a detection outage retries with the same requestId, and leaving with edits asks first', async () => {
  const calls = []
  let publishes = 0
  globalThis.fetch = async (url, init = {}) => {
    const method = init.method || 'GET'
    calls.push({ method, url, body: init.body ? JSON.parse(init.body) : null })
    if (url === '/api/auth/me') return json({ id: 1 })
    if (url === '/api/me/articles' && method === 'POST') return json(articleView(5, { title: '标题', summary: '摘要', body: '正文' }), 201)
    if (url === '/api/me/articles/5' && method === 'GET') return json(articleView(5, { title: '标题', summary: '摘要', body: '正文', version: 2 }))
    if (url === '/api/me/articles/5/publish') {
      publishes++
      if (publishes === 1) return json({ status: 503, code: 'DETECTION_UNAVAILABLE', message: '检测暂时不可用' }, 503)
      return json({ outcome: 'PUBLISHED', level: 'NONE', message: '已发布', replay: false, flowId: 1, taskId: null, article: articleView(5, { title: '标题', summary: '摘要', body: '正文', version: 2, postId: 9, status: { publicState: 'LIVE', work: 'SAVED' }, canWithdraw: true, canDelete: false, published: { title: '标题', summary: '摘要', body: '正文', version: 2, updatedAt: null } }) })
    }
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage', 'post.create'] }
  const { app, router } = await mount(editorRoutes, '/me/posts/new')
  try {
    assert.ok(buttonByText('保存草稿').disabled, '空稿不能保存')
    await setValue('[data-field=title]', '标题'); await setValue('[data-field=summary]', '摘要'); await setValue('[data-field=body]', '正文')
    assert.match(text(), /2\/120/, '长度按字符计')
    buttonByText('发布').click(); await flush()
    assert.equal(calls.filter((c) => c.method === 'POST' && c.url === '/api/me/articles').length, 1, '发布前先建稿')
    const first = calls.find((c) => c.url === '/api/me/articles/5/publish')
    assert.equal(first.body.expectedVersion, 1); assert.ok(first.body.requestId)
    assert.match(text(), /检测暂时不可用/); assert.match(text(), /输入已保留/)
    buttonByText('发布').click(); await flush()
    const second = calls.filter((c) => c.url === '/api/me/articles/5/publish')[1]
    assert.equal(second.body.requestId, first.body.requestId, '重试沿用同一 requestId')
    assert.equal(second.body.expectedVersion, 2, '重试前刷新到服务端版本')
    assert.match(text(), /已发布/); assert.match(text(), /公开版v2/)

    await setValue('[data-field=body]', '正文改了')
    const stayed = router.push('/me/posts'); await flush()
    assert.ok(document.querySelector('.ct-modal'), '有未保存修改时先询问')
    buttonByText('继续编辑').click(); await flush()
    assert.ok(await stayed, '选择继续编辑后导航被取消')
    assert.equal(router.currentRoute.value.path, '/me/posts/5/edit')
    const left = router.push('/me/posts'); await flush()
    buttonByText('放弃修改').click(); await flush(); await left
    assert.equal(router.currentRoute.value.path, '/me/posts')
  } finally { app.unmount() }
})

test('editor: a version conflict keeps the input and offers both ways out', async () => {
  let saves = 0
  globalThis.fetch = async (url, init = {}) => {
    const method = init.method || 'GET'
    if (url === '/api/auth/me') return json({ id: 1 })
    if (url === '/api/me/articles/7' && method === 'GET') return json(articleView(7, { title: '别处标题', version: 4 }))
    if (url === '/api/me/articles/7' && method === 'PUT') { saves++; return json({ status: 409, code: 'VERSION_CONFLICT', message: '这份草稿已在别处更新到 v4', currentVersion: 4 }, 409) }
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage', 'post.create'] }
  const { app } = await mount(editorRoutes, '/me/posts/7/edit')
  try {
    await setValue('[data-field=title]', '我的标题'); buttonByText('保存草稿').click(); await flush()
    assert.equal(saves, 1)
    assert.equal(document.querySelector('[data-field=title]').value, '我的标题', '冲突后输入保留')
    assert.match(text(), /以我的为准/); assert.match(text(), /载入别处的版本/)
    buttonByText('载入别处的版本（放弃我的输入）').click(); await flush()
    assert.equal(document.querySelector('[data-field=title]').value, '别处标题')
  } finally { app.unmount() }
})

test('editor: a 401 keeps the input, shows the re-login panel and does not save', async () => {
  const calls = []
  globalThis.fetch = async (url, init = {}) => {
    calls.push(`${init.method || 'GET'} ${url}`)
    if (url === '/api/auth/me') return json({ status: 401, message: '请先登录' }, 401)
    if (url === '/api/me/articles/9' && !init.method) return json(articleView(9, { title: '原标题' }))
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage', 'post.create'] }
  const { app } = await mount(editorRoutes, '/me/posts/9/edit')
  try {
    await setValue('[data-field=title]', '改过的标题'); buttonByText('保存草稿').click(); await flush()
    assert.ok(document.querySelector('.account-reauth'), '就地重新登录面板')
    assert.equal(document.querySelector('[data-field=title]').value, '改过的标题', '输入保留')
    assert.ok(!calls.includes('PUT /api/me/articles/9'), '登录失效时不代存')
    assert.ok(buttonByText('保存草稿').disabled || buttonByText('发布').disabled)
  } finally { app.unmount() }
})

test('preview and public reading share the safe renderer: HTML shows as text, images do not load, unsafe links are not clickable', async () => {
  const source = '# 标题\n<script>alert(1)</script>\n![图](http://x/y.png)\n[坏](javascript:alert(1)) [好](https://example.com) **粗**'
  globalThis.fetch = async (url) => url === '/api/me/articles/8' ? json(articleView(8, { title: '预览', body: source })) : json({})
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage'] }
  const { app } = await mount(editorRoutes, '/me/posts/8/preview')
  try {
    const md = document.querySelector('.ct-md')
    assert.ok(md && !md.querySelector('script') && !md.querySelector('img'), '无脚本、无图片元素')
    assert.match(md.textContent, /<script>alert\(1\)<\/script>/, '脚本原样显示为文字')
    const links = [...md.querySelectorAll('a')]
    assert.deepEqual(links.map((a) => a.getAttribute('href')), ['https://example.com'])
    assert.match(links[0].getAttribute('rel'), /noopener/)
    assert.ok(md.querySelector('.ct-md-blocked')); assert.ok(md.querySelector('.ct-md-image'))
  } finally { app.unmount() }
})

test('my content: direct load restores filters from the address, back/forward follow, and empty / failed / loading states render', async () => {
  const calls = []
  let mode = 'ok', release
  globalThis.fetch = async (url, init = {}) => {
    calls.push(url)
    if (url.startsWith('/api/me/articles?')) {
      if (mode === 'slow') await new Promise((r) => { release = r })
      if (mode === 'fail') return json({ status: 500, message: '服务暂时不可用' }, 500)
      if (mode === 'empty' || url.includes('q=none')) return json({ items: [], page: 0, size: 20, total: 0, hasMore: false })
      return json({ items: [articleRow(1, { title: '甲' })], page: 0, size: 20, total: 1, hasMore: false })
    }
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage', 'post.create'] }
  mode = 'slow'
  const { app, router } = await mount([{ path: '/me/posts', component: MyContentPage }], '/me/posts?state=REVIEW&q=none&page=0')
  try {
    assert.match(text(), /正在加载内容/, '加载态')
    assert.ok(calls.includes('/api/me/articles?state=REVIEW&q=none&page=0&size=20'), '直达地址带出筛选')
    release(); mode = 'ok'; await flush()
    assert.equal(document.querySelector('.ct-search input').value, 'none', '搜索框回填')
    assert.equal(document.querySelector('.ct-chips .on').textContent.trim(), '审核相关')
    assert.match(text(), /没有符合条件的文章/, '筛选后为空')
    buttonByText('清除筛选').click(); await flush()
    assert.equal(router.currentRoute.value.fullPath, '/me/posts')
    assert.match(text(), /甲/)
    buttonByText('已公开').click(); await flush()
    assert.equal(router.currentRoute.value.query.state, 'LIVE')
    router.back(); await flush()
    assert.equal(router.currentRoute.value.fullPath, '/me/posts')
    assert.equal(document.querySelector('.ct-chips .on').textContent.trim(), '全部', '后退恢复筛选')
    router.forward(); await flush()
    assert.equal(document.querySelector('.ct-chips .on').textContent.trim(), '已公开', '前进恢复筛选')
    mode = 'fail'; buttonByText('全部').click(); await flush()
    assert.match(text(), /服务暂时不可用/); assert.ok(buttonByText('重试'), '失败可重试')
    mode = 'empty'; buttonByText('重试').click(); await flush()
    assert.match(text(), /还没有文章|没有符合条件/)
  } finally { app.unmount() }
})

test('editor: direct load of a missing article shows a clear failure; a draft created by publish survives a refresh at its own address', async () => {
  globalThis.fetch = async (url, init = {}) => {
    if (url === '/api/me/articles/404') return json({ status: 404, message: '没有找到' }, 404)
    return json({})
  }
  document.cookie = 'XSRF-TOKEN=test'
  currentUser.value = { id: 1, permissions: ['profile.manage', 'post.create'] }
  const { app } = await mount(editorRoutes, '/me/posts/404/edit')
  try {
    assert.match(text(), /无法打开这篇文章/); assert.match(text(), /可能已被删除/)
    assert.ok(document.querySelector('a[href="/me/posts"]'), '可回到列表')
  } finally { app.unmount() }
})
