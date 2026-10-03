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
    mvn -s .mvn/settings.xml test
    if ($LASTEXITCODE -ne 0) { throw "Maven tests failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
