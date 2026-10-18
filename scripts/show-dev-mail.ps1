param([int]$Last = 5)
# 本机验收用：列出开发出信箱（data/dev-mail/outbox.jsonl）里最近几封邮件的时间、收件人、主题和链接。
# 出信箱只在 -mode outbox 的本机预览里产生，里面的链接是一次性凭据，不要分享。
$file = Join-Path (Split-Path -Parent $PSScriptRoot) 'data\dev-mail\outbox.jsonl'
if (-not (Test-Path -LiteralPath $file)) { Write-Output '出信箱还没有邮件。'; exit 0 }
$rows = Get-Content -LiteralPath $file -Encoding UTF8 | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json }
$rows | Select-Object -Last $Last | ForEach-Object {
    $link = ([regex]::Match($_.text, 'http\S+')).Value
    Write-Output ('[{0}] {1}  ->  {2}' -f ([datetime]$_.at).ToLocalTime().ToString('HH:mm:ss'), $_.subject, $_.to)
    if ($link) { Write-Output ('    ' + $link) }
}
