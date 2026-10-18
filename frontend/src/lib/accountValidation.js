// 账号表单的输入侧校验（不使用浏览器原生气泡）。规则与后端 PasswordPolicy 一致：8–20 位，两次一致。
// 超过 BCrypt 的 72 字节上限由服务端明确拒绝，这里不重复判断字节数。
export function emailError(value) {
  if (!String(value || '').trim()) return '请填写邮箱地址。'
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value).trim()) ? '' : '邮箱格式还不完整，请填写完整地址，例如 name@example.com。'
}
export function passwordError(password, confirmation) {
  if (!password) return '请设置登录密码。'
  if (password.length < 8) return '密码至少需要8位，请再补充一些。'
  if (password.length > 20) return '密码最多20位，请缩短后再试。'
  if (password !== confirmation) return '两次输入的密码不一致，请重新确认。'
  return ''
}
