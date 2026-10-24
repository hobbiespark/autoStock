# autoStock PC 이전 백업 — git 방식 (2026-10-24 — aiDoc/pc-migration-2026-10-24.md, 복구: aiDoc/restore-from-backup.md)
#
# 사용자 결정(10/24): 외장 디스크 대신 git(GitHub hobbiespark/autoStock, 공개 저장소)으로 옮기고, 비밀 값만 외장 디스크로.
# 하는 일(순서):
#   1) DB — scripts\backup_db.ps1로 새 덤프(+버전 기록) → 저장소 backup\db\로 복사(이전 덤프는 지운다 — git 기록에 남는다),
#      테이블별 행 수(backup\db\rowcounts.txt — 새 PC에서 scripts\restore_db.ps1이 대조)
#   2) 로그 스냅숏 — app\logs\ → backup\app-logs\(잠금 파일 제외)
#   3) git — backup\, data\, STRATEGY.md, BACKTEST_REPORT.md, docs\measured\를 커밋하고 push. 커밋 직전에 비밀 파일 이름
#      (.env·*_appkey*·*_secretkey*·*.pem)이 끼었는지 보고, 끼었으면 커밋하지 않고 멈춘다. 코드 변경(위 경로 밖)은 건드리지 않는다
#   4) 비밀 값 폴더 — .env, 키움 키 파일, .vscode\, (있으면) D:\keys → D:\backup\autostock\secrets_yyyyMMdd_HHmm\
#      + RESTORE_GUIDE.md(복구 안내) + MANIFEST.csv(SHA-256) + migration.log. 이 폴더만 외장 디스크로 옮긴다.
#
# 사용: scripts\pc_migration_backup.bat(더블클릭) 또는
#       powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\pc_migration_backup.ps1
#       (옵션) -NoPush(커밋만)  -SkipDb(DB 덤프 생략)  -BackupRoot E:\autostock-backup  -KeysDir D:\keys
# 앱은 켜 둬도 된다(pg_dump는 한 시점의 일관된 사본). 옛 PC를 더 쓰다가 옮기면 옮기기 직전에 한 번 더 실행한다.
param(
    [string]$Repo = "",
    [string]$BackupRoot = "D:\backup\autostock",
    [string]$KeysDir = "D:\keys",
    [string]$Container = "autostock-db",
    [switch]$NoPush,
    [switch]$SkipDb
)

# 네이티브 명령(git·docker·robocopy)은 종료 코드로 판정한다 — "Stop"이면 PowerShell 5.1이 stderr 출력만으로 멈춘다(backup_db.ps1 참고)
$ErrorActionPreference = "Continue"
if (-not $Repo) { $Repo = Split-Path -Parent $PSScriptRoot }
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
$secrets = Join-Path $BackupRoot "secrets_$stamp"
New-Item -ItemType Directory -Force -Path $secrets -ErrorAction Stop | Out-Null
$logFile = Join-Path $secrets "migration.log"
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

# robocopy 종료 코드 0~7은 성공, 8 이상은 실패한 파일이 있다. *.lock은 뺀다(앱이 떠 있으면 단일 실행 잠금이 잠겨 있다).
function CopyDir([string]$src, [string]$dst, [string]$label, [switch]$Mirror) {
    if (-not (Test-Path $src)) { Log "[.] 없음(건너뜀): $label"; return }
    $mode = if ($Mirror) { "/MIR" } else { "/E" }
    robocopy $src $dst $mode /R:1 /W:1 /XF *.lock /NP /NFL /NDL /NJH /NJS | Out-Null
    $rc = $LASTEXITCODE
    $count = @(Get-ChildItem -Path $dst -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    if ($rc -ge 8) { Fail ("{0}: robocopy 실패 코드 {1}" -f $label, $rc) }
    else { Log ("[O] {0}: 파일 {1}개" -f $label, $count) }
}

Log "[.] PC 이전 백업(git 방식) 시작 — 저장소 $Repo, 비밀 값 폴더 $secrets"
Set-Location $Repo
$git = @("-c", "core.autocrlf=true")
if (-not ((git config user.name) -join "")) { $git += @("-c", "user.name=hobbiespark") }
if (-not ((git config user.email) -join "")) { $git += @("-c", "user.email=wstlits@hunet.co.kr") }

$codeChanges = @(git @git status --porcelain 2>$null | Where-Object {
        $_ -and $_ -notmatch 'spring-petclinic' -and $_ -notmatch ' (backup|data|docs/measured)/' -and
                $_ -notmatch ' "?(STRATEGY|BACKTEST_REPORT)\.md' })
if ($codeChanges.Count -gt 0) {
    Log "[!] 커밋 안 된 코드 변경 $($codeChanges.Count)건 — 이 스크립트는 커밋하지 않는다(필요하면 따로 커밋한 뒤 다시 실행):"
    $codeChanges | ForEach-Object { Log "      $_" }
}

# ── 1) DB ───────────────────────────────────────────────────────────────────────
$dbDir = Join-Path $Repo "backup\db"
New-Item -ItemType Directory -Force -Path $dbDir -ErrorAction Stop | Out-Null
if ($SkipDb) {
    Log "[.] -SkipDb — DB 덤프 생략(backup\db는 그대로)"
} else {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repo "scripts\backup_db.ps1") -BackupDir $BackupRoot
    if ($LASTEXITCODE -ne 0) {
        Fail "DB 백업 실패 — $BackupRoot\backup.log 확인(Docker Desktop·컨테이너 $Container 실행 여부)"
    } else {
        $dump = Get-ChildItem -Path $BackupRoot -Filter "autostock_*.dump" |
                Where-Object { $_.Name -match '^autostock_\d{8}_\d{4}\.dump$' } | Sort-Object Name -Descending | Select-Object -First 1
        Get-ChildItem -Path $dbDir -File | Where-Object { $_.Name -like "autostock_*" } | Remove-Item -Force -ErrorAction Stop
        Copy-Item -Path $dump.FullName -Destination $dbDir -Force -ErrorAction Stop
        $versionFile = $dump.FullName -replace '\.dump$', '.versions.txt'
        if (Test-Path $versionFile) { Copy-Item -Path $versionFile -Destination $dbDir -Force -ErrorAction Stop }
        Log ("[O] DB 덤프 backup\db\{0} ({1:N0} bytes)" -f $dump.Name, $dump.Length)

        # 큰따옴표 없는 SQL만 넘긴다(PowerShell 5.1 인자 전달 함정)
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
            Log "[O] 테이블 행 수 기록 $($tables.Count)개 — backup\db\rowcounts.txt"
        }
    }
}

# ── 2) 로그 스냅숏 ──────────────────────────────────────────────────────────────
CopyDir (Join-Path $Repo "app\logs") (Join-Path $Repo "backup\app-logs") "로그 스냅숏 backup\app-logs\" -Mirror

# ── 3) git 커밋·push ───────────────────────────────────────────────────────────
git @git add -A -- backup data STRATEGY.md BACKTEST_REPORT.md docs/measured 2>&1 |
        Where-Object { "$_" -notmatch "LF will be replaced|original line endings" } | ForEach-Object { Log "      $_" }
$staged = @(git @git diff --cached --name-only 2>$null | Where-Object { $_ })
$leaked = @($staged | Where-Object { $_ -match '(^|/)\.env$|_appkey|_secretkey|\.pem$|(^|/)keys/' })
if ($leaked.Count -gt 0) {
    git @git reset -q 2>$null | Out-Null
    Fail ("비밀 파일이 커밋 대상에 끼었다 — 커밋하지 않고 멈춤: {0}" -f ($leaked -join ", "))
} elseif ($staged.Count -eq 0) {
    Log "[O] 커밋할 변경 없음(backup·data·문서가 이미 최신)"
} else {
    Log "[.] 커밋 대상 $($staged.Count)개 파일"
    $message = "backup: PC 이전 스냅숏 — DB 덤프·data·로그 ($(Get-Date -Format 'yyyy-MM-dd HH:mm'))`n`nscripts/pc_migration_backup.ps1 — 복구: aiDoc/restore-from-backup.md"
    $commitOutput = @(git @git commit -q -m $message 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        $commitOutput | ForEach-Object { Log "      $_" }
        Fail "git commit 실패(exit $LASTEXITCODE)"
    } else {
        Log ("[O] 커밋 {0}" -f ((git log -1 --format="%h %s") -join ""))
    }
}
if ($NoPush) {
    Log "[.] -NoPush — push 생략"
} else {
    Log "[.] git push origin main(처음엔 data 때문에 몇 분 걸린다)"
    $pushOutput = @(git push origin main 2>&1 | ForEach-Object { "$_" })
    $pushExit = $LASTEXITCODE
    $pushOutput | Where-Object { $_ -notmatch '^(Enumerating|Counting|Compressing|Writing|Delta|Total|remote: Resolving)' } |
            ForEach-Object { Log "      $_" }
    if ($pushExit -ne 0) { Fail "git push 실패(exit $pushExit) — 다시 실행하거나 git push origin main" }
    else { Log "[O] git push origin main 완료" }
}
Log ("[.] HEAD {0} — {1}" -f ((git rev-parse --short HEAD) -join ""), ((git status -sb | Select-Object -First 1) -join ""))

# ── 4) 비밀 값 폴더(외장 디스크로 옮길 것만) ───────────────────────────────────
$secretFiles = @(".env") + @(Get-ChildItem -Path $Repo -File -Force |
        Where-Object { $_.Name -like "*_appkey*.txt" -or $_.Name -like "*_secretkey*.txt" } | ForEach-Object { $_.Name })
foreach ($name in $secretFiles) {
    $src = Join-Path $Repo $name
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination (Join-Path $secrets $name) -Force -ErrorAction Stop
        Log "[O] $name"
    } else {
        Fail "없음: $name — 키·토큰을 따로 챙겨야 한다"
    }
}
CopyDir (Join-Path $Repo ".vscode") (Join-Path $secrets ".vscode") ".vscode\(git 무시 대상)"
if (Test-Path $KeysDir) { CopyDir $KeysDir (Join-Path $secrets "keys") "$KeysDir(실전 키 등)" } else { Log "[.] $KeysDir 없음(건너뜀)" }
$guide = Join-Path $Repo "aiDoc\restore-from-backup.md"
if (Test-Path $guide) {
    Copy-Item -Path $guide -Destination (Join-Path $secrets "RESTORE_GUIDE.md") -Force -ErrorAction Stop
    Log "[O] RESTORE_GUIDE.md(복구 안내 — 새 PC에서 먼저 읽는다)"
} else {
    Fail "복구 안내 없음: $guide"
}
$manifest = Join-Path $secrets "MANIFEST.csv"
Get-ChildItem -Path $secrets -Recurse -File -Force | Where-Object { $_.FullName -ne $manifest -and $_.FullName -ne $logFile } |
        ForEach-Object {
            [pscustomobject]@{
                path   = $_.FullName.Substring($secrets.Length + 1)
                bytes  = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        } | Export-Csv -Path $manifest -NoTypeInformation -Encoding UTF8
Log "[.] 비밀 값 폴더 $secrets — 이 폴더만 외장 디스크로 옮긴다(클라우드 공유·메신저 금지)"

if ($failures.Count -gt 0) {
    Log "[X] 끝 — 실패 $($failures.Count)건: $($failures -join ' / ')"
    exit 1
}
Log "[O] 끝 — 새 PC: git clone 뒤 비밀 값 폴더를 붙이고 DB 복원(aiDoc\restore-from-backup.md)"
exit 0
