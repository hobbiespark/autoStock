# autoStock DB 복원 — 새 PC 이전용(2026-10-24, aiDoc/pc-migration-2026-10-24.md)
#
# 동작: 운영 컨테이너(autostock-db)의 autostock DB에 덤프를 복원한다. 순서는 복원 리허설(restore_check.ps1)과 같다 —
#       timescaledb_pre_restore → pg_restore(--no-owner --exit-on-error) → timescaledb_post_restore.
#       DB에 public 테이블이 이미 있으면 멈춘다(앱이 먼저 떠서 Flyway가 빈 스키마를 만들었을 수 있다). -Force면 DB를 지우고 새로 만든다.
#       덤프 옆에 rowcounts.txt(pc_migration_backup.ps1이 남김)가 있으면 테이블별 행 수를 대조한다.
# 순서: 1) scripts\setup_docker.ps1 — 빈 autostock-db 기동  2) 이 스크립트  3) 앱 기동(Flyway는 덤프에 기록된 버전까지 이미 적용돼 있다)
# 앱은 꺼 둔다(8080이 응답하면 멈춘다). 덤프의 TimescaleDB 버전과 컨테이너 버전이 같아야 한다(.versions.txt — 이미지는 compose에 다이제스트 고정).
#
# 사용: powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\restore_db.ps1 -DumpFile E:\migration_20261024_1100\db\autostock_20261024_1100.dump
param(
    [Parameter(Mandatory = $true)][string]$DumpFile,
    [string]$Container = "autostock-db",
    [switch]$Force
)

# 네이티브 명령(docker)은 종료 코드로 판정한다 — "Stop"이면 PowerShell 5.1이 stderr 출력만으로 멈춘다(backup_db.ps1 참고)
$ErrorActionPreference = "Continue"
# 큰따옴표 없는 SQL만 인자로 넘긴다(PowerShell 5.1 인자 전달 함정)
$tableSql = "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p') order by 1"

function Psql([string]$database, [string]$sql) {
    docker exec $Container psql -X -q -U autostock -d $database -tAc $sql
}

$failed = $false
try {
    if (-not (Test-Path -LiteralPath $DumpFile)) { throw "덤프 파일 없음: $DumpFile" }
    $DumpFile = (Resolve-Path -LiteralPath $DumpFile).Path
    Write-Host "[.] 덤프: $DumpFile"
    $versionFile = $DumpFile -replace '\.dump$', '.versions.txt'
    $dumpImage = ""
    if (Test-Path -LiteralPath $versionFile) {
        $versionLines = @(Get-Content -LiteralPath $versionFile -Encoding UTF8)
        $versionLines | Where-Object { $_ -like "image *" -or $_ -like "server *" -or $_ -like "extension timescaledb *" } |
                ForEach-Object { Write-Host "    백업 당시 $_" }
        $dumpImage = (($versionLines | Where-Object { $_ -like "image *" } | Select-Object -First 1) -replace '^image ', '')
    }

    $image = (docker inspect -f "{{.Config.Image}}" $Container 2>$null) -join ""
    if ($LASTEXITCODE -ne 0 -or -not $image) { throw "컨테이너 $Container 없음 — 먼저 scripts\setup_docker.ps1" }
    Write-Host "[.] 복원할 컨테이너: $Container ($image)"
    if ($dumpImage -and $dumpImage -ne $image) {
        Write-Host "[!] 백업 당시 이미지와 다르다 — TimescaleDB 버전이 다르면 복원이 실패할 수 있다(infra\docker-compose.yml 다이제스트 확인)"
    }

    $appUp = $false
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri "http://127.0.0.1:8080/actuator/health" | Out-Null
        $appUp = $true
    } catch {
        # 응답 없음 = 앱 꺼짐(정상)
    }
    if ($appUp) { throw "앱이 떠 있다(127.0.0.1:8080) — scripts\stop_autostock.bat 뒤 다시" }

    # TCP로 확인한다 — 초기화 중 임시 서버는 소켓만 열어 초기화가 끝나야 응답한다(restore_check.ps1과 같다)
    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        docker exec $Container pg_isready -h 127.0.0.1 -U autostock -d autostock 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw "DB 준비 시간 초과(120초) — docker logs $Container 확인" }

    $existing = @(Psql "autostock" $tableSql | Where-Object { $_ })
    if ($existing.Count -gt 0) {
        if (-not $Force) {
            throw ("autostock DB에 이미 테이블 {0}개가 있다({1} …) — 덮어쓰려면 -Force(DB를 지우고 새로 만든다)" -f
                    $existing.Count, (($existing | Select-Object -First 3) -join ", "))
        }
        Write-Host "[!] -Force — autostock DB를 지우고 새로 만든다(테이블 $($existing.Count)개)"
        Psql "postgres" "select pg_terminate_backend(pid) from pg_stat_activity where datname = 'autostock' and pid <> pg_backend_pid()" | Out-Null
        Psql "postgres" "drop database autostock" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "drop database 실패" }
        Psql "postgres" "create database autostock owner autostock" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "create database 실패" }
    }

    Psql "autostock" "create extension if not exists timescaledb" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "timescaledb 확장 만들기 실패" }
    $tsVersion = (Psql "autostock" "select extversion from pg_extension where extname = 'timescaledb'") -join ""
    Write-Host "[.] 컨테이너 TimescaleDB $tsVersion"

    docker cp $DumpFile "${Container}:/tmp/restore.dump"
    if ($LASTEXITCODE -ne 0) { throw "덤프 복사 실패" }
    docker exec -u 0 $Container chmod 644 /tmp/restore.dump | Out-Null   # docker cp는 root 소유로 넣는다

    Psql "autostock" "select timescaledb_pre_restore()" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "timescaledb_pre_restore 실패" }
    Write-Host "[.] pg_restore 중(분봉이 많아 1~3분)"
    docker exec $Container pg_restore -U autostock -d autostock --no-owner --exit-on-error /tmp/restore.dump
    $restoreExit = $LASTEXITCODE
    Psql "autostock" "select timescaledb_post_restore()" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "timescaledb_post_restore 실패" }
    if ($restoreExit -ne 0) { throw "pg_restore 실패(exit $restoreExit)" }
    Psql "autostock" "analyze" | Out-Null
    Write-Host "[O] pg_restore 완료(TimescaleDB pre/post restore 포함)"

    $expected = @{}
    $rowFile = Join-Path (Split-Path -Parent $DumpFile) "rowcounts.txt"
    if (Test-Path -LiteralPath $rowFile) {
        Get-Content -LiteralPath $rowFile -Encoding UTF8 | Where-Object { $_ -match "`t" } | ForEach-Object {
            $parts = $_.Split("`t")
            $expected[$parts[0].Trim([char]0xFEFF)] = $parts[1]
        }
    } else {
        Write-Host "[.] rowcounts.txt 없음 — 행 수만 보인다"
    }
    $tables = @(Psql "autostock" $tableSql | Where-Object { $_ })
    Write-Host ("{0,-24} {1,12} {2,12}  {3}" -f "table", "restored", "backup", "판정")
    foreach ($t in $tables) {
        $restored = (Psql "autostock" "select count(*) from public.$t") -join ""
        $want = if ($expected.ContainsKey($t)) { $expected[$t] } else { "-" }
        $verdict = if ($want -eq "-") { "" } elseif ("$restored" -eq "$want") { "일치" } else { "차이(덤프 뒤 행 수를 셌으면 정상)" }
        Write-Host ("{0,-24} {1,12} {2,12}  {3}" -f $t, $restored, $want, $verdict)
    }
    foreach ($name in $expected.Keys) {
        if ($tables -notcontains $name) { Write-Host "[X] 백업에 있던 테이블이 없다: $name"; $failed = $true }
    }
    $latestMigration = (Psql "autostock" "select max(version::int) from flyway_schema_history where version is not null") -join ""
    Write-Host "[.] 복원본 최신 마이그레이션: V$latestMigration"
}
catch {
    Write-Host "[X] 복원 실패: $($_.Exception.Message)"
    $failed = $true
}
finally {
    docker exec -u 0 $Container rm -f /tmp/restore.dump 2>$null | Out-Null   # root 소유(docker cp)라 root로 지운다
}

if ($failed) { Write-Host "[X] 결과: 실패"; exit 1 }
Write-Host "[O] 결과: 복원 완료 — 이제 앱을 켠다(scripts\start_autostock.bat)"
exit 0
