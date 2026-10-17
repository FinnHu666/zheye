param(
    [Parameter(Mandatory = $true)]
    [string]$ReleaseDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$release = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
$version = (Get-Content (Join-Path $release 'VERSION') -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid release version.' }
$jar = Join-Path $release "finns-$version.jar"
$java = 'D:/java/jdk/jdk-17.0.12/bin/java.exe'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Release JAR is missing.' }
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw 'Port 8080 is already in use; verify its process before stopping it.' }
$pgBin = 'E:/tools/finns-runtime/postgresql-17.11/pgsql/bin'
$pgData = Join-Path $projectRoot 'data/postgres'
if (-not (Test-Path -LiteralPath (Join-Path $pgData 'PG_VERSION'))) { throw 'Existing main database cluster is missing; this script does not initialize databases.' }
& "$pgBin/pg_ctl.exe" -D $pgData status *> $null
if ($LASTEXITCODE -ne 0) {
    & "$pgBin/pg_ctl.exe" -D $pgData -l (Join-Path $projectRoot 'data/postgres-server.log') -o '-h 127.0.0.1 -p 5432' start
    if ($LASTEXITCODE -ne 0) { throw 'Main PostgreSQL startup failed.' }
}
$owner = & "$pgBin/psql.exe" -X -w -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "select pg_get_userbyid(datdba) from pg_database where datname='finns'"
if ($LASTEXITCODE -ne 0 -or "$owner".Trim() -ne 'finns_app') { throw 'Existing main database ownership check failed.' }
$names = @('FINNS_DB_URL','FINNS_DB_USERNAME','FINNS_DB_PASSWORD','FINNS_AI_CREDENTIAL_SECRET','FINNS_BOOTSTRAP_ADMIN_EMAIL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:FINNS_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/finns'
    $env:FINNS_DB_USERNAME = 'finns_app'
    $env:FINNS_DB_PASSWORD = ''
    # 0.7.0：没有任何能管理角色的用户时，把该邮箱对应的账户设为管理员；已有管理者则忽略。需在调用前设置环境变量。
    if (-not $env:FINNS_AI_CREDENTIAL_SECRET) { $env:FINNS_AI_CREDENTIAL_SECRET = [Environment]::GetEnvironmentVariable('FINNS_AI_CREDENTIAL_SECRET','User') }
    if (-not $env:FINNS_AI_CREDENTIAL_SECRET) { $env:FINNS_AI_CREDENTIAL_SECRET = [Environment]::GetEnvironmentVariable('FINNS_AI_CREDENTIAL_SECRET','Machine') }
    if (-not $env:FINNS_AI_CREDENTIAL_SECRET -or [Convert]::FromBase64String($env:FINNS_AI_CREDENTIAL_SECRET).Length -ne 32) { throw 'Configure FINNS_AI_CREDENTIAL_SECRET as 32 random bytes in Base64 before starting the main site.' }
    $process = Start-Process -FilePath $java -ArgumentList @('-jar',('"' + $jar + '"'),'--spring.profiles.active=postgres','--server.address=127.0.0.1','--server.port=8080','--server.servlet.session.cookie.name=FINNS_MAIN') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $release 'main-server.log') -RedirectStandardError (Join-Path $release 'main-server-error.log') -PassThru
    Set-Content -LiteralPath (Join-Path $release 'main.pid') -Value $process.Id
    Write-Output "Main site process started: PID $($process.Id), version $version, http://127.0.0.1:8080 . Check the startup log before considering deployment successful."
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
