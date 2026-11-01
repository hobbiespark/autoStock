# autoStock 운영 DB 기동 (VALUNIVO 1-1 autoStock D6 단계 C, 구현 514) — start_autostock.bat가 부른다.
#
# 상태 파일이 없으면(legacy) 지금과 같이 infra\docker-compose.yml의 postgres(autostock-db)를 올린다.
# D6 전환 뒤(docker)에는 VALUNIVO가 만든 컨테이너(상태 파일 container)를 docker start로 켜고 healthy를 기다린다(최대 120초).
#   이 컨테이너는 VALUNIVO infra\servers\compose.timescale.yml로 만들어지며 여기서는 만들거나 지우지 않는다.
# 종료 코드: 0 = DB 준비, 1 = 실패(앱을 띄우지 않는다).
param([string]$RuntimeRoot = "")
$ErrorActionPreference = "Continue"
$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$repoRoot = Split-Path -Parent $scriptRoot
. (Join-Path $scriptRoot 'Get-AutoStockDatabase.ps1')
if (-not $RuntimeRoot) { $RuntimeRoot = Get-AutoStockRuntimeRoot $repoRoot }
try { $database = Get-AutoStockDatabase $RuntimeRoot } catch { Write-Host "[X] DB 대상 해석 실패: $($_.Exception.Message)"; exit 1 }

if ($database.Mode -eq 'legacy') {
    docker compose -f (Join-Path $repoRoot 'infra\docker-compose.yml') up -d postgres
    if ($LASTEXITCODE -ne 0) { Write-Host "[X] docker compose up 실패(exit $LASTEXITCODE)"; exit 1 }
    Write-Host "[O] DB legacy container $($database.Container)"
    exit 0
}

docker start $database.Container | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[X] docker start $($database.Container) 실패(exit $LASTEXITCODE) — 컨테이너가 있는지 확인"; exit 1 }
for ($i = 0; $i -lt 60; $i++) {
    $health = (docker inspect -f '{{.State.Health.Status}}' $database.Container) -join ''
    if ($health -eq 'healthy') { Write-Host "[O] DB $($database.Container) healthy (db=$($database.Database), port=$($database.Port))"; exit 0 }
    Start-Sleep -Seconds 2
}
Write-Host "[X] DB $($database.Container) not healthy after 120s (last: $health)"
exit 1
