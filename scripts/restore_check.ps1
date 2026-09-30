# autoStock 백업 복원 리허설 (Phase 0.8 — aiDoc/backup.md, RUNBOOK 8절, 월 1회 권장)
#
# 동작: 운영과 같은 이미지(postgres:16-alpine)로 임시 컨테이너를 띄워 최신(또는 지정한) 덤프를 pg_restore → 주요 테이블 행 수를
#       운영 DB와 나란히 보여준다. 복원 실패·테이블 누락이면 종료 코드 1. 행 수 차이는 백업 뒤 운영 DB가 바뀐 만큼이라 경고만 한다
#       (장 마감 뒤 백업 직후에 돌리면 보통 같다). 운영 DB에는 읽기(count)만 한다. 임시 컨테이너는 끝나면 지운다.
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\restore_check.ps1
#         (옵션) -DumpFile D:\backup\autostock\autostock_20261001_1630.dump  -BackupDir D:\backup\autostock
param(
    [string]$BackupDir = "D:\backup\autostock",
    [string]$DumpFile = "",
    [string]$LiveContainer = "autostock-db",
    [string]$Image = "postgres:16-alpine"
)

# 오류 처리: 네이티브 명령(docker)은 종료 코드($LASTEXITCODE)로 판정한다. $ErrorActionPreference를 "Stop"으로 두지 않는다 —
# Windows PowerShell 5.1은 네이티브 명령의 stderr를 리다이렉트(2>$null)하면 오류 레코드로 바꿔 "Stop"에서 스크립트를 멈춘다
# (예: 없는 컨테이너 docker rm -f). cmdlet은 -ErrorAction Stop을 개별로 준다.
$ErrorActionPreference = "Continue"
$tables = @("orders", "event_store", "signal_decisions", "daily_performance", "ipo_deals",
            "disclosure_blacklist", "market_holidays", "risk_state", "risk_daily_pnl", "flyway_schema_history")
$name = "autostock-restore-check"

if (-not $DumpFile) {
    $latest = Get-ChildItem -Path $BackupDir -Filter "autostock_*.dump" | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { Write-Host "[X] 백업 파일 없음: $BackupDir"; exit 1 }
    $DumpFile = $latest.FullName
}
Write-Host "[.] 복원 대상: $DumpFile"

$failed = $false
try {
    docker rm -f $name 2>$null | Out-Null
    docker run -d --name $name -e POSTGRES_USER=autostock -e POSTGRES_PASSWORD=restore-check -e POSTGRES_DB=autostock $Image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "임시 컨테이너 기동 실패" }

    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        docker exec $name pg_isready -U autostock -d autostock 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "임시 DB 준비 시간 초과(60초)" }
    Start-Sleep -Seconds 2   # 초기화 스크립트가 끝나고 재시작하는 짧은 창을 넘긴다

    docker cp $DumpFile "${name}:/tmp/restore.dump"
    if ($LASTEXITCODE -ne 0) { throw "덤프 복사 실패" }
    docker exec $name pg_restore -U autostock -d autostock --no-owner --exit-on-error /tmp/restore.dump
    if ($LASTEXITCODE -ne 0) { throw "pg_restore 실패(exit $LASTEXITCODE)" }
    Write-Host "[O] pg_restore 완료"

    Write-Host ("{0,-24} {1,12} {2,12}  {3}" -f "table", "restored", "live", "판정")
    foreach ($t in $tables) {
        $restored = (docker exec $name psql -U autostock -d autostock -tAc "select count(*) from $t" 2>$null)
        if ($LASTEXITCODE -ne 0) { $restored = "없음"; $failed = $true }
        $live = (docker exec $LiveContainer psql -U autostock -d autostock -tAc "select count(*) from $t" 2>$null)
        if ($LASTEXITCODE -ne 0) { $live = "-" }
        $verdict = if ($restored -eq "없음") { "실패(테이블 없음)" } elseif ("$restored" -eq "$live") { "일치" } else { "차이(백업 뒤 변경분이면 정상)" }
        Write-Host ("{0,-24} {1,12} {2,12}  {3}" -f $t, $restored, $live, $verdict)
    }
    $latestMigration = (docker exec $name psql -U autostock -d autostock -tAc "select max(version::int) from flyway_schema_history where version is not null")
    Write-Host "[.] 복원본 최신 마이그레이션: V$latestMigration"
}
catch {
    Write-Host "[X] 복원 리허설 실패: $($_.Exception.Message)"
    $failed = $true
}
finally {
    docker rm -f $name 2>$null | Out-Null
}

if ($failed) { Write-Host "[X] 결과: 실패"; exit 1 }
Write-Host "[O] 결과: 복원 가능 — RUNBOOK 8절 점검 기록에 날짜와 파일명을 남긴다"
exit 0
