param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pgRoot = if ($env:FINNS_POSTGRES_HOME) { $env:FINNS_POSTGRES_HOME } else { 'E:\tools\finns-runtime\postgresql-17.11\pgsql' }
$dataRoot = Join-Path $projectRoot 'data\postgres'
$bin = Join-Path $pgRoot 'bin'
$pgCtl = Join-Path $bin 'pg_ctl.exe'
$initDb = Join-Path $bin 'initdb.exe'
$psql = Join-Path $bin 'psql.exe'
$createdb = Join-Path $bin 'createdb.exe'
$createuser = Join-Path $bin 'createuser.exe'

foreach ($tool in @($pgCtl, $initDb, $psql, $createdb, $createuser)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "PostgreSQL tool not found: $tool. Set FINNS_POSTGRES_HOME to the extracted pgsql directory." }
}

if ($Action -eq 'status') {
    if (-not (Test-Path -LiteralPath (Join-Path $dataRoot 'PG_VERSION'))) { Write-Output 'PostgreSQL is not initialized for FINNS.'; exit 0 }
    & $pgCtl -D $dataRoot status
    exit $LASTEXITCODE
}

if ($Action -eq 'stop') {
    if (Test-Path -LiteralPath (Join-Path $dataRoot 'PG_VERSION')) {
        & $pgCtl -D $dataRoot -m fast stop
        if ($LASTEXITCODE -ne 0) { throw "Could not stop FINNS PostgreSQL: $LASTEXITCODE" }
    }
    exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $dataRoot 'PG_VERSION'))) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $dataRoot) -Force | Out-Null
    & $initDb -D $dataRoot -U postgres --auth-local=trust --auth-host=trust --encoding=UTF8
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL initdb failed: $LASTEXITCODE" }
}

& $pgCtl -D $dataRoot status *> $null
if ($LASTEXITCODE -ne 0) {
    $log = Join-Path $projectRoot 'data\postgres-server.log'
    & $pgCtl -D $dataRoot -l $log -o '-h 127.0.0.1 -p 5432' start
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL failed to start. Check $log" }
}

$roleExists = & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='finns_app'"
if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to the local PostgreSQL maintenance database.' }
if (-not ($roleExists -match '1')) {
    & $createuser -h 127.0.0.1 -p 5432 -U postgres --no-superuser --no-createdb --no-createrole finns_app
    if ($LASTEXITCODE -ne 0) { throw "Could not create the FINNS application role: $LASTEXITCODE" }
}
$databaseExists = & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='finns'"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local PostgreSQL databases.' }
if (-not ($databaseExists -match '1')) {
    & $createdb -h 127.0.0.1 -p 5432 -U postgres -O finns_app finns
    if ($LASTEXITCODE -ne 0) { throw "Could not create the FINNS database: $LASTEXITCODE" }
}

Write-Output "FINNS PostgreSQL is ready at 127.0.0.1:5432/finns (loopback-only development database)."
