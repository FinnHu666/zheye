$ErrorActionPreference = 'Stop'
$jdkRoot = 'D:\java\jdk\jdk-17.0.12'
if (-not (Test-Path -LiteralPath "$jdkRoot\bin\java.exe")) { throw "未找到 Java 17：$jdkRoot" }
$env:JAVA_HOME = $jdkRoot
$env:Path = "$jdkRoot\bin;$env:Path"
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceFile = Join-Path $projectRoot 'data\aitome.mv.db'
if (-not (Test-Path -LiteralPath $sourceFile)) { throw "H2 source database not found: $sourceFile" }
$listeners = @(netstat -ano | Select-String ':8080\s+.*LISTENING')
if ($listeners.Count -gt 0) { throw 'Stop FINNS/H2 first. The read-only dry run did not open or copy an active H2 file.' }
& (Join-Path $PSScriptRoot 'postgres.ps1') start
if ($LASTEXITCODE -ne 0) { throw "Local PostgreSQL startup failed: $LASTEXITCODE" }

$envNames = @('SPRING_PROFILES_ACTIVE','FINNS_H2_MIGRATION_ENABLED','FINNS_H2_MIGRATION_DRY_RUN','FINNS_H2_SOURCE_FILE','SPRING_MAIN_WEB_APPLICATION_TYPE','FINNS_DB_PASSWORD')
$previous = @{}
foreach ($name in $envNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:SPRING_PROFILES_ACTIVE = 'postgres'
    $env:FINNS_H2_MIGRATION_ENABLED = 'true'
    $env:FINNS_H2_MIGRATION_DRY_RUN = 'true'
    $env:FINNS_H2_SOURCE_FILE = $sourceFile
    $env:SPRING_MAIN_WEB_APPLICATION_TYPE = 'none'
    if ($null -eq $previous['FINNS_DB_PASSWORD']) { $env:FINNS_DB_PASSWORD = '' }
    Push-Location $projectRoot
    try {
        mvn -s .mvn/settings.xml '-Dspring-boot.run.profiles=postgres' spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw "H2 dry-run failed: $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    foreach ($name in $envNames) {
        if ($null -eq $previous[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
}
