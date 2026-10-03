param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^finns_(test|restore_check)_[a-z0-9_]+$')]
    [string]$DatabaseName
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pgRoot = if ($env:FINNS_POSTGRES_HOME) { $env:FINNS_POSTGRES_HOME } else { 'E:\tools\finns-runtime\postgresql-17.11\pgsql' }
$psql = Join-Path $pgRoot 'bin\psql.exe'
if (-not (Test-Path -LiteralPath $psql)) {
    throw "PostgreSQL psql not found: $psql. Set FINNS_POSTGRES_HOME to the extracted pgsql directory."
}

$databaseOwner = & $psql --no-psqlrc -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$DatabaseName'"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the local PostgreSQL test database.' }
if (-not $databaseOwner) { throw "Test database does not exist: $DatabaseName" }
if ($databaseOwner.Trim() -ne 'finns_app') { throw "Refusing test database not owned by finns_app: $DatabaseName" }

$jdkRoot = 'D:\java\jdk\jdk-17.0.12'
if (-not (Test-Path -LiteralPath "$jdkRoot\bin\java.exe")) {
    throw "Java 17 not found: $jdkRoot"
}

$environmentNames = @('SPRING_PROFILES_ACTIVE', 'FINNS_DB_URL', 'FINNS_DB_USERNAME', 'FINNS_DB_PASSWORD', 'FINNS_H2_MIGRATION_ENABLED', 'JAVA_HOME', 'Path')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:SPRING_PROFILES_ACTIVE = 'postgres'
    $env:FINNS_DB_URL = "jdbc:postgresql://127.0.0.1:5432/$DatabaseName"
    $env:FINNS_DB_USERNAME = 'finns_app'
    $env:FINNS_DB_PASSWORD = ''
    $env:FINNS_H2_MIGRATION_ENABLED = 'false'
    $env:JAVA_HOME = $jdkRoot
    $env:Path = "$jdkRoot\bin;$env:Path"

    Write-Output "Running tests against isolated loopback PostgreSQL database $DatabaseName. Test writes remain in this database."
    Push-Location $projectRoot
    try {
        & (Join-Path $PSScriptRoot 'test.ps1')
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $environmentNames) {
        $oldValue = $previousEnvironment[$name]
        if ($null -eq $oldValue) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $oldValue, 'Process')
        }
    }
}
