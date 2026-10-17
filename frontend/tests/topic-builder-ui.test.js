import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'
const browser = new JSDOM('<div id="test-app"></div>', {
  url: 'http://localhost/',
  pretendToBeVisual: true,
})
for (const key of [
  'window',
  'document',
  'Document',
  'Element',
  'HTMLElement',
  'SVGElement',
  'Node',
  'Event',
  'MouseEvent',
  'history',
  'location',
])
  globalThis[key] = browser.window[key]
globalThis.innerWidth = 1200
globalThis.innerHeight = 900
globalThis.requestAnimationFrame = browser.window.requestAnimationFrame.bind(
  browser.window,
)
globalThis.cancelAnimationFrame = browser.window.cancelAnimationFrame.bind(
  browser.window,
)
browser.window.HTMLElement.prototype.scrollIntoView = function () {}
// jsdom has no native dialog top layer; browser acceptance covers the real modal.
browser.window.HTMLDialogElement.prototype.showModal = function () { this.open = true }
browser.window.HTMLDialogElement.prototype.close = function () { this.open = false }
const output = resolve('../target/topic-builder-ui-tests')
await build({
  configFile: false,
  plugins: [vue()],
  logLevel: 'silent',
  build: {
    outDir: output,
    emptyOutDir: true,
    lib: {
      entry: resolve('tests/topic-builder-ui-entry.js'),
      formats: ['es'],
      fileName: () => 'builder-ui.mjs',
    },
  },
})
const {
  createApp,
  nextTick,
  h,
  RouterView,
  createRouter,
  createMemoryHistory,
  TopicBuilderPage,
  PersonalPage,
  AiSettingsPage,
} = await import(
  new URL('../../target/topic-builder-ui-tests/builder-ui.mjs', import.meta.url)
)
const form = {
  topic: '租房案例',
  audience: '学生',
  purpose: '避坑',
  goal: '',
  style: 'guide',
  density: 'comfortable',
  styleNote: '',
  source: '固定资料',
  constraints: '',
  context: '',
}
const doc = () => ({
  schemaVersion: 1,
  form: { ...form },
  confirmedForm: { ...form },
  blocks: [
    { id: crypto.randomUUID(), kind: 'steps', title: '原模块', body: '1. 记录预算。\n2. 记录租期。\n2026 年核对。' },
  ],
  layoutMode: 'flow',
  withoutSource: false,
})
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await new Promise((r) => setTimeout(r, 0))
    await nextTick()
  }
}
const button = (text) =>
  [...document.querySelectorAll('button')].find(
    (e) => e.textContent.replace(/\s+/g, '') === text.replace(/\s+/g, '') || e.getAttribute('aria-label') === text,
  )
const click = async (text) => {
  const b = button(text)
  assert.ok(b, 'button ' + text)
  b.click()
  await flush()
}
const input = (selector, value) => {
  const e = document.querySelector(selector)
  assert.ok(e, selector)
  e.value = value
  e.dispatchEvent(new Event('input', { bubbles: true }))
}

test('actual Vue workbench protects navigation and preserves edits across first-save route replacement', async () => {
  let stored = { id: 7, revision: 0, document: doc() },
    releaseSave,
    holdSave = false
  globalThis.fetch = async (url, options = {}) => {
    let value = {}
    const body = options.body ? JSON.parse(options.body) : null
    if (url.endsWith('/capabilities')) value = { aiAvailable: true }
    else if (url.endsWith('/generate')) {
      value = { document: doc() }
      value.document.form = body.form
      value.document.confirmedForm = { ...body.form }
    } else if (url.endsWith('/drafts')) {
      stored = {
        id: 7,
        revision: 0,
        document: JSON.parse(JSON.stringify(body.document)),
      }
      if (holdSave) await new Promise((r) => (releaseSave = r))
      value = stored
    } else if (url.endsWith('/builder-document')) {
      if (options.method === 'PUT')
        stored = {
          id: 7,
          revision: stored.revision + 1,
          document: body.document,
        }
      value = stored
    }
    return { ok: true, status: 200, json: async () => value }
  }
  document.cookie = 'XSRF-TOKEN=test'
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/new', component: TopicBuilderPage },
      { path: '/me/collections/ai/new', component: TopicBuilderPage },
      { path: '/me/collections/:id/builder', component: TopicBuilderPage },
      {
        path: '/me/collections/:id/builder/preview',
        component: TopicBuilderPage,
        meta: { builderPreview: true },
      },
      {
        path: '/me/collections',
        component: { render: () => h('p', '专题列表') },
      },
    ],
  })
  await router.push('/me/collections/ai/new')
  const app = createApp({ render: () => h(RouterView) })
  app.use(router)
  app.mount('#test-app')
  await flush()
  try {
    await click('直接填写创建问卷 →')
    input('#topic-field', '租房案例')
    input('#audience-field', '学生')
    input('#purpose-field', '避坑')
    await flush()
    await click('4 需求摘要')
    await click('确认并生成页面 →')
    assert.ok(button('保存私人草稿'))
    assert.deepEqual([...document.querySelectorAll('.content-steps li')].map(e => e.textContent.trim()), ['记录预算。', '记录租期。', '2026 年核对。'])
    holdSave = true
    button('保存私人草稿').click()
    await flush()
    input('.block-settings input', '保存期间编辑')
    await flush()
    releaseSave()
    await flush()
    assert.equal(router.currentRoute.value.path, '/me/collections/7/builder')
    assert.equal(stored.document.blocks[0].body, '1. 记录预算。\n2. 记录租期。\n2026 年核对。')
    assert.equal(
      document.querySelector('.block-settings input').value,
      '保存期间编辑',
    )
    assert.match(document.querySelector('.save-state').textContent, /未保存/)
    await click('＋ 开始一个专题')
    assert.ok(document.querySelector('.confirm-dialog'))
    await click('继续编辑')
    assert.equal(router.currentRoute.value.path, '/me/collections/7/builder')
    holdSave = false
    await click('保存私人草稿')
    await click('＋ 开始一个专题')
    assert.equal(router.currentRoute.value.path, '/me/collections/ai/new')
    assert.equal(document.querySelector('#chat-input').value, '')
    assert.equal(button('保存私人草稿'), undefined)
    await router.push('/me/collections/7/builder/preview')
    await flush()
    assert.equal(button('保存私人草稿'), undefined)
    assert.ok(
      document
        .querySelector('.topic-block h3')
        .textContent.includes('保存期间编辑'),
    )
    document.querySelector('a[href="/me/collections/7/builder"]').click()
    await flush()
    assert.ok(button('保存私人草稿'))
    assert.ok(document.querySelector('.block-settings'))
    router.back()
    await flush()
    assert.equal(button('保存私人草稿'), undefined)
    router.forward()
    await flush()
    assert.ok(button('保存私人草稿'))
    await click('修改创建需求')
    await click('4 需求摘要')
    await click('确认并生成页面 →')
    assert.ok(button('采用新初稿'))
    assert.equal(
      document.querySelector('.regeneration-review h3').textContent,
      '原模块',
    )
    await click('保留原稿')
    assert.equal(
      document.querySelector('.topic-block h3').textContent,
      '保存期间编辑',
    )
    await click('修改创建需求')
    await click('4 需求摘要')
    await click('确认并生成页面 →')
    await click('采用新初稿')
    assert.equal(
      document.querySelector('.topic-block h3').textContent,
      '原模块',
    )
    assert.equal(stored.document.blocks[0].title, '保存期间编辑')
    await click('＋ 开始一个专题')
    await click('放弃未保存修改')
    await click('直接填写创建问卷 →')
    input('#topic-field', '第二份')
    input('#audience-field', '新手')
    input('#purpose-field', '学习')
    await flush()
    await click('4 需求摘要')
    await click('确认并生成页面 →')
    document.querySelector('a[href="/me/collections"]').click()
    await flush()
    assert.ok(document.querySelector('.confirm-dialog'))
    await click('保存并继续')
    assert.equal(router.currentRoute.value.path, '/me/collections')
    await router.push('/me/collections/7/builder')
    await flush()
    await click('〈/〉 导入 HTML 模块')
    input('.code-source-input', '<p>尚未应用的模块源码</p>')
    await flush()
    const unload = new browser.window.Event('beforeunload', { cancelable: true })
    browser.window.dispatchEvent(unload)
    assert.equal(unload.defaultPrevented, true)
    await click('关闭代码导入')
    const cleanUnload = new browser.window.Event('beforeunload', { cancelable: true })
    browser.window.dispatchEvent(cleanUnload)
    assert.equal(cleanUnload.defaultPrevented, false)
    await click('修改创建需求')
    input('.questionnaire textarea[maxlength="300"]', '尚未生成的新目标')
    await click('关闭创建问卷')
    document.querySelector('a[href="/me/collections"]').click()
    await flush()
    await click('保存并继续')
    assert.equal(router.currentRoute.value.path, '/me/collections/7/builder')
    assert.match(document.querySelector('.confirm-dialog').textContent, /问卷调整尚未生成/)
    await click('放弃未保存修改')
    assert.equal(router.currentRoute.value.path, '/me/collections')
  } finally {
    app.unmount()

  }
})


test('profile saves only changes and preserves concurrent edits and failures', async () => {
  let persisted = { displayName:'原昵称',bio:'原简介',email:'profile@test.dev',createdAt:new Date().toISOString() }, release, fail=false
  globalThis.fetch = async (url, options={}) => {
    if (options.method === 'PATCH') {
      const snapshot = JSON.parse(options.body)
      await new Promise(r => release=r)
      if (fail) return {ok:false,status:500,json:async()=>({message:'保存失败'})}
      persisted={...persisted,...snapshot}
    }
    return {ok:true,status:200,json:async()=>({...persisted})}
  }
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/me',component:PersonalPage}]})
  await router.push('/me')
  const app=createApp(PersonalPage);app.use(router);app.mount('#test-app');await flush()
  try {
    assert.equal(button('保存资料'),undefined)
    input('input[maxlength="40"]','新昵称');await flush();assert.ok(button('保存资料'))
    input('input[maxlength="40"]','原昵称');await flush();assert.equal(button('保存资料'),undefined)
    input('input[maxlength="40"]','提交昵称');await flush();document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush()
    input('textarea','保存期间继续编辑');release();await flush()
    assert.equal(document.querySelector('textarea').value,'保存期间继续编辑');assert.ok(button('保存资料'))
    fail=true;document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush();release();await flush()
    assert.equal(document.querySelector('textarea').value,'保存期间继续编辑');assert.match(document.querySelector('[role=alert]').textContent,/保存失败/)
  } finally {app.unmount()}
})

test('AI settings clears submitted key and exposes metadata only without model calls', async () => {
  let status={configured:false,lastFour:'',storageAvailable:true}, calls=[]
  globalThis.fetch=async(url,options={})=>{
    calls.push(url)
    if(options.method==='PUT')status={...status,configured:true,lastFour:'1234'}
    if(options.method==='DELETE')status={...status,configured:false,lastFour:''}
    return {ok:true,status:200,json:async()=>status}
  }
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/me/ai-settings',component:AiSettingsPage}]});await router.push('/me/ai-settings')
  const app=createApp(AiSettingsPage);app.use(router);app.mount('#test-app');await flush()
  try {
    input('input[type=password]','test-only-secret-1234');await flush();document.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush()
    assert.equal(document.querySelector('input').value,'');assert.match(document.querySelector('.key-status').textContent,/1234/);assert.ok(!document.body.textContent.includes('test-only-secret'))
    assert.ok(calls.every(url=>url==='/api/me/ai-settings/deepseek'))
    await click('删除 Key');await click('确认删除');assert.match(document.querySelector('.key-status').textContent,/尚未配置/)
  } finally {app.unmount()}
})

test('editable notes survive save and preview; picker copies all kinds with new identities', async () => {
  const original=doc();original.generation={warnings:['模型原始提示']};let stored={id:99,revision:0,document:original},favorites=[]
  const snapshots=['text','steps','question','html'].map(kind=>({id:crypto.randomUUID(),kind,title:kind+' 来源模块',body:'来源正文',...(kind==='html'?{code:{rendererVersion:1,source:'<p>时间轴</p>',frameHeight:360}}:{})}))
  globalThis.fetch=async(url,options={})=>{
    let value={};const payload=options.body?JSON.parse(options.body):null
    if(url.endsWith('/capabilities'))value={aiAvailable:true}
    else if(url.includes('module-favorites')){
      if(options.method==='POST'){const item={id:crypto.randomUUID(),block:structuredClone(payload.block)};favorites.push(item);value=item}
      else if(options.method==='DELETE'){favorites=[];value={deleted:true}}
      else value={items:structuredClone(favorites),hasNext:false}
    } else if(url.includes('editorType=BUILDER'))value={items:[{id:12,name:'来源专题',itemCount:4}],hasMore:!url.includes('page=1')}
    else if(url.includes('/12/builder-document'))value={document:{blocks:snapshots}}
    else if(url.endsWith('/builder-document')){
      if(options.method==='PUT')stored={...stored,revision:stored.revision+1,document:structuredClone(payload.document)}
      value=structuredClone(stored)
    }
    return {ok:true,status:200,json:async()=>value}
  }
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/me/collections/:id/builder',component:TopicBuilderPage}]});await router.push('/me/collections/99/builder')
  const app=createApp(TopicBuilderPage);app.use(router);const vm=app.mount('#test-app');await flush()
  try {
    assert.equal(vm.isDirty,false);input('textarea[aria-label="核验提示 1"]','作者核验后内容');await flush()
    await click('＋ 添加提示');input('textarea[aria-label="核验提示 2"]','新增提示');await flush();await click('删除核验提示 2')
    await click('保存私人草稿');assert.deepEqual(stored.document.reviewNotes,['作者核验后内容']);assert.deepEqual(stored.document.generation.warnings,['模型原始提示'])
    vm.previewMode='read';await flush();assert.match(document.body.textContent,/作者核验后内容/);assert.ok(!document.body.textContent.includes('模型原始提示'));vm.previewMode='edit';await flush()
    for(const kind of ['text','steps','question','html']){
      await click('＋ 添加内容区块');await click('本人专题');if(kind==='text'){await click('下一页');assert.match(document.querySelector('.module-picker footer').textContent,/第 2 页/);await click('上一页')}document.querySelector('.module-picker .picker-card').click();await flush()
      const card=[...document.querySelectorAll('.module-picker .picker-card')].find(e=>e.textContent.includes(kind+' 来源模块'));assert.ok(card);card.click();await flush()
      const inserted=vm.blocks.at(-1);assert.notEqual(inserted.id,snapshots.find(b=>b.kind===kind).id);assert.equal(inserted.kind,kind)
      await click('收藏此模块');inserted.body='独立编辑';await flush();assert.equal(favorites.at(-1).block.body,'来源正文')
    }
    assert.equal(snapshots[0].body,'来源正文');await click('＋ 添加内容区块');await click('我的收藏');await click('插入模块');assert.equal(vm.blocks.at(-1).body,'来源正文')
    await click('＋ 添加内容区块');await click('模块市场');assert.match(document.querySelector('.module-picker').textContent,/示例／暂未开放/);assert.equal(document.querySelectorAll('.market-example button').length,0);await click('关闭模块选择')
    await click('保存私人草稿');await vm.loadRoute(router.currentRoute.value);await flush();assert.equal(vm.blocks.length,6);assert.deepEqual(vm.reviewNotes,['作者核验后内容'])
    vm.applyDocument({...original,blocks:[]});assert.deepEqual(vm.reviewNotes,['模型原始提示'])
    vm.candidate={...original,generation:{warnings:['新模型提示']}};vm.keepOriginal();assert.deepEqual(vm.reviewNotes,['模型原始提示'])
    vm.candidate={...original,generation:{warnings:['新模型提示']}};vm.acceptCandidate();assert.deepEqual(vm.reviewNotes,['新模型提示'])
  } finally {app.unmount()}
})
