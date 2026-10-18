param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^finns_test_[a-z0-9_]+$')]
    [string]$DatabaseName,
    [ValidateRange(1024,65535)]
    [int]$PreviewPort = 8091,
    [switch]$ResetOnly
)
# FINNS-16 验收预览：在隔离库 finns_test_* 里准备虚构演示账号并设置层级（站长小明=站长；周以宁、陈默=管理员；其余=普通成员），
# 并把停用状态、站长指针和本项操作记录还原到验收起点。只连 127.0.0.1，拒绝主库 finns。预览须已用 start-account-preview.ps1 启动（出信箱模式）。
# 演示账号邮箱均为 *@demo.finns.test，密码见 seed-user-admin-preview.mjs 顶部（仅限本机隔离预览）。
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$psql = Join-Path ($(if ($env:FINNS_POSTGRES_HOME) { $env:FINNS_POSTGRES_HOME } else { 'E:\tools\finns-runtime\postgresql-17.11\pgsql' })) 'bin\psql.exe'
$owner = & $psql --no-psqlrc -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$DatabaseName'"
if ($LASTEXITCODE -ne 0 -or "$owner".Trim() -ne 'finns_app') { throw "Test database missing or not owned by finns_app: $DatabaseName" }

if (-not $ResetOnly) {
    $env:FINNS_PREVIEW_URL = "http://127.0.0.1:$PreviewPort"
    try {
        & node (Join-Path $PSScriptRoot 'seed-user-admin-preview.mjs')
        if ($LASTEXITCODE -ne 0) { throw "seed-user-admin-preview.mjs failed: $LASTEXITCODE" }
    } finally { Remove-Item Env:FINNS_PREVIEW_URL -ErrorAction SilentlyContinue }
}

$sql = @"
update user_accounts set account_status='ACTIVE', status_version=status_version+1, session_epoch=session_epoch+1 where email like '%@demo.finns.test';
update user_accounts set role_id=(select id from roles where code='MEMBER') where email like '%@demo.finns.test';
update user_accounts set role_id=(select id from roles where code='OWNER') where email='owner@demo.finns.test';
update user_accounts set role_id=(select id from roles where code='ADMIN') where email in ('zhou@demo.finns.test','chen@demo.finns.test');
update access_control_state set owner_user_id=(select id from user_accounts where email='owner@demo.finns.test'), owner_version=owner_version+1, version=version+1;
delete from access_audit_log where type in ('ACCOUNT_STATUS','OWNER_TRANSFER');
"@
& $psql --no-psqlrc -h 127.0.0.1 -p 5432 -U postgres -d $DatabaseName -v ON_ERROR_STOP=1 -c $sql
if ($LASTEXITCODE -ne 0) { throw "Resetting demo roles failed: $LASTEXITCODE" }
Write-Output "Demo hierarchy ready in $DatabaseName. Open http://127.0.0.1:$PreviewPort/admin/users and sign in as owner@demo.finns.test."
