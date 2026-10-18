// Product prototype validation; no browser-native popover.
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
