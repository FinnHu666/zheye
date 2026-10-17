import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import {
  compileHtmlBlock,
  timelineExample,
} from '../src/lib/topic-builder/html.js'
globalThis.document = new JSDOM('').window.document
const fixtures = JSON.parse(
  readFileSync(
    new URL('../src/lib/topic-builder/html-fixtures.json', import.meta.url),
    'utf8',
  ),
)
for (const fixture of fixtures)
  test('HTML shared policy: ' + fixture.name, () => {
    if (fixture.valid)
      assert.doesNotThrow(() => compileHtmlBlock(fixture.source))
    else assert.throws(() => compileHtmlBlock(fixture.source))
  })
test('timeline is isolated by CSP and code capacity is exact', () => {
  const compiled = compileHtmlBlock(timelineExample)
  assert.match(compiled.srcdoc, /script-src 'none'/)
  assert.match(compiled.srcdoc, /connect-src 'none'/)
  assert.match(compiled.srcdoc, /img-src data:/)
  assert.doesNotThrow(() =>
    compileHtmlBlock('<p>' + 'x'.repeat(59993) + '</p>'),
  )
  assert.throws(() => compileHtmlBlock('<p>' + 'x'.repeat(59994) + '</p>'))
  assert.throws(() =>
    compileHtmlBlock('<div>'.repeat(42) + 'x' + '</div>'.repeat(42)),
  )
  assert.throws(() => compileHtmlBlock('<br>'.repeat(2001)))
})
