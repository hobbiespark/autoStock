# autoStock PC 이전 백업 (2026-10-24 — aiDoc/pc-migration-2026-10-24.md)
#
# 새 PC로 옮길 것을 한 폴더에 모은다. 하는 일(순서):
#   1) git — 커밋 안 된 변경이 있으면 알린다(멈추지 않음). -Push면 git push origin main. 저장소 전체 번들(git bundle --all) —
#      푸시가 안 돼도 새 PC에서 이 파일로 clone할 수 있다.
#   2) DB — scripts\backup_db.ps1로 새 덤프(+버전 기록)를 만들고 이 폴더로 복사. 테이블별 행 수(rowcounts.txt)를 남긴다 —
#      새 PC에서 scripts\restore_db.ps1이 대조한다.
#   3) git 밖 파일 — .env, 키움 키 파일(*_appkey*.txt·*_secretkey*.txt), STRATEGY.md·BACKTEST_REPORT.md, data\, app\logs\,
#      docs\measured\*.log, .vscode\, (있으면) D:\keys
#   4) MANIFEST.csv(파일별 크기·SHA-256)와 migration.log
# 결과: D:\backup\autostock\migration_yyyyMMdd_HHmm\
#       비밀 값(.env·키 파일)이 들어 있다 — 외장 디스크로 옮기고, 클라우드 공유·메신저로 보내지 않는다.
#
# 사용: powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\pc_migration_backup.ps1 -Push
#       (옵션) -BackupRoot E:\autostock-backup  -KeysDir D:\keys  -SkipData
# 앱은 켜 둬도 된다(pg_dump는 한 시점의 일관된 사본). 행 수는 덤프 직후에 세므로 그 사이 바뀐 만큼은 복원 때 "차이"로 보인다.
param(
    [string]$Repo = "",
    [string]$BackupRoot = "D:\backup\autostock",
    [string]$KeysDir = "D:\keys",
    [string]$Container = "autostock-db",
    [switch]$Push,
    [switch]$SkipData
)

# 네이티브 명령(git·docker·robocopy)은 종료 코드로 판정한다 — "Stop"이면 PowerShell 5.1이 stderr 출력만으로 멈춘다(backup_db.ps1 참고)
$ErrorActionPreference = "Continue"
if (-not $Repo) { $Repo = Split-Path -Parent $PSScriptRoot }
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
$out = Join-Path $BackupRoot "migration_$stamp"
New-Item -ItemType Directory -Force -Path $out -ErrorAction Stop | Out-Null
$logFile = Join-Path $out "migration.log"
$failures = New-Object System.Collections.Generic.List[string]

function Log([string]$message) {
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8 -ErrorAction Stop
}

function Fail([string]$message) {
    Log "[X] $message"
    $failures.Add($message)
}

# robocopy 종료 코드 0~7은 성공(복사한 것 있음/없음/추가 파일), 8 이상은 실패한 파일이 있다.
# *.lock은 뺀다 — 앱이 떠 있으면 app\logs\autostock.instance.lock(단일 실행 잠금)이 잠겨 있어 읽을 수 없고, 새 PC에 필요 없다.
function CopyDir([string]$src, [string]$dst, [string]$label) {
    if (-not (Test-Path $src)) { Log "[.] 없음(건너뜀): $label"; return }
    robocopy $src $dst /E /R:1 /W:1 /XF *.lock /NP /NFL /NDL /NJH /NJS | Out-Null
    $rc = $LASTEXITCODE
    $srcCount = @(Get-ChildItem -Path $src -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -ne ".lock" }).Count
    $dstCount = @(Get-ChildItem -Path $dst -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    if ($rc -ge 8) { Fail ("{0}: robocopy 실패 코드 {1} (원본 {2}개 / 복사 {3}개)" -f $label, $rc, $srcCount, $dstCount) }
    else { Log ("[O] {0}: {1}개 파일 복사(원본 {2}개)" -f $label, $dstCount, $srcCount) }
}

Log "[.] PC 이전 백업 시작 — 저장소 $Repo, 결과 $out"
Set-Location $Repo

# ── 1) git ──────────────────────────────────────────────────────────────────────
$dirty = @(git status --porcelain 2>$null | Where-Object { $_ -and $_ -notmatch 'spring-petclinic' })
if ($dirty.Count -gt 0) {
    Log "[!] 커밋 안 된 변경 $($dirty.Count)건 — 푸시·번들에 들어가지 않는다(필요하면 커밋 뒤 다시 실행):"
    $dirty | ForEach-Object { Log "      $_" }
} else {
    Log "[O] 커밋 안 된 변경 없음(spring-petclinic 제외)"
}
if ($Push) {
    $pushOutput = @(git push origin main 2>&1 | ForEach-Object { "$_" })
    $pushExit = $LASTEXITCODE
    $pushOutput | ForEach-Object { Log "      $_" }
    if ($pushExit -ne 0) { Fail "git push 실패(exit $pushExit) — 번들(autoStock.bundle)로도 옮길 수 있다" }
    else { Log "[O] git push origin main 완료" }
}
$head = (git rev-parse --short HEAD 2>$null) -join ""
$branchLine = (git status -sb 2>$null | Select-Object -First 1) -join ""
Log "[.] HEAD $head — $branchLine"
$bundle = Join-Path $out "autoStock.bundle"
$bundleOutput = @(git bundle create $bundle --all 2>&1 | ForEach-Object { "$_" })
if ($LASTEXITCODE -ne 0) {
    $bundleOutput | ForEach-Object { Log "      $_" }
    Fail "git bundle 실패(exit $LASTEXITCODE)"
} else {
    git bundle verify $bundle 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "git bundle verify 실패" }
    else { Log ("[O] git 번들 {0} ({1:N0} bytes, 모든 브랜치·태그)" -f (Split-Path -Leaf $bundle), (Get-Item $bundle).Length) }
}

# ── 2) DB ───────────────────────────────────────────────────────────────────────
$dbDir = Join-Path $out "db"
New-Item -ItemType Directory -Force -Path $dbDir -ErrorAction Stop | Out-Null
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repo "scripts\backup_db.ps1") -BackupDir $BackupRoot
if ($LASTEXITCODE -ne 0) {
    Fail "DB 백업 실패 — $BackupRoot\backup.log 확인(Docker Desktop·컨테이너 $Container 실행 여부)"
} else {
    $dump = Get-ChildItem -Path $BackupRoot -Filter "autostock_*.dump" |
            Where-Object { $_.Name -match '^autostock_\d{8}_\d{4}\.dump$' } | Sort-Object Name -Descending | Select-Object -First 1
    Copy-Item -Path $dump.FullName -Destination $dbDir -Force -ErrorAction Stop
    $versionFile = $dump.FullName -replace '\.dump$', '.versions.txt'
    if (Test-Path $versionFile) { Copy-Item -Path $versionFile -Destination $dbDir -Force -ErrorAction Stop }
    Log ("[O] DB 덤프 {0} ({1:N0} bytes)" -f $dump.Name, $dump.Length)

    # 테이블별 행 수 — 새 PC 복원 때 대조(scripts\restore_db.ps1). 큰따옴표 없는 SQL만 넘긴다(PowerShell 5.1 인자 전달 함정)
    $tableSql = "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r', 'p') order by 1"
    $tables = @(docker exec $Container psql -X -U autostock -d autostock -tAc $tableSql 2>$null | Where-Object { $_ })
    if ($LASTEXITCODE -ne 0 -or $tables.Count -eq 0) {
        Fail "행 수 기록 실패(테이블 목록을 읽지 못함) — 덤프 자체는 유효"
    } else {
        $lines = New-Object System.Collections.Generic.List[string]
        foreach ($t in $tables) {
            $count = (docker exec $Container psql -X -U autostock -d autostock -tAc "select count(*) from public.$t" 2>$null) -join ""
            $lines.Add("$t`t$count")
        }
        Set-Content -Path (Join-Path $dbDir "rowcounts.txt") -Value $lines -Encoding UTF8 -ErrorAction Stop
        Log "[O] 테이블 행 수 기록 $($tables.Count)개 — db\rowcounts.txt"
    }
}

# ── 3) git 밖 파일 ──────────────────────────────────────────────────────────────
$local = Join-Path $out "project-local"
New-Item -ItemType Directory -Force -Path $local -ErrorAction Stop | Out-Null
$singleFiles = @(".env", "STRATEGY.md", "BACKTEST_REPORT.md") +
        @(Get-ChildItem -Path $Repo -File -Force | Where-Object { $_.Name -like "*_appkey*.txt" -or $_.Name -like "*_secretkey*.txt" } |
                ForEach-Object { $_.Name })
foreach ($name in $singleFiles) {
    $src = Join-Path $Repo $name
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination (Join-Path $local $name) -Force -ErrorAction Stop
        Log "[O] $name"
    } else {
        Log "[.] 없음(건너뜀): $name"
    }
}
if (-not (Test-Path (Join-Path $Repo ".env"))) { Fail ".env가 없다 — 키·토큰을 따로 챙겨야 한다" }
$measured = Join-Path $Repo "docs\measured"
$measuredLogs = @(Get-ChildItem -Path $measured -Filter "*.log" -File -ErrorAction SilentlyContinue)
if ($measuredLogs.Count -gt 0) {
    $dst = Join-Path $local "docs\measured"
    New-Item -ItemType Directory -Force -Path $dst -ErrorAction Stop | Out-Null
    $measuredLogs | ForEach-Object { Copy-Item -Path $_.FullName -Destination $dst -Force -ErrorAction Stop }
    Log "[O] docs\measured\*.log $($measuredLogs.Count)개(git 무시 대상)"
}
if ($SkipData) { Log "[.] -SkipData — data\ 건너뜀" } else { CopyDir (Join-Path $Repo "data") (Join-Path $local "data") "data\" }
CopyDir (Join-Path $Repo "app\logs") (Join-Path $local "app\logs") "app\logs\"
CopyDir (Join-Path $Repo ".vscode") (Join-Path $local ".vscode") ".vscode\"
if (Test-Path $KeysDir) { CopyDir $KeysDir (Join-Path $out "keys") "$KeysDir (실전 키 등)" } else { Log "[.] $KeysDir 없음(건너뜀)" }

# ── 4) 목록 ────────────────────────────────────────────────────────────────────
Log "[.] MANIFEST.csv 작성(파일별 SHA-256) — data\가 크면 1~2분 걸린다"
$manifest = Join-Path $out "MANIFEST.csv"
Get-ChildItem -Path $out -Recurse -File -Force | Where-Object { $_.FullName -ne $manifest -and $_.FullName -ne $logFile } |
        ForEach-Object {
            [pscustomobject]@{
                path   = $_.FullName.Substring($out.Length + 1)
                bytes  = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        } | Export-Csv -Path $manifest -NoTypeInformation -Encoding UTF8
$all = @(Get-ChildItem -Path $out -Recurse -File -Force)
$total = ($all | Measure-Object -Property Length -Sum).Sum
Log ("[.] 결과 폴더 {0} — 파일 {1:N0}개, {2:N1} MB" -f $out, $all.Count, ($total / 1MB))

if ($failures.Count -gt 0) {
    Log "[X] 끝 — 실패 $($failures.Count)건: $($failures -join ' / ')"
    exit 1
}
Log "[O] 끝 — 이 폴더를 외장 디스크로 새 PC에 옮긴다(비밀 값 포함). 복원 순서: aiDoc\pc-migration-2026-10-24.md"
exit 0
