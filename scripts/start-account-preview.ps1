param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^finns_test_[a-z0-9_]+$')]
    [string]$DatabaseName,
    [ValidateRange(1024,65535)]
    [int]$Port = 8091,
    [switch]$Stop,
    [switch]$RealMail
)
# 本机账号流程体验实例（FINNS-15 验收用）：只监听 127.0.0.1，只连 finns_test_* 隔离库。默认邮件写入 data/dev-mail/（出信箱，不发真实邮件）。
# 加 -RealMail 时改用 smtp 模式，发送真实邮件：需要先在当前终端设置 FINNS_MAIL_HOST/PORT/USERNAME/PASSWORD/FROM（见 docs/iterations/0.7.0/MAIL_SETUP.md），
# 链接前缀固定为本机预览地址，所以邮件里的链接只能在本机打开。
# 拒绝 8080 和主库 finns。需要先运行 scripts/package.ps1 生成 target/finns-<version>.jar。
$ErrorActionPreference = 'Stop'
if ($Port -eq 8080) { throw 'Port 8080 is the main site; use another port.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot 'target\account-preview.pid'
if ($Stop) {
    if (Test-Path -LiteralPath $pidFile) {
        $id = [int](Get-Content -LiteralPath $pidFile)
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
        if ($process -and $process.CommandLine -match 'finns-.*\.jar' -and $process.CommandLine -match '--server.address=127.0.0.1') { Stop-Process -Id $id; Write-Output "Stopped preview PID $id." }
        else { Write-Output 'Recorded PID is not a FINNS preview process; nothing stopped.' }
        Remove-Item -LiteralPath $pidFile
    }
    exit 0
}
$version = (Get-Content (Join-Path $projectRoot 'VERSION') -Raw).Trim()
$jar = Join-Path $projectRoot "target\finns-$version.jar"
if (-not (Test-Path -LiteralPath $jar)) { throw "Missing $jar; run scripts/package.ps1 first." }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is already in use." }
$psql = Join-Path ($(if ($env:FINNS_POSTGRES_HOME) { $env:FINNS_POSTGRES_HOME } else { 'E:\tools\finns-runtime\postgresql-17.11\pgsql' })) 'bin\psql.exe'
$owner = & $psql --no-psqlrc -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$DatabaseName'"
if ($LASTEXITCODE -ne 0 -or "$owner".Trim() -ne 'finns_app') { throw "Test database missing or not owned by finns_app: $DatabaseName" }
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
if ($RealMail) {
    foreach ($required in 'FINNS_MAIL_HOST','FINNS_MAIL_FROM','FINNS_MAIL_USERNAME','FINNS_MAIL_PASSWORD') {
        if (-not [Environment]::GetEnvironmentVariable($required, 'Process')) { throw "-RealMail needs $required in the current process environment." }
    }
}
$names = @('FINNS_AI_CREDENTIAL_SECRET','FINNS_DB_URL','FINNS_DB_USERNAME','FINNS_DB_PASSWORD','FINNS_MAIL_MODE','FINNS_PUBLIC_BASE_URL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:FINNS_AI_CREDENTIAL_SECRET = [Convert]::ToBase64String($bytes)   # 每次随机，仅本预览实例使用
    $env:FINNS_DB_URL = "jdbc:postgresql://127.0.0.1:5432/$DatabaseName"
    $env:FINNS_DB_USERNAME = 'finns_app'
    $env:FINNS_DB_PASSWORD = ''
    $env:FINNS_MAIL_MODE = $(if ($RealMail) { 'smtp' } else { 'outbox' })
    $env:FINNS_PUBLIC_BASE_URL = "http://127.0.0.1:$Port"
    $log = Join-Path $projectRoot 'target\account-preview.log'
    $process = Start-Process -FilePath 'D:/java/jdk/jdk-17.0.12/bin/java.exe' -ArgumentList @('-jar', ('"' + $jar + '"'), '--spring.profiles.active=postgres', '--server.address=127.0.0.1', "--server.port=$Port", '--server.servlet.session.cookie.name=FINNS_PREVIEW') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError ($log + '.err') -PassThru
    Set-Content -LiteralPath $pidFile -Value $process.Id
    Write-Output "Preview started: PID $($process.Id), http://127.0.0.1:$Port/auth/register . $(if ($RealMail) { 'Real SMTP mail is ON.' } else { 'Mails are written to data/dev-mail/outbox.jsonl.' }) Stop with: scripts/start-account-preview.ps1 -DatabaseName $DatabaseName -Stop"
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
