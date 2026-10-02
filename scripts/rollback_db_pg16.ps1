# autoStock DB 되돌리기 — TimescaleDB HA(PostgreSQL 17) → 전환 전 postgres:16-alpine
# (2026-10-02, aiDoc/db-switch-timescale.md 6절)
#
# switch_db_timescale.ps1이 끝난 뒤 문제가 생겼을 때 쓴다(전환 도중 실패는 전환 스크립트가 스스로 되돌린다).
# 하는 일 — 앱이 꺼진 장외에만 실행한다:
#   1) 점검: Docker, 앱 꺼짐(8080), 지금 컨테이너가 새 이미지인지, 옛 정의 파일·옛 볼륨이 있는지
#   2) 지금(새) DB를 덤프해 둔다 → <백업 폴더>\autostock_prerollback_yyyyMMdd_HHmm.dump — 전환 뒤 쌓인 데이터 보존
#   3) 새 컨테이너 삭제(새 볼륨은 그대로) → compose 파일을 전환 전으로 되돌림 → 옛 정의로 기동(옛 볼륨, 전환 직전 상태)
#   4) 버전·행 수 출력
# 잃는 것: 전환 뒤 새 DB에만 쌓인 데이터(2단계 덤프에는 남는다). 휴장 중 전환이면 주문 데이터는 없다.
# 주의: 코드에 TimescaleDB가 필요한 마이그레이션(V12 분봉 이후)이 들어 있으면 옛 DB에서 앱이 뜨지 않는다 —
#       그 커밋 이전 코드로 되돌린 뒤 기동한다.
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\rollback_db_pg16.ps1
#         (옵션) -SkipDump(2단계 생략)  -BackupDir E:\backup\autostock
param(
    [string]$BackupDir = "D:\backup\autostock",
    [string]$Container = "autostock-db",
    [switch]$SkipDump
)

$ErrorActionPreference = "Continue"   # 이유는 switch_db_timescale.ps1·backup_db.ps1과 같다

$repoRoot = Split-Path -Parent $PSScriptRoot
$infraDir = Join-Path $repoRoot "infra"
$composeMain = Join-Path $infraDir "docker-compose.yml"
$composeNew = Join-Path $infraDir "docker-compose.timescale.yml"
$composeRollback = Join-Path $infraDir "docker-compose.pg16.yml"
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
$logDir = Join-Path (Join-Path $repoRoot "app") "logs"
New-Item -ItemType Directory -Force -Path $logDir -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Force -Path $BackupDir -ErrorAction Stop | Out-Null
$logFile = Join-Path $logDir "db-rollback-$stamp.log"
$script:dkExit = 0

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
    Finish 1
}

function Dk {
    $lines = @(& $script:dockerExe @args 2>&1 | ForEach-Object { "$_" })
    $script:dkExit = $LASTEXITCODE
    return $lines
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

Log "[.] DB 되돌리기 시작 — 로그 $logFile"

$dockerCmd = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $dockerCmd) { Fail "docker 명령이 없다" }
$script:dockerExe = $dockerCmd.Source
$null = Dk info
if ($script:dkExit -ne 0) { Fail "Docker 엔진이 응답하지 않는다 — Docker Desktop을 켠 뒤 다시 실행" }

if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    $listen = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($listen) { Fail "앱이 실행 중이다(127.0.0.1:8080) — scripts\stop_autostock.bat로 끄고 다시 실행" }
}

$image = (Dk inspect -f "{{.Config.Image}}" $Container) -join ""
$containerExists = ($script:dkExit -eq 0)
if ($containerExists -and $image -eq "postgres:16-alpine") {
    Log "[O] 이미 옛 DB(postgres:16-alpine)다 — 할 일 없음"
    Finish 0
}
if (-not (Test-Path $composeRollback)) { Fail "옛 정의 파일이 없다: $composeRollback — 전환 스크립트로 바꾼 상태가 아니다" }
$project = "infra"
$oldVolume = "${project}_pgdata"
$null = Dk volume inspect $oldVolume
if ($script:dkExit -ne 0) { Fail "옛 볼륨 $oldVolume 이 없다 — 되돌릴 대상이 없다" }

if ($containerExists -and $image -like "*timescaledb-ha*") {
    $running = (Dk inspect -f "{{.State.Running}}" $Container) -join ""
    if ($running -ne "true") {
        $null = Dk start $Container
        $null = Wait-Healthy 120
    }
    if ($SkipDump) {
        Log "[.] -SkipDump: 새 DB 덤프 생략 — 전환 뒤 데이터는 새 볼륨에만 남는다"
    } else {
        $dumpName = "autostock_prerollback_$stamp.dump"
        $dumpHost = Join-Path $BackupDir $dumpName
        $out = Dk exec $Container pg_dump -U autostock -d autostock -Fc -f "/tmp/$dumpName"
        if ($script:dkExit -ne 0) { LogLines $out; Fail "새 DB 덤프 실패 — -SkipDump로 건너뛸 수 있다(전환 뒤 데이터 포기)" }
        $out = Dk cp "${Container}:/tmp/$dumpName" $dumpHost
        if ($script:dkExit -ne 0) { LogLines $out; Fail "새 DB 덤프를 꺼내지 못했다" }
        $null = Dk exec $Container rm -f "/tmp/$dumpName"
        Log ("[O] 새 DB 덤프 {0} ({1:N0} bytes) — 전환 뒤 데이터 보존" -f $dumpHost, (Get-Item $dumpHost).Length)
    }
    $out = Dk rm -f $Container
    if ($script:dkExit -ne 0) { LogLines $out; Fail "새 컨테이너 삭제 실패" }
    Log "[O] 새 컨테이너 삭제(새 볼륨 ${project}_pgdata17 그대로)"
}

try {
    Copy-Item -Path $composeMain -Destination $composeNew -Force -ErrorAction Stop
    Copy-Item -Path $composeRollback -Destination $composeMain -Force -ErrorAction Stop
    Remove-Item -Path $composeRollback -Force -ErrorAction Stop
} catch {
    Fail "compose 파일 되돌리기 실패: $($_.Exception.Message)"
}
Log "[O] compose 되돌림: docker-compose.yml = 옛 정의, docker-compose.timescale.yml = 새 정의(다시 전환할 때 쓴다)"

$out = Dk compose -p $project -f $composeMain up -d postgres
if ($script:dkExit -ne 0) { LogLines $out; Fail "옛 정의 기동 실패(docker compose up)" }
if (-not (Wait-Healthy 120)) { Fail "옛 DB가 healthy가 되지 않았다 — docker logs $Container 확인" }

$version = (Dk exec $Container psql -X -U autostock -d autostock -tAc "show server_version") -join ""
$migration = (Dk exec $Container psql -X -U autostock -d autostock -tAc "select max(version::int) from flyway_schema_history where version is not null") -join ""
Log "[O] 옛 DB 기동: PostgreSQL $version, 최신 마이그레이션 V$migration (볼륨 $oldVolume — 전환 직전 상태)"
Log "    다시 전환하려면: switch_db_timescale.ps1 -ReplaceNewVolume (새 볼륨의 전환 뒤 데이터는 위 덤프로 남겼다)"
Log "    코드에 TimescaleDB가 필요한 마이그레이션이 있으면 그 이전 커밋으로 되돌린 뒤 앱을 기동한다"
Finish 0
