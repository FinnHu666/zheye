$ErrorActionPreference = 'Stop'

$jdkRoot = 'D:\java\jdk\jdk-17.0.12'
if (-not (Test-Path -LiteralPath "$jdkRoot\bin\java.exe")) {
    throw "未找到 Java 17：$jdkRoot"
}

$env:JAVA_HOME = $jdkRoot
$env:Path = "$jdkRoot\bin;$env:Path"

& (Join-Path $PSScriptRoot 'postgres.ps1') start
if ($LASTEXITCODE -ne 0) { throw "Local PostgreSQL startup failed: $LASTEXITCODE" }

Push-Location (Join-Path $PSScriptRoot '..\frontend')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        npm install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw "npm install failed: $LASTEXITCODE" }
    }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "Vue production build failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}

$hadDbPassword = Test-Path Env:FINNS_DB_PASSWORD
$previousDbPassword = $env:FINNS_DB_PASSWORD
try {
    if (-not $hadDbPassword) { $env:FINNS_DB_PASSWORD = '' }
    mvn -s .mvn/settings.xml '-Dspring-boot.run.profiles=postgres' '-Dspring-boot.run.arguments=--server.address=127.0.0.1' spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw "Spring Boot failed: $LASTEXITCODE" }
} finally {
    if ($hadDbPassword) { $env:FINNS_DB_PASSWORD = $previousDbPassword }
    else { Remove-Item Env:FINNS_DB_PASSWORD -ErrorAction SilentlyContinue }
}
