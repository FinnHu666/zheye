// 契约 CONTRACT_CONTENT_IMAGES §6 T-F1–T-F3：本站渲染规则（原始 HTML 文字化、外链图片、链接白名单、失败占位）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { escapeRawHtml, imagePolicy, imageFallback } from '../src/lib/markdownRender.js'

const { window } = new JSDOM('<!doctype html><div id="root"></div>')
const doc = window.document
const parse = (html) => { const div = doc.createElement('div'); div.innerHTML = imagePolicy(html, doc); return div }

test('T-F1 raw HTML outside code becomes text; fenced, indented and inline code stay untouched', () => {
  assert.equal(escapeRawHtml('<img src=x onerror=alert(1)> <b>粗</b>'), '&lt;img src=x onerror=alert(1)> &lt;b>粗&lt;/b>')
  assert.equal(escapeRawHtml('前 `<b>` 后 <i>'), '前 `<b>` 后 &lt;i>')
  assert.equal(escapeRawHtml('``a ` <b> ``<x>'), '``a ` <b> ``&lt;x>')
  assert.equal(escapeRawHtml('```html\n<script>1</script>\n```\n<script>'), '```html\n<script>1</script>\n```\n&lt;script>')
  assert.equal(escapeRawHtml('~~~\n<a>\n~~~'), '~~~\n<a>\n~~~')
  assert.equal(escapeRawHtml('段落\n\n    <pre>缩进代码</pre>'), '段落\n\n    <pre>缩进代码</pre>')
  assert.equal(escapeRawHtml('未闭合 `<b>'), '未闭合 `&lt;b>')
  assert.equal(escapeRawHtml(null), '')
  assert.equal(escapeRawHtml('![图](https://a.example/x.png) 1 < 2'), '![图](https://a.example/x.png) 1 &lt; 2')
})

test('T-F2 only http/https images are kept, with no-referrer, lazy and async; other sources become a notice', () => {
  const root = parse('<p><img src="https://a.example/x.png" alt="好" onerror="x()" data-src="y" style="width:9999px"><img src="http://b.example/y.png" alt=""></p>'
    + '<img src="data:image/png;base64,AA" alt="数据"><img src="javascript:alert(1)" alt="脚本"><img src="/local.png" alt="相对"><img alt="空">')
  const imgs = [...root.querySelectorAll('img')]
  assert.deepEqual(imgs.map((i) => i.getAttribute('src')), ['https://a.example/x.png', 'http://b.example/y.png'])
  for (const img of imgs) {
    assert.equal(img.getAttribute('referrerpolicy'), 'no-referrer'); assert.equal(img.getAttribute('loading'), 'lazy'); assert.equal(img.getAttribute('decoding'), 'async')
    assert.equal(img.className, 'md-img'); assert.equal(img.getAttribute('onerror'), null); assert.equal(img.getAttribute('style'), null); assert.equal(img.getAttribute('data-src'), null)
  }
  assert.deepEqual([...root.querySelectorAll('.md-img-fail')].map((s) => s.textContent), ['图片地址不受支持：数据', '图片地址不受支持：脚本', '图片地址不受支持：相对', '图片地址不受支持：空'])
})

test('T-F2 links keep http/https, site paths and anchors; javascript:, protocol-relative and data: become plain text', () => {
  const root = parse('<a href="https://ok.example">好</a><a href="/collections/1">内部</a><a href="#a">锚</a><a href="javascript:alert(1)">坏</a><a href="//evil.example">坏2</a><a href="data:text/html,1">坏3</a>')
  assert.deepEqual([...root.querySelectorAll('a')].map((a) => a.getAttribute('href')), ['https://ok.example', '/collections/1', '#a'])
  assert.equal(root.querySelector('a').getAttribute('rel'), 'noopener noreferrer nofollow')
  assert.match(root.textContent, /坏坏2坏3$/)
})

test('T-F2 extra renderers (math, mermaid, charts) are neutralised so no extra assets are loaded', () => {
  const root = parse('<div class="language-math">E=mc^2</div><pre><code class="language-mermaid">graph TD</code></pre><pre><code class="language-js">x</code></pre>')
  assert.equal(root.querySelector('div').className, ''); assert.equal(root.querySelectorAll('code')[0].className, ''); assert.equal(root.querySelectorAll('code')[1].className, 'language-js')
})

test('T-F3 a failed external image becomes “图片无法加载：说明（域名）”; other images are left alone', () => {
  const root = doc.getElementById('root')
  root.innerHTML = imagePolicy('<img src="https://no-such.example/a.png" alt="坏图">', doc) + '<img class="avatar" src="https://x.example/b.png">'
  const detach = imageFallback(root)
  root.querySelector('img.md-img').dispatchEvent(new window.Event('error'))
  root.querySelector('img.avatar').dispatchEvent(new window.Event('error'))
  assert.equal(root.querySelector('.md-img-fail').textContent, '图片无法加载：坏图（no-such.example）')
  assert.ok(root.querySelector('img.avatar'))
  detach()
})
