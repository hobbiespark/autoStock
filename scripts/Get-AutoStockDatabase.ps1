# autoStock 운영 DB 대상 해석 (VALUNIVO 1-1 autoStock D6 단계 C, 구현 514) — backup_db.ps1·start_database.ps1·load_env.ps1이 dot-source한다.
#
# 상태 파일 <RuntimeRoot>\database.json이 없으면 지금과 똑같다: 컨테이너 autostock-db, DB·계정 autostock, compose infra\docker-compose.yml.
# D6 전환(단계 D)이 상태 파일 {"mode":"docker","env_file":...,"app_config":...,"container":...}을 쓰면 그때부터
#   DB는 VALUNIVO 생성 Compose env(server-env\autostock.production.env, VALUNIVO infra\servers\compose.timescale.yml)의 컨테이너이고
#   앱은 상태 파일의 app_config(app-config\production\autostock.env)를 읽는다. 앱 설정의 DB_PORT·DB_NAME이 env 파일과 다르면 멈춘다.
# 글쓰기 Get-WritingDatabase.ps1(VALUNIVO 492·D257)과 같은 규칙이다. 값(암호·키)은 읽지도 출력하지도 않는다.
function Get-AutoStockRuntimeRoot([string]$RepoRoot) {
    if ($env:AUTOSTOCK_RUNTIME_ROOT) { return $env:AUTOSTOCK_RUNTIME_ROOT }
    return (Join-Path (Split-Path $RepoRoot -Parent) 'autoStock-runtime')
}

function Read-AutoStockKeys([string]$Path, [string[]]$Keys) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*([A-Za-z0-9_]+)\s*=(.*)$' -and $Keys -contains $Matches[1]) { $values[$Matches[1]] = $Matches[2].Trim().Trim("'") }
    }
    return $values
}

function Get-AutoStockDatabase([string]$RuntimeRoot) {
    $statePath = Join-Path $RuntimeRoot 'database.json'
    if (-not (Test-Path -LiteralPath $statePath)) {
        return [pscustomobject]@{ Mode = 'legacy'; Container = 'autostock-db'; Database = 'autostock'; AdminUser = 'autostock'; Port = $null
            EnvFile = $null; AppConfig = $null; StatePath = $null }
    }
    $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($state.mode -ne 'docker') { throw 'database.json mode must be docker' }
    foreach ($name in @('env_file', 'app_config', 'container')) { if (-not $state.$name) { throw "database.json $name is missing" } }
    if ($state.container -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') { throw 'database.json container name is invalid' }
    foreach ($path in @([string]$state.env_file, [string]$state.app_config)) {
        if (-not [IO.Path]::IsPathRooted($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "database.json path is not an existing absolute file: $path" }
    }
    $values = Read-AutoStockKeys ([string]$state.env_file) @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_ADMIN_USER')
    if ($values['DB_HOST'] -ne '127.0.0.1' -or $values['DB_PORT'] -notmatch '^\d{1,5}$' -or $values['DB_NAME'] -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$' -or
        $values['DB_ADMIN_USER'] -notmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'env file DB_HOST/DB_PORT/DB_NAME/DB_ADMIN_USER is invalid' }
    $app = Read-AutoStockKeys ([string]$state.app_config) @('DB_HOST', 'DB_PORT', 'DB_NAME')
    if ($app['DB_HOST'] -ne '127.0.0.1' -or $app['DB_PORT'] -cne $values['DB_PORT'] -or $app['DB_NAME'] -cne $values['DB_NAME']) {
        throw 'generated app config DB_HOST/DB_PORT/DB_NAME does not match the env file'
    }
    return [pscustomobject]@{ Mode = 'docker'; Container = [string]$state.container; Database = $values['DB_NAME']; AdminUser = $values['DB_ADMIN_USER']
        Port = [int]$values['DB_PORT']; EnvFile = [string]$state.env_file; AppConfig = [string]$state.app_config; StatePath = $statePath }
}
