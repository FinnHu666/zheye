$ErrorActionPreference = 'Stop'
$jdkRoot = 'D:\java\jdk\jdk-17.0.12'
if (-not (Test-Path -LiteralPath "$jdkRoot\bin\java.exe")) { throw "未找到 Java 17：$jdkRoot" }
$env:JAVA_HOME = $jdkRoot
$env:Path = "$jdkRoot\bin;$env:Path"

$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceFile = Join-Path $projectRoot 'data\aitome.mv.db'
if (-not (Test-Path -LiteralPath $sourceFile)) { throw "H2 source database not found: $sourceFile" }
$listeners = @(netstat -ano | Select-String ':8080\s+.*LISTENING')
if ($listeners.Count -gt 0) { throw "Port 8080 is still listening. Stop the FINNS/H2 application first, then rerun this script. No backup or migration was attempted." }

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupRoot = Join-Path $projectRoot "data\backups\$timestamp"
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$backupFile = Join-Path $backupRoot 'aitome.mv.db'
Copy-Item -LiteralPath $sourceFile -Destination $backupFile
$sourceHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash
$backupHash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash
if ($sourceHash -ne $backupHash) { throw "H2 backup verification failed: $backupFile" }
Write-Output "Verified H2 backup: $backupFile"

& (Join-Path $PSScriptRoot 'postgres.ps1') start
if ($LASTEXITCODE -ne 0) { throw "Local PostgreSQL startup failed: $LASTEXITCODE" }

$oldProfile = $env:SPRING_PROFILES_ACTIVE
$oldMigration = $env:FINNS_H2_MIGRATION_ENABLED
$oldSource = $env:FINNS_H2_SOURCE_FILE
$oldWebType = $env:SPRING_MAIN_WEB_APPLICATION_TYPE
$hadDbPassword = Test-Path Env:FINNS_DB_PASSWORD
$oldDbPassword = $env:FINNS_DB_PASSWORD
try {
    $env:SPRING_PROFILES_ACTIVE = 'postgres'
    $env:FINNS_H2_MIGRATION_ENABLED = 'true'
    $env:FINNS_H2_SOURCE_FILE = $sourceFile
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
    if (-not $hadDbPassword) { $env:FINNS_DB_PASSWORD = '' }
    Push-Location $projectRoot
    try {
        mvn -s .mvn/settings.xml '-Dspring-boot.run.profiles=postgres' spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw "H2-to-PostgreSQL migration failed: $LASTEXITCODE. Verified source and backup were retained." }
    } finally { Pop-Location }
} finally {
    $env:SPRING_PROFILES_ACTIVE = $oldProfile
    $env:FINNS_H2_MIGRATION_ENABLED = $oldMigration
    $env:FINNS_H2_SOURCE_FILE = $oldSource
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = $oldWebType
    if ($hadDbPassword) { $env:FINNS_DB_PASSWORD = $oldDbPassword }
    else { Remove-Item Env:FINNS_DB_PASSWORD -ErrorAction SilentlyContinue }
}
Write-Output "Migration source retained: $sourceFile"
Write-Output "Verified backup retained: $backupFile"
