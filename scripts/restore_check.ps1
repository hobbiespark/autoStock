# autoStock 백업 복원 리허설 (Phase 0.8 — aiDoc/backup.md, RUNBOOK 8절, 월 1회 권장)
#
# 동작: 운영 컨테이너와 같은 이미지로 임시 컨테이너를 띄워 최신(또는 지정한) 덤프를 pg_restore → 테이블별 행 수를
#       운영 DB와 나란히 보여준다. 복원 실패·테이블 누락이면 종료 코드 1. 행 수 차이는 백업 뒤 운영 DB가 바뀐 만큼이라 경고만 한다
#       (장 마감 뒤 백업 직후에 돌리면 보통 같다). 운영 DB에는 읽기(count)만 한다. 임시 컨테이너는 끝나면 지운다.
# 2026-10-02 TimescaleDB 전환(aiDoc/db-switch-timescale.md):
#   - 이미지는 운영 컨테이너에서 읽는다(-Image로 지정 가능). TimescaleDB 덤프는 같은 확장 버전으로 복원해야 한다.
#   - 임시 DB에 timescaledb가 있으면 timescaledb_pre_restore → pg_restore → timescaledb_post_restore 순서로 복원한다
#     (없이 복원하면 "could not find hypertable" 오류 — 실측).
#   - 비교 테이블은 운영 DB의 public 테이블 전부(새 테이블이 생겨도 따로 고치지 않는다).
#   - -Lab: 복원한 컨테이너를 지우지 않고 랩(autostock-lab, 127.0.0.1:5433)으로 남긴다 — 결정 D-19, 학습·실험은 운영 DB가 아니라
#     여기서 한다. 매번 최신 백업으로 새로 만든다(있던 랩은 지운다). 끄기: docker rm -f autostock-lab
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\restore_check.ps1
#         (옵션) -DumpFile D:\backup\autostock\autostock_20261001_1630.dump  -BackupDir D:\backup\autostock  -Lab
param(
    [string]$BackupDir = "D:\backup\autostock",
    [string]$DumpFile = "",
    [string]$LiveContainer = "autostock-db",
    [string]$Image = "",
    [switch]$Lab
)

# 오류 처리: 네이티브 명령(docker)은 종료 코드($LASTEXITCODE)로 판정한다. $ErrorActionPreference를 "Stop"으로 두지 않는다 —
# Windows PowerShell 5.1은 네이티브 명령의 stderr를 리다이렉트(2>$null)하면 오류 레코드로 바꿔 "Stop"에서 스크립트를 멈춘다
# (예: 없는 컨테이너 docker rm -f). cmdlet은 -ErrorAction Stop을 개별로 준다.
$ErrorActionPreference = "Continue"
$name = "autostock-restore-check"
if ($Lab) { $name = "autostock-lab" }
# public 스키마의 일반·파티션 테이블 이름 — 큰따옴표 없는 SQL만 인자로 넘긴다(PowerShell 5.1 인자 전달 함정)
$tableSql = "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p') order by 1"

if (-not $DumpFile) {
    $latest = Get-ChildItem -Path $BackupDir -Filter "autostock_*.dump" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { Write-Host "[X] 백업 파일 없음: $BackupDir"; exit 1 }
    $DumpFile = $latest.FullName
}
Write-Host "[.] 복원 대상: $DumpFile"
$versionFile = $DumpFile -replace '\.dump$', '.versions.txt'
if (Test-Path $versionFile) {
    Get-Content -Path $versionFile -Encoding UTF8 | Where-Object { $_ -like "image *" -or $_ -like "extension timescaledb *" -or $_ -like "server *" } |
            ForEach-Object { Write-Host "    백업 당시 $_" }
}

if (-not $Image) {
    $Image = (docker inspect -f "{{.Config.Image}}" $LiveContainer 2>$null) -join ""
    if ($LASTEXITCODE -ne 0 -or -not $Image) {
        Write-Host "[X] 운영 컨테이너($LiveContainer)가 없어 이미지를 정할 수 없다 — -Image로 지정"
        exit 1
    }
}
Write-Host "[.] 복원 이미지: $Image"

$failed = $false
try {
    docker rm -f $name 2>$null | Out-Null
    $runArgs = @("run", "-d", "--name", $name, "-e", "POSTGRES_USER=autostock", "-e", "POSTGRES_PASSWORD=restore-check",
                 "-e", "POSTGRES_DB=autostock", "-e", "TIMESCALEDB_TELEMETRY=off", "-e", "TS_TUNE_MEMORY=1GB")
    # TS_TUNE_MAX_CONNS는 주지 않는다 — timescaledb-tune이 25 미만을 거부해 초기화가 죽는다(리허설 실측 "maxConns must be 0 OR >= 25")
    if ($Lab) { $runArgs += @("-p", "127.0.0.1:5433:5432") }
    $runArgs += $Image
    docker @runArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "임시 컨테이너 기동 실패" }

    # TCP로 확인한다 — 초기화 중 임시 서버는 소켓만 열어(listen_addresses='') 초기화가 끝나야 응답한다
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        docker exec $name pg_isready -h 127.0.0.1 -U autostock -d autostock 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        if (((docker inspect -f "{{.State.Running}}" $name 2>$null) -join "") -eq "false") {
            docker logs --tail 20 $name 2>&1 | ForEach-Object { Write-Host "    $_" }
            throw "임시 컨테이너가 초기화 중 멈췄다(위 로그)"
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "임시 DB 준비 시간 초과(120초)" }

    docker cp $DumpFile "${name}:/tmp/restore.dump"
    if ($LASTEXITCODE -ne 0) { throw "덤프 복사 실패" }
    docker exec -u 0 $name chmod 644 /tmp/restore.dump | Out-Null   # docker cp는 root 소유로 넣는다

    $timescale = (docker exec $name psql -X -U autostock -d autostock -tAc "select count(*) from pg_extension where extname = 'timescaledb'") -join ""
    $useTimescale = ($timescale -eq "1")
    if ($useTimescale) {
        docker exec $name psql -X -q -U autostock -d autostock -tAc "select timescaledb_pre_restore()" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "timescaledb_pre_restore 실패" }
    }
    docker exec $name pg_restore -U autostock -d autostock --no-owner --exit-on-error /tmp/restore.dump
    $restoreExit = $LASTEXITCODE
    if ($useTimescale) {
        docker exec $name psql -X -q -U autostock -d autostock -tAc "select timescaledb_post_restore()" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "timescaledb_post_restore 실패" }
    }
    if ($restoreExit -ne 0) { throw "pg_restore 실패(exit $restoreExit)" }
    if ($useTimescale) { Write-Host "[O] pg_restore 완료(TimescaleDB pre/post restore 포함)" } else { Write-Host "[O] pg_restore 완료" }

    $tables = @(docker exec $LiveContainer psql -X -U autostock -d autostock -tAc $tableSql 2>$null | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $tables.Count -eq 0) {
        Write-Host "[!] 운영 DB 테이블 목록을 읽지 못했다 — 복원본 기준으로 센다"
        $tables = @(docker exec $name psql -X -U autostock -d autostock -tAc $tableSql | Where-Object { $_ })
    }
    Write-Host ("{0,-24} {1,12} {2,12}  {3}" -f "table", "restored", "live", "판정")
    foreach ($t in $tables) {
        $restored = (docker exec $name psql -X -U autostock -d autostock -tAc "select count(*) from public.$t" 2>$null) -join ""
        if ($LASTEXITCODE -ne 0) { $restored = "없음"; $failed = $true }
        $live = (docker exec $LiveContainer psql -X -U autostock -d autostock -tAc "select count(*) from public.$t" 2>$null) -join ""
        if ($LASTEXITCODE -ne 0) { $live = "-" }
        $verdict = if ($restored -eq "없음") { "실패(테이블 없음)" } elseif ("$restored" -eq "$live") { "일치" } else { "차이(백업 뒤 변경분이면 정상)" }
        Write-Host ("{0,-24} {1,12} {2,12}  {3}" -f $t, $restored, $live, $verdict)
    }
    $latestMigration = (docker exec $name psql -X -U autostock -d autostock -tAc "select max(version::int) from flyway_schema_history where version is not null") -join ""
    Write-Host "[.] 복원본 최신 마이그레이션: V$latestMigration"
    if ($useTimescale) {
        $tsVersion = (docker exec $name psql -X -U autostock -d autostock -tAc "select extversion from pg_extension where extname = 'timescaledb'") -join ""
        Write-Host "[.] 복원본 TimescaleDB $tsVersion"
    }
}
catch {
    Write-Host "[X] 복원 리허설 실패: $($_.Exception.Message)"
    $failed = $true
}
finally {
    if (-not $Lab -or $failed) {
        docker rm -f $name 2>$null | Out-Null
    }
}

if ($failed) { Write-Host "[X] 결과: 실패"; exit 1 }
if ($Lab) {
    Write-Host "[O] 결과: 복원 가능 — 랩 컨테이너 $name 를 남겼다(127.0.0.1:5433, autostock/restore-check). 끄기: docker rm -f $name"
} else {
    Write-Host "[O] 결과: 복원 가능 — RUNBOOK 8절 점검 기록에 날짜와 파일명을 남긴다"
}
exit 0
