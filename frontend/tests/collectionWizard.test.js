import test from 'node:test'
import assert from 'node:assert/strict'
import { addTag, applyLayouts, basicIssues, clampLayout, collectLayouts, loadLayouts, saveLayouts, stackLayouts, stageHeight, checkCover, completeness, defaultExtras, joinAudience, loadExtras, parseAudience, parseGoal, saveExtras, summarize, wizardIssues } from '../src/lib/collectionWizard.js'

const entry = (over = {}) => ({ kind: 'NOTE', title: '', body: '正文', annotation: '', externalUrl: '', postId: null, ...over })
const draft = (over = {}) => ({ name: 'AI 学习', description: '简介', audience: '学生、开发者', goal: '知识普及', sections: [{ title: '第一章', description: '', entries: [entry()] }], ...over })

test('适用人群按固定顺序拼接并能还原，未知文字被忽略', () => {
  assert.equal(joinAudience(['开发者', '学生']), '学生、开发者')
  assert.deepEqual(parseAudience('学生、开发者、不存在'), ['学生', '开发者'])
  assert.deepEqual(parseAudience(''), [])
})

test('发布目标只认预设项', () => {
  assert.equal(parseGoal(' 技能提升 '), '技能提升')
  assert.equal(parseGoal('自己写的目标'), '')
})

test('基础信息：标题、简介、人群、目标缺一不可，历史自由文本人群视为已填', () => {
  assert.equal(basicIssues(draft()).length, 0)
  assert.deepEqual(basicIssues(draft({ name: ' ', description: '', audience: '', goal: '' })).map(i => i.field), ['name', 'description', 'audience', 'goal'])
  assert.equal(basicIssues(draft({ audience: '想入门的朋友' })).length, 0)
})

test('发布问题：基础信息归第一步，内容问题归第二步且带定位', () => {
  const issues = wizardIssues(draft({ description: '', sections: [{ title: '章', description: '', entries: [entry({ body: ' ' }), entry({ kind: 'LINK', title: '链接', externalUrl: 'ftp://x' })] }] }))
  assert.deepEqual(issues.map(i => [i.step, i.field]), [['basic', 'description'], ['content', 'body'], ['content', 'externalUrl']])
  assert.equal(issues[1].section, 0); assert.equal(issues[1].entry, 0)
  assert.equal(wizardIssues(draft()).length, 0)
})

test('名称缺失只报一次，不会在第二步重复', () => {
  assert.equal(wizardIssues(draft({ name: '' })).filter(i => i.field === 'name').length, 1)
})

test('内容完整度与章节统计', () => {
  assert.deepEqual(summarize(draft()), { sections: 1, entries: 1 })
  assert.equal(completeness(draft()).percent, 100)
  assert.equal(completeness(draft({ description: '', sections: [] })).percent, 25)
})

test('封面校验：格式与 5MB 上限', () => {
  assert.match(checkCover(null), /选择/)
  assert.match(checkCover({ type: 'image/gif', size: 10 }), /JPG、PNG/)
  assert.match(checkCover({ type: 'image/png', size: 3 * 1024 * 1024 }), /2MB/)
  assert.equal(checkCover({ type: 'image/jpeg', size: 1024 }), '')
})

test('标签归一化去重（NFKC、空白折叠、大小写）并限制数量与长度', () => {
  assert.deepEqual(addTag(['a'], ' a '), ['a'])
  assert.deepEqual(addTag(['AI'], 'ai'), ['AI'])
  assert.deepEqual(addTag([], 'ＡＩ  工具'), ['AI 工具'])
  assert.deepEqual(addTag(['a'], ' '), ['a'])
  assert.deepEqual(addTag([], '一二三四五六七八九十一二三'), ['一二三四五六七八九十一二'])
  assert.equal(addTag(Array.from({ length: 6 }, (_, i) => `t${i}`), 'new').length, 6)
})

test('引子字段暂存：往返、坏数据和存储不可用都不抛错', () => {
  const store = new Map(); const storage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, v) }
  const extras = { ...defaultExtras(), tags: ['AI'], category: 'AI 与大模型', seoTitle: 'SEO' }
  saveExtras(7, extras, storage)
  assert.deepEqual(loadExtras(7, storage), { ...defaultExtras(), tags: ['AI'], category: 'AI 与大模型', seoTitle: 'SEO' })
  store.set('finns.collection-wizard.8', '{坏的')
  assert.deepEqual(loadExtras(8, storage), defaultExtras())
  store.set('finns.collection-wizard.9', JSON.stringify({ category: '不存在', tags: [1, 'ok'] }))
  assert.deepEqual(loadExtras(9, storage).tags, ['ok']); assert.equal(loadExtras(9, storage).category, '')
  const broken = { getItem() { throw new Error('denied') }, setItem() { throw new Error('denied') } }
  assert.deepEqual(loadExtras(1, broken), defaultExtras()); saveExtras(1, extras, broken)
  assert.deepEqual(loadExtras(1, undefined), defaultExtras())
})

test('画布布局：数值被限制在安全范围内，宽度不小于20%且不越界', () => {
  assert.deepEqual(clampLayout({ x: 95, y: -5, w: 50, h: 99999 }), { x: 50, y: 0, w: 50, h: 1600 })
  assert.deepEqual(clampLayout({ x: 3, y: 12.6, w: 5, h: 10 }), { x: 3, y: 13, w: 20, h: 80 })
  assert.deepEqual(clampLayout({}), { x: 3, y: 0, w: 94, h: 160 })
  assert.deepEqual(clampLayout({ x: 'a', y: '', w: null, h: NaN }), { x: 3, y: 0, w: 94, h: 160 })
})

test('没有手动摆放的模块按阅读顺序自上而下排开，舞台高度随最低的模块增长', () => {
  const stack = stackLayouts(['intro', 'NOTE', 'LINK'])
  assert.deepEqual(stack.map(l => l.y), [0, 166, 392])
  assert.equal(stageHeight(stack), 392 + 120 + 60)
  assert.equal(stageHeight([]), 380)
})

test('布局随条目收集与恢复；条目数量变化时不套用旧位置', () => {
  const sections = [{ entries: [{ kind: 'NOTE', layout: { x: 10, y: 20, w: 40, h: 100 } }, { kind: 'LINK' }] }, { entries: [{ kind: 'POST', layout: { x: 0, y: 500, w: 60, h: 200 } }] }]
  const saved = collectLayouts(sections, { x: 5, y: 0, w: 50, h: 150 })
  assert.deepEqual(saved.entries, [{ x: 10, y: 20, w: 40, h: 100 }, null, { x: 0, y: 500, w: 60, h: 200 }])
  const fresh = [{ entries: [{ kind: 'NOTE' }, { kind: 'LINK' }] }, { entries: [{ kind: 'POST' }] }]
  assert.equal(applyLayouts(fresh, saved), true)
  assert.deepEqual(fresh[0].entries[0].layout, { x: 10, y: 20, w: 40, h: 100 }); assert.equal(fresh[0].entries[1].layout, undefined)
  const changed = [{ entries: [{ kind: 'NOTE' }] }]
  assert.equal(applyLayouts(changed, saved), false); assert.equal(changed[0].entries[0].layout, undefined)
})

test('布局暂存：往返、坏数据与存储不可用都不抛错', () => {
  const store = new Map(); const storage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, v) }
  saveLayouts(3, { intro: null, entries: [null] }, storage)
  assert.deepEqual(loadLayouts(3, storage), { intro: null, entries: [null] })
  store.set('finns.collection-wizard.layout.4', '{坏的'); assert.equal(loadLayouts(4, storage), null)
  const broken = { getItem() { throw new Error('x') }, setItem() { throw new Error('x') } }
  assert.equal(loadLayouts(1, broken), null); saveLayouts(1, {}, broken)
})

test('画布布局：层级 z 被保留并限制范围，没有层级时不出现 z 字段', () => {
  assert.deepEqual(clampLayout({ x: 3, y: 0, w: 60, h: 100, z: 4 }), { x: 3, y: 0, w: 60, h: 100, z: 4 })
  assert.equal(clampLayout({ z: 5000 }).z, 999); assert.equal(clampLayout({ z: -3 }).z, 0)
  for (const none of [undefined, null, '', 'abc']) assert.equal('z' in clampLayout({ x: 3, y: 0, w: 60, h: 100, z: none }), false)
  const saved = collectLayouts([{ entries: [{ layout: { x: 3, y: 0, w: 60, h: 100, z: 2 } }] }], null)
  const fresh = [{ entries: [{}] }]; applyLayouts(fresh, saved); assert.equal(fresh[0].entries[0].layout.z, 2)
})
