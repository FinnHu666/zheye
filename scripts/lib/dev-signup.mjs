// 本机验收脚本用：经开发出信箱完成“邮箱验证 → 设置账号 → 登录”（FINNS-15 之后不再有一步注册）。
// 需要服务以 FINNS_MAIL_MODE=outbox 且 --server.address=127.0.0.1 启动，邮件写入 data/dev-mail/outbox.jsonl（已被 git 忽略）。
import { readFile } from 'node:fs/promises'

const outbox = new URL('../../data/dev-mail/outbox.jsonl', import.meta.url)

async function tokenFor(email, attempts = 40) {
  for (let i = 0; i < attempts; i++) {
    try {
      const rows = (await readFile(outbox, 'utf8')).split(/\r?\n/).filter(Boolean).map((line) => JSON.parse(line))
      const mail = rows.filter((row) => row.to === email.toLowerCase()).pop()
      const match = mail && /token=([A-Za-z0-9_-]+)/.exec(mail.text)
      if (match) return match[1]
    } catch (error) { if (error.code !== 'ENOENT') throw error }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error('开发出信箱里没有找到验证邮件：请确认服务以 FINNS_MAIL_MODE=outbox 启动')
}

// post(path, body) 必须在失败时抛错，并让同一份 Cookie 会话保持登录。
export async function signupViaDevOutbox(post, { email, password, displayName }) {
  await post('/api/auth/request-link', { email, purpose: 'signup' })
  const token = await tokenFor(email)
  await post('/api/auth/signup-verify', { token })
  await post('/api/auth/register', { token, displayName, password, confirmPassword: password })
  return post('/api/auth/login', { email, password })
}
