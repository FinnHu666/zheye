import test from 'node:test'
import assert from 'node:assert/strict'
import { MAX_LIBRARY, clampHeight, importIssue, loadLibrary, loadPlaced, normalizeModules, saveLibrary, savePlaced } from '../src/lib/collectionHtmlModules.js'

const ok = () => {}
const bad = source => { if (source.includes('<script')) throw new Error('不支持脚本') }
const memory = () => { const m = new Map(); return { getItem: k => m.get(k) ?? null, setItem: (k, v) => m.set(k, v), raw: m } }

test('整理读出的模块：坏数据、空名称、检查不通过的都丢掉，高度被限制', () => {
  const list = normalizeModules([
    { id: 'a', name: ' 时间轴 ', source: '<p>x</p>', height: 5000 },
    { id: 'b', name: '', source: '<p>x</p>' },
    { id: 'c', name: '脚本', source: '<script>1</script>' },
    { name: '没有 id', source: '<p>y</p>', height: 10 },
    null, 'x', { name: 3, source: '<p>' }
  ], bad)
  assert.deepEqual(list.map(m => [m.name, m.height]), [['时间轴', 1200], ['没有 id', 120]])
  assert.ok(list[1].id.startsWith('h'))
  assert.deepEqual(normalizeModules('坏', ok), [])
})

test('数量上限：我的模块库不超过上限', () => {
  const many = Array.from({ length: MAX_LIBRARY + 5 }, (_, i) => ({ id: `i${i}`, name: `m${i}`, source: '<p>x</p>' }))
  assert.equal(normalizeModules(many, ok).length, MAX_LIBRARY)
})

test('模块库与画布上已放置的模块可往返暂存，且读出时重新检查', () => {
  const storage = memory()
  assert.equal(saveLibrary([{ id: 'a', name: '甲', source: '<p>x</p>', height: 300, extra: '忽略' }], storage), true)
  assert.deepEqual(loadLibrary(ok, storage), [{ id: 'a', name: '甲', source: '<p>x</p>', height: 300 }])
  assert.deepEqual(loadLibrary(() => { throw new Error('规则收紧') }, storage), [])
  savePlaced(7, [{ id: 'p', name: '乙', source: '<p>y</p>', height: 200, layout: { x: 3, y: 0, w: 60, h: 200 } }], storage)
  assert.equal(loadPlaced(7, ok, storage)[0].layout.w, 60)
  assert.deepEqual(loadPlaced(8, ok, storage), [])
  assert.deepEqual(loadPlaced(null, ok, storage), []); assert.equal(savePlaced(null, [], storage), false)
})

test('存储不可用或数据损坏时不抛错', () => {
  const broken = { getItem() { throw new Error('x') }, setItem() { throw new Error('x') } }
  assert.deepEqual(loadLibrary(ok, broken), []); assert.equal(saveLibrary([], broken), false)
  const storage = memory(); storage.setItem('finns.collection-wizard.html-library', '{坏的'); assert.deepEqual(loadLibrary(ok, storage), [])
})

test('导入表单校验：名称、重名、数量、显示高度', () => {
  assert.match(importIssue({ name: ' ', height: 240 }), /名字/)
  assert.match(importIssue({ name: 'a'.repeat(41), height: 240 }), /40/)
  assert.match(importIssue({ name: '甲', height: 240, library: [{ name: '甲' }] }), /同名/)
  assert.match(importIssue({ name: '乙', height: 50 }), /120/)
  assert.match(importIssue({ name: '乙', height: 'abc' }), /120/)
  assert.match(importIssue({ name: '乙', height: 240, library: Array.from({ length: MAX_LIBRARY }, (_, i) => ({ name: `m${i}` })) }), /最多/)
  assert.equal(importIssue({ name: '乙', height: 240 }), '')
  assert.equal(clampHeight('abc'), 240); assert.equal(clampHeight(1), 120)
})
