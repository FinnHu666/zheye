param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^finns_(test|restore_check)_[a-z0-9_]+$')]
    [string]$DatabaseName,
    [ValidateRange(1024,65535)][int]$DatabasePort = 55439,
    [ValidateScript({ $_ -ge 1024 -and $_ -le 65535 -and $_ -ne 8080 })]
    [int]$Port = 8082
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pgRoot = if ($env:FINNS_POSTGRES_HOME) { $env:FINNS_POSTGRES_HOME } else { 'E:\tools\finns-runtime\postgresql-17.11\pgsql' }
$owner = & (Join-Path $pgRoot 'bin/psql.exe') --no-psqlrc -h 127.0.0.1 -p $DatabasePort -U postgres -d postgres -tAc "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$DatabaseName'"
if ($LASTEXITCODE -ne 0 -or -not $owner -or $owner.Trim() -ne 'finns_app') { throw 'Refusing a missing or non-isolated test database.' }
$version = (Get-Content (Join-Path $projectRoot 'VERSION') -Raw).Trim()
$jar = Join-Path $projectRoot "target/finns-$version.jar"
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build frontend and package the application first.' }
$names = @('FINNS_DB_URL','FINNS_DB_USERNAME','FINNS_DB_PASSWORD','FINNS_AI_CREDENTIAL_SECRET')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:FINNS_DB_URL = "jdbc:postgresql://127.0.0.1:$DatabasePort/$DatabaseName"
    $env:FINNS_DB_USERNAME = 'finns_app'
    $env:FINNS_DB_PASSWORD = ''
    if (-not $env:FINNS_AI_CREDENTIAL_SECRET) { $env:FINNS_AI_CREDENTIAL_SECRET = [Environment]::GetEnvironmentVariable('FINNS_AI_CREDENTIAL_SECRET','User') }
    if (-not $env:FINNS_AI_CREDENTIAL_SECRET) { $env:FINNS_AI_CREDENTIAL_SECRET = [Environment]::GetEnvironmentVariable('FINNS_AI_CREDENTIAL_SECRET','Machine') }
    Write-Output "Isolated topic builder: http://127.0.0.1:$Port ; database: $DatabaseName ; no production database access."
    & D:/java/jdk/jdk-17.0.12/bin/java.exe -jar $jar "--server.port=$Port" --server.address=127.0.0.1 --server.servlet.session.cookie.name=FINNS_BUILDER_TEST --spring.profiles.active=postgres
    if ($LASTEXITCODE -ne 0) { throw "Isolated application exited with code $LASTEXITCODE" }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
