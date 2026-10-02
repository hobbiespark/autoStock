# autoStock DB 전환 — postgres:16-alpine → TimescaleDB HA 이미지(PostgreSQL 17)
# (2026-10-02, aiDoc/db-switch-timescale.md, 결정 D-15·D-16·D-17)
#
# 하는 일 — 앱이 꺼진 장외에만 실행한다:
#   1) 사전 점검: Docker, 앱 꺼짐(8080), 운영 컨테이너·볼륨, 새 볼륨 없음, 새 이미지 받기(가장 오래 걸리는 단계를 먼저)
#   2) 옛 DB 덤프(-Fc) → 목차 확인 → <백업 폴더>\autostock_preswitch_yyyyMMdd_HHmm.dump
#      (backup_db.ps1의 보존 정리 대상 이름이 아니라 지워지지 않는다)
#   3) 옛 DB의 테이블별 행 수 기록
#   4) 옛 컨테이너 중지·삭제(볼륨은 그대로 — 롤백용) → 새 정의로 기동 → healthy 대기 → 버전·확장 확인
#   5) 복원: timescaledb_pre_restore → pg_restore --exit-on-error → timescaledb_post_restore → ANALYZE → 행 수 대조
#   6) pg_stat_statements 확장, compose 파일 교체(docker-compose.yml ← 새 정의, 옛 정의는 docker-compose.pg16.yml)
#   7) 새 형식 백업 1회 + 복원 리허설(-SkipRehearsal로 생략)
# 4~5단계에서 실패하면 스스로 되돌린다: 새 컨테이너·새 볼륨을 지우고 옛 정의로 다시 띄운다(옛 볼륨 그대로).
# 로그: app\logs\db-switch-yyyyMMdd_HHmm.log, 같은 내용을 백업 폴더에도 남긴다.
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\switch_db_timescale.ps1
#         (옵션) -CheckOnly(사전 점검과 이미지 받기만)  -SkipRehearsal  -BackupDir E:\backup\autostock
#                -ReplaceNewVolume(이전 시도의 새 볼륨이 남아 있을 때만 — 그 볼륨을 지우고 다시 만든다)
# 전환 뒤 되돌리기: scripts\rollback_db_pg16.ps1
param(
    [string]$BackupDir = "D:\backup\autostock",
    [string]$Container = "autostock-db",
    [switch]$CheckOnly,
    [switch]$SkipRehearsal,
    [switch]$ReplaceNewVolume
)

# 오류 처리: 네이티브 명령(docker)은 종료 코드로 판정한다. $ErrorActionPreference를 "Stop"으로 두지 않는다 —
# Windows PowerShell 5.1은 네이티브 명령의 stderr를 오류 레코드로 바꿔 "Stop"에서 스크립트를 멈춘다(backup_db.ps1과 같다).
$ErrorActionPreference = "Continue"

# compose 정의(infra\docker-compose.timescale.yml)와 테스트 이미지(PostgresTestDatabase.IMAGE)와 같아야 한다
$NewImage = "timescale/timescaledb-ha:pg17.11-ts2.30.2@sha256:2fcc39a5d4c8a65f58691ef92c7819df5773db72397b2dd8493f114659b519c2"
$ExpectedTimescale = "2.30.2"
$ComposeProject = "infra"        # compose 프로젝트 — infra\docker-compose.yml의 폴더 이름(start_autostock.bat가 -p 없이 쓴다)
$OldVolumeSuffix = "_pgdata"     # 옛 정의의 볼륨 pgdata → <프로젝트>_pgdata
$NewVolumeSuffix = "_pgdata17"   # 새 정의의 볼륨 pgdata17

$repoRoot = Split-Path -Parent $PSScriptRoot
$infraDir = Join-Path $repoRoot "infra"
$composeMain = Join-Path $infraDir "docker-compose.yml"
$composeNew = Join-Path $infraDir "docker-compose.timescale.yml"
$composeRollback = Join-Path $infraDir "docker-compose.pg16.yml"
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
$logDir = Join-Path (Join-Path $repoRoot "app") "logs"
New-Item -ItemType Directory -Force -Path $logDir -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Force -Path $BackupDir -ErrorAction Stop | Out-Null
$logFile = Join-Path $logDir "db-switch-$stamp.log"

$script:dkExit = 0
$script:oldRemoved = $false
$script:newVolume = ""

function Log([string]$message) {
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

function LogLines([object[]]$lines) {
    foreach ($l in $lines) { if ("$l".Trim()) { Log "    $l" } }
}

function Finish([int]$code) {
    Copy-Item -Path $logFile -Destination (Join-Path $BackupDir (Split-Path -Leaf $logFile)) -Force -ErrorAction SilentlyContinue
    exit $code
}

function Fail([string]$message) {
    Log "[X] $message"
    if ($script:oldRemoved) {
        Restore-OldDatabase $message
    }
    Finish 1
}

# docker 실행 — 표준 출력·오류를 문자열 배열로 돌려주고 종료 코드는 $script:dkExit에 남긴다.
# 인자는 $args로 받는다(param 블록을 두면 -U 같은 인자를 이 함수의 매개변수로 해석한다).
function Dk {
    $lines = @(& $script:dockerExe @args 2>&1 | ForEach-Object { "$_" })
    $script:dkExit = $LASTEXITCODE
    return $lines
}

# SQL은 파일로 넘긴다 — Windows PowerShell 5.1은 네이티브 인자 안의 큰따옴표를 망가뜨린다.
function Invoke-DbSql([string]$Sql, [string]$Name) {
    $local = Join-Path ([System.IO.Path]::GetTempPath()) "autostock_switch_$Name.sql"
    [System.IO.File]::WriteAllText($local, $Sql, (New-Object System.Text.UTF8Encoding($false)))
    $remote = "/tmp/autostock_switch_$Name.sql"
    $copied = Dk cp $local "${Container}:$remote"
    if ($script:dkExit -ne 0) {
        LogLines $copied
        return @()
    }
    $null = Dk exec -u 0 $Container chmod 644 $remote   # docker cp는 root 소유로 넣는다 — HA 이미지는 postgres 사용자로 읽는다
    $lines = Dk exec $Container psql -X -q -v ON_ERROR_STOP=1 -U autostock -d autostock -tA -f $remote
    $code = $script:dkExit
    $null = Dk exec -u 0 $Container rm -f $remote
    Remove-Item -Path $local -Force -ErrorAction SilentlyContinue
    $script:dkExit = $code
    return $lines
}

# 테이블별 행 수(public 스키마의 일반·파티션 테이블) — "이름|행 수" 줄을 순서 있는 표로 돌려준다
$CountSql = @'
SELECT c.relname || '|' || (xpath('/row/c/text()',
       query_to_xml(format('select count(*) as c from public.%I', c.relname), false, true, '')))[1]::text
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
ORDER BY c.relname;
'@

function Get-TableCounts([string]$Name) {
    $lines = Invoke-DbSql $CountSql $Name
    if ($script:dkExit -ne 0) {
        LogLines $lines
        return $null
    }
    $counts = [ordered]@{}
    foreach ($l in $lines) {
        $parts = "$l".Split("|")
        if ($parts.Count -eq 2) { $counts[$parts[0]] = $parts[1] }
    }
    return $counts
}

function Wait-Healthy([int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $health = ""
    do {
        Start-Sleep -Seconds 3
        $health = (Dk inspect -f "{{.State.Health.Status}}" $Container) -join ""
    } until ($health -eq "healthy" -or (Get-Date) -gt $deadline)
    return ($health -eq "healthy")
}

# 4~5단계 실패 시 되돌림: 새 컨테이너·새 볼륨을 지우고 옛 정의(docker-compose.yml, 아직 바꾸기 전)로 다시 띄운다.
function Restore-OldDatabase([string]$reason) {
    $script:oldRemoved = $false   # 되돌림 중 실패가 다시 되돌림을 부르지 않게
    Log "[!] 되돌림 시작 — 원인: $reason"
    $tail = Dk logs --tail 30 $Container
    Log "[.] 새 컨테이너 로그 끝부분:"
    LogLines $tail
    $null = Dk rm -f $Container
    if ($script:newVolume) {
        $removed = Dk volume rm $script:newVolume
        if ($script:dkExit -eq 0) { Log "[.] 실패한 시도의 새 볼륨 삭제: $($script:newVolume)" } else { LogLines $removed }
    }
    $up = Dk compose -p $ComposeProject -f $composeMain up -d postgres
    if ($script:dkExit -ne 0) {
        LogLines $up
        Log "[X] 옛 DB 재기동 실패 — 수동 조치: docker compose -p $($ComposeProject) -f infra\docker-compose.yml up -d postgres"
        return
    }
    if (Wait-Healthy 120) {
        Log "[O] 옛 DB(postgres:16-alpine, 볼륨 그대로)로 되돌렸다 — 앱은 지금 코드 그대로 기동할 수 있다"
    } else {
        Log "[X] 옛 DB가 healthy가 되지 않았다 — docker logs $Container 확인"
    }
}

Log "[.] DB 전환 시작 — 로그 $logFile"

# ── 1) 사전 점검 ─────────────────────────────────────────────────────────
$dockerCmd = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $dockerCmd) { Fail "docker 명령이 없다 — Docker Desktop 설치·실행 확인" }
$script:dockerExe = $dockerCmd.Source
$null = Dk info
if ($script:dkExit -ne 0) { Fail "Docker 엔진이 응답하지 않는다 — Docker Desktop을 켠 뒤 다시 실행" }
Log "[O] Docker 엔진 응답"

# compose 교체가 전환의 마지막 단계다 — 교체돼 있으면 끝난 것이다
if (-not (Test-Path $composeNew)) {
    if ((Test-Path $composeRollback) -and (Select-String -Path $composeMain -Pattern "timescaledb-ha" -Quiet)) {
        Log "[O] 이미 전환됨(docker-compose.yml이 새 정의) — 할 일 없음"
        Finish 0
    }
    Fail "새 정의 파일이 없다: $composeNew"
}
if (Select-String -Path $composeMain -Pattern "timescaledb-ha" -Quiet) {
    Fail "docker-compose.yml이 이미 새 정의인데 docker-compose.timescale.yml도 있다 — 파일 상태를 확인한다"
}

# 앱이 DB를 쓰는 중이면 덤프 뒤 변경분을 잃는다
if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    $listen = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($listen) { Fail "앱이 실행 중이다(127.0.0.1:8080) — scripts\stop_autostock.bat로 끄고 다시 실행" }
}
Log "[O] 앱 꺼짐 확인(8080 대기 없음)"

$oldVolume = $ComposeProject + $OldVolumeSuffix
$script:newVolume = $ComposeProject + $NewVolumeSuffix

$image = (Dk inspect -f "{{.Config.Image}}" $Container) -join ""
if ($script:dkExit -ne 0) {
    # 이전 실행이 옛 컨테이너를 지운 직후 끊겼을 수 있다 — 데이터는 옛 볼륨에 그대로 있으니 옛 정의로 다시 띄운다
    $null = Dk volume inspect $oldVolume
    if ($script:dkExit -ne 0) { Fail "운영 DB 컨테이너($Container)도 옛 볼륨($oldVolume)도 없다 — 멈춘다" }
    Log "[!] 운영 DB 컨테이너가 없다 — 옛 정의로 다시 띄운다(옛 볼륨 $oldVolume 그대로)"
    $up = Dk compose -p $ComposeProject -f $composeMain up -d postgres
    if ($script:dkExit -ne 0) { LogLines $up; Fail "옛 정의 기동 실패" }
    if (-not (Wait-Healthy 120)) { Fail "옛 DB가 healthy가 되지 않았다 — docker logs $Container 확인" }
    $image = (Dk inspect -f "{{.Config.Image}}" $Container) -join ""
}
if ($image -like "*timescaledb-ha*") {
    # compose는 아직 옛 정의인데 새 컨테이너가 떠 있다 = 이전 전환이 복원 도중 끊겼다(창을 닫았거나 PC가 꺼짐)
    if (-not $ReplaceNewVolume) {
        Fail ("이전 전환이 중간에 끊긴 흔적이다(새 컨테이너가 떠 있는데 compose는 옛 정의). 새 DB에는 덤프 일부만 있을 수 있다 — " +
              "-ReplaceNewVolume을 붙여 다시 실행하면 새 컨테이너·새 볼륨을 지우고 옛 DB로 되돌린 뒤 처음부터 한다")
    }
    Log "[.] -ReplaceNewVolume: 끊긴 시도의 새 컨테이너·새 볼륨을 지우고 옛 DB로 되돌린 뒤 처음부터 한다"
    $null = Dk rm -f $Container
    $removed = Dk volume rm $script:newVolume
    if ($script:dkExit -ne 0) { LogLines $removed }
    $up = Dk compose -p $ComposeProject -f $composeMain up -d postgres
    if ($script:dkExit -ne 0) { LogLines $up; Fail "옛 정의 기동 실패" }
    if (-not (Wait-Healthy 120)) { Fail "옛 DB가 healthy가 되지 않았다 — docker logs $Container 확인" }
    $image = (Dk inspect -f "{{.Config.Image}}" $Container) -join ""
}
if ($image -ne "postgres:16-alpine") { Fail "예상한 옛 이미지가 아니다: $image (postgres:16-alpine 기대) — 멈춘다" }

# 라벨은 JSON으로 받아 읽는다 — 템플릿에 큰따옴표를 넣으면 Windows PowerShell 5.1이 인자를 망가뜨린다
$labelsJson = (Dk inspect -f "{{json .Config.Labels}}" $Container) -join ""
$labelProject = ""
if ($script:dkExit -eq 0 -and $labelsJson) {
    $labels = $labelsJson | ConvertFrom-Json
    $labelProject = "$($labels.'com.docker.compose.project')"
}
if ($labelProject -ne $ComposeProject) {
    Fail ("compose 프로젝트가 {0}이 아니다(라벨 '{1}') — start_autostock.bat가 기본 이름({0})을 쓰고 롤백 경로가 이 이름에 기대므로 멈춘다" -f $ComposeProject, $labelProject)
}
$mounts = (Dk inspect -f "{{range .Mounts}}{{.Type}}:{{.Name}}:{{.Destination}};{{end}}" $Container) -join ""
if ($mounts -notlike "*volume:${oldVolume}:/var/lib/postgresql/data*") {
    Fail "데이터 볼륨이 예상($oldVolume → /var/lib/postgresql/data)과 다르다: $mounts — 멈춘다"
}
Log "[O] 운영 컨테이너 $Container — 이미지 $image, 프로젝트 $($ComposeProject), 볼륨 $oldVolume"

$null = Dk volume inspect $script:newVolume
if ($script:dkExit -eq 0) {
    if (-not $ReplaceNewVolume) {
        Fail ("새 볼륨 {0}이 이미 있다(이전 시도·롤백의 흔적). 안에 전환 뒤 데이터가 있을 수 있다 — 확인 뒤 -ReplaceNewVolume로 다시 실행" -f $script:newVolume)
    }
    $removed = Dk volume rm $script:newVolume
    if ($script:dkExit -ne 0) { LogLines $removed; Fail "새 볼륨 삭제 실패 — 쓰는 컨테이너가 있는지 확인" }
    Log "[.] -ReplaceNewVolume: 남아 있던 새 볼륨 $($script:newVolume) 삭제"
}

$running = (Dk inspect -f "{{.State.Running}}" $Container) -join ""
if ($running -ne "true") {
    Log "[.] 운영 컨테이너가 멈춰 있어 시작한다"
    $null = Dk start $Container
    if (-not (Wait-Healthy 120)) { Fail "옛 DB가 healthy가 되지 않았다 — docker logs $Container 확인" }
}

Log "[.] 새 이미지 받는 중(약 870MB, 처음 한 번만) — $NewImage"
$pulled = Dk pull $NewImage
if ($script:dkExit -ne 0) { LogLines $pulled; Fail "새 이미지 받기 실패 — 네트워크 확인 뒤 다시 실행(아직 아무것도 바꾸지 않았다)" }
Log "[O] 새 이미지 준비: $(($pulled | Select-Object -Last 1))"

if ($CheckOnly) {
    Log "[O] 사전 점검 통과 — 전환 준비됨(-CheckOnly라 여기서 멈춘다)"
    Finish 0
}

# ── 2) 옛 DB 덤프 ────────────────────────────────────────────────────────
$dumpName = "autostock_preswitch_$stamp.dump"
$dumpHost = Join-Path $BackupDir $dumpName
$dumpInOld = "/tmp/$dumpName"
$out = Dk exec $Container pg_dump -U autostock -d autostock -Fc -f $dumpInOld
if ($script:dkExit -ne 0) { LogLines $out; Fail "pg_dump 실패" }
$toc = @(Dk exec $Container pg_restore --list $dumpInOld | Where-Object { $_ -and -not $_.StartsWith(";") })
if ($script:dkExit -ne 0 -or $toc.Count -eq 0) { Fail "덤프 목차 확인 실패(항목 $($toc.Count)개)" }
$out = Dk cp "${Container}:$dumpInOld" $dumpHost
if ($script:dkExit -ne 0) { LogLines $out; Fail "덤프를 꺼내지 못했다(docker cp)" }
$null = Dk exec $Container rm -f $dumpInOld
$dumpSize = (Get-Item $dumpHost -ErrorAction Stop).Length
if ($dumpSize -le 0) { Fail "덤프 파일 크기 0: $dumpHost" }
Log ("[O] 옛 DB 덤프 {0} ({1:N0} bytes, 목차 {2}개)" -f $dumpHost, $dumpSize, $toc.Count)

# ── 3) 옛 DB 행 수 ───────────────────────────────────────────────────────
$oldCounts = Get-TableCounts "count_old"
if ($null -eq $oldCounts -or $oldCounts.Count -eq 0) { Fail "옛 DB 행 수를 읽지 못했다" }
$oldVersion = (Dk exec $Container psql -X -U autostock -d autostock -tAc "show server_version") -join ""
Log "[O] 옛 DB(PostgreSQL $oldVersion) 테이블 $($oldCounts.Count)개 행 수 기록"
foreach ($k in $oldCounts.Keys) { Log ("    {0,-28} {1,10}" -f $k, $oldCounts[$k]) }

# ── 4) 옛 컨테이너 삭제(볼륨 보존) → 새 정의로 기동 ──────────────────────
$out = Dk stop $Container
if ($script:dkExit -ne 0) { LogLines $out; Fail "옛 컨테이너 중지 실패" }
$out = Dk rm $Container
if ($script:dkExit -ne 0) { LogLines $out; Fail "옛 컨테이너 삭제 실패" }
$script:oldRemoved = $true
Log "[O] 옛 컨테이너 삭제(볼륨 $oldVolume 그대로 — 롤백용)"

$out = Dk compose -p $ComposeProject -f $composeNew up -d postgres
if ($script:dkExit -ne 0) { LogLines $out; Fail "새 정의 기동 실패(docker compose up)" }
Log "[.] 새 DB 초기화 대기(initdb·튜닝·확장 생성, 보통 1분 안)"
if (-not (Wait-Healthy 300)) { Fail "새 DB가 300초 안에 healthy가 되지 않았다" }
Log "[O] 새 DB healthy"

$versionSql = @'
SELECT 'server|' || current_setting('server_version');
SELECT 'ext|' || extname || '|' || extversion FROM pg_extension ORDER BY extname;
SELECT 'setting|' || name || '|' || current_setting(name) FROM pg_settings
 WHERE name IN ('shared_preload_libraries', 'data_checksums', 'TimeZone', 'shared_buffers', 'max_connections',
                'timescaledb.telemetry_level')
 ORDER BY name;
SELECT 'collate|' || datcollate FROM pg_database WHERE datname = current_database();
'@
$versions = Invoke-DbSql $versionSql "versions"
if ($script:dkExit -ne 0) { LogLines $versions; Fail "새 DB 버전 조회 실패" }
LogLines $versions
if (-not ($versions -contains "ext|timescaledb|$ExpectedTimescale")) { Fail "새 DB에 timescaledb $ExpectedTimescale 확장이 없다" }
if (-not ($versions -like "server|17.*")) { Fail "새 DB가 PostgreSQL 17이 아니다" }

# ── 5) 복원 ──────────────────────────────────────────────────────────────
$dumpInNew = "/tmp/$dumpName"
$out = Dk cp $dumpHost "${Container}:$dumpInNew"
if ($script:dkExit -ne 0) { LogLines $out; Fail "덤프를 새 컨테이너로 복사하지 못했다" }
$null = Dk exec -u 0 $Container chmod 644 $dumpInNew
$out = Invoke-DbSql "SELECT timescaledb_pre_restore();" "pre_restore"
if ($script:dkExit -ne 0) { LogLines $out; Fail "timescaledb_pre_restore 실패" }
$out = Dk exec $Container pg_restore -U autostock -d autostock --no-owner --exit-on-error $dumpInNew
$restoreExit = $script:dkExit
LogLines $out
$post = Invoke-DbSql "SELECT timescaledb_post_restore();" "post_restore"
if ($restoreExit -ne 0) { Fail "pg_restore 실패(exit $restoreExit)" }
if ($script:dkExit -ne 0) { LogLines $post; Fail "timescaledb_post_restore 실패" }
$null = Dk exec -u 0 $Container rm -f $dumpInNew
$out = Invoke-DbSql "ANALYZE;" "analyze"
if ($script:dkExit -ne 0) { LogLines $out; Fail "ANALYZE 실패" }
Log "[O] 복원 완료(pre_restore → pg_restore → post_restore → ANALYZE)"

$newCounts = Get-TableCounts "count_new"
if ($null -eq $newCounts) { Fail "새 DB 행 수를 읽지 못했다" }
$mismatch = 0
foreach ($k in $oldCounts.Keys) {
    $after = $newCounts[$k]
    $verdict = "일치"
    if ("$after" -ne "$($oldCounts[$k])") { $verdict = "불일치"; $mismatch++ }
    Log ("    {0,-28} {1,10} {2,10}  {3}" -f $k, $oldCounts[$k], $after, $verdict)
}
if ($mismatch -gt 0) { Fail "행 수 불일치 $mismatch개 테이블" }
Log "[O] 행 수 대조: 테이블 $($oldCounts.Count)개 모두 일치"

# ── 6) 마무리 — 느린 질의 통계 확장, compose 파일 교체 ───────────────────
$out = Invoke-DbSql "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" "pg_stat_statements"
if ($script:dkExit -ne 0) { LogLines $out; Log "[!] pg_stat_statements 확장 생성 실패 — 전환에는 지장 없다" }

try {
    Copy-Item -Path $composeMain -Destination $composeRollback -Force -ErrorAction Stop
    Copy-Item -Path $composeNew -Destination $composeMain -Force -ErrorAction Stop
    Remove-Item -Path $composeNew -Force -ErrorAction Stop
} catch {
    Fail "compose 파일 교체 실패: $($_.Exception.Message)"
}
$script:oldRemoved = $false   # 여기부터는 자동 되돌림 대상이 아니다(되돌리기는 rollback_db_pg16.ps1)
Log "[O] compose 교체: docker-compose.yml = 새 정의, docker-compose.pg16.yml = 옛 정의(롤백용)"

# ── 7) 새 형식 백업 + 복원 리허설 ────────────────────────────────────────
if ($SkipRehearsal) {
    Log "[.] -SkipRehearsal: 백업·복원 리허설 생략"
} else {
    Log "[.] 새 DB 백업(backup_db.ps1)"
    & (Join-Path $PSScriptRoot "backup_db.ps1") -BackupDir $BackupDir -Container $Container
    if ($LASTEXITCODE -ne 0) {
        Log "[!] 새 DB 백업 실패 — backup.log 확인(전환 자체는 끝났다)"
    } else {
        Log "[.] 복원 리허설(restore_check.ps1 — 같은 이미지 임시 컨테이너)"
        & (Join-Path $PSScriptRoot "restore_check.ps1") -BackupDir $BackupDir -LiveContainer $Container
        if ($LASTEXITCODE -ne 0) { Log "[!] 복원 리허설 실패 — 출력 확인(전환 자체는 끝났다)" } else { Log "[O] 복원 리허설 통과" }
    }
}

Log "[O] 전환 완료 — PostgreSQL 17 + TimescaleDB $ExpectedTimescale. 다음: scripts\start_autostock.bat로 앱 기동"
Log "    되돌리기: scripts\rollback_db_pg16.ps1 (옛 볼륨 $oldVolume, 전환 전 덤프 $dumpHost)"
Finish 0
