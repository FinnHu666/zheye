$ErrorActionPreference = 'Stop'

$jdkRoot = 'D:\java\jdk\jdk-17.0.12'
if (-not (Test-Path -LiteralPath "$jdkRoot\bin\java.exe")) {
    throw "未找到 Java 17：$jdkRoot"
}

$env:JAVA_HOME = $jdkRoot
$env:Path = "$jdkRoot\bin;$env:Path"

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            npm ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw "npm ci failed: $LASTEXITCODE" }
        }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Vue production build failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }

    mvn -s .mvn/settings.xml package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
