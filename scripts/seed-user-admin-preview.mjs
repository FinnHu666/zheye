// FINNS-16 验收预览用：经开发出信箱创建虚构演示账号（仅本机 127.0.0.1 预览，邮箱均为 @demo.finns.test）。
// 需要服务以 FINNS_MAIL_MODE=outbox 启动（scripts/start-account-preview.ps1 默认如此）；账号创建后由 seed-user-admin-preview.ps1 分配角色。
// 演示密码是只用于本机隔离预览库的测试值，不得用于任何真实账号。
import { readFile } from 'node:fs/promises'

export const DEMO_PASSWORD = 'Demo-Passw0rd-1'
const base = process.env.FINNS_PREVIEW_URL || 'http://127.0.0.1:8091'
if (!/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(base)) throw new Error('只允许本机预览地址')
const outbox = new URL('../data/dev-mail/outbox.jsonl', import.meta.url)

let cookies = new Map()
function remember(res) { for (const c of res.headers.getSetCookie?.() || []) { const [kv] = c.split(';'); const i = kv.indexOf('='); cookies.set(kv.slice(0, i), kv.slice(i + 1)) } }
const jar = () => [...cookies].map(([k, v]) => `${k}=${v}`).join('; ')
async function post(path, body) {
  if (!cookies.has('XSRF-TOKEN')) remember(await fetch(base + '/api/auth/csrf', { headers: { cookie: jar() } }))
  const res = await fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json', cookie: jar(), 'X-XSRF-TOKEN': decodeURIComponent(cookies.get('XSRF-TOKEN')) }, body: JSON.stringify(body) })
  remember(res)
  const text = await res.text()
  if (!res.ok) throw new Error(`${path} ${res.status} ${text}`)
  return text
}
async function tokenFor(email) {
  for (let i = 0; i < 60; i++) {
    try {
      const rows = (await readFile(outbox, 'utf8')).split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line))
      const mail = rows.filter((row) => row.to === email.toLowerCase()).pop()
      const match = mail && /token=([A-Za-z0-9_-]+)/.exec(mail.text)
      if (match) return match[1]
    } catch (error) { if (error.code !== 'ENOENT') throw error }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error('开发出信箱里没有找到验证邮件：请确认预览以出信箱模式启动')
}

// 站长小明将被分配为站长；周以宁、陈默为管理员；其余为普通成员（见 seed-user-admin-preview.ps1）。
const people = [['站长小明', 'owner'], ['周以宁', 'zhou'], ['陈默', 'chen'], ['阿远', 'ayuan'], ['小林', 'xiaolin'], ['苏木', 'sumu'], ['何夕', 'hexi']]
for (const [displayName, local] of people) {
  const email = `${local}@demo.finns.test`
  cookies = new Map()
  await post('/api/auth/request-link', { email, purpose: 'signup' })
  const token = await tokenFor(email)
  await post('/api/auth/signup-verify', { token })
  await post('/api/auth/register', { token, displayName, password: DEMO_PASSWORD, confirmPassword: DEMO_PASSWORD })
  console.log('created', email)
}
