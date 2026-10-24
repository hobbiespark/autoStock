# autoStock PC 이전 백업 — 로컬/외장 디스크 전용. Git 커밋·push를 하지 않는다.
# migration_시각 폴더 전체(data, DB, 실측, 로그, secrets)를 안전한 매체로 옮긴다.
param(
    [string]$Repo = "",
    [string]$BackupRoot = "C:\ai\backups\autostock",
    [string]$KeysDir = "C:\ai\keys",
    [string]$Container = "autostock-db",
    [switch]$NoPush,
    [switch]$SkipDb
)

# 네이티브 명령(docker·robocopy)은 종료 코드로 판정한다 — "Stop"이면 PowerShell 5.1이 stderr 출력만으로 멈춘다(backup_db.ps1 참고)
$ErrorActionPreference = "Continue"
if (-not $Repo) { $Repo = Split-Path -Parent $PSScriptRoot }
$repoPath = [IO.Path]::GetFullPath($Repo).TrimEnd('\')
$backupPath = [IO.Path]::GetFullPath($BackupRoot).TrimEnd('\')
if ($backupPath.Equals($repoPath, [StringComparison]::OrdinalIgnoreCase) -or
    $backupPath.StartsWith($repoPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "백업 위치는 저장소 밖이어야 한다. -BackupRoot를 바꾼다."
}
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$migration = Join-Path $BackupRoot "migration_$stamp"
$secrets = Join-Path $migration "secrets"
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
function CopyDir([string]$src, [string]$dst, [string]$label) {
    if (-not (Test-Path $src)) { Log "[.] 없음(건너뜀): $label"; return }
    $mode = "/E"
    robocopy $src $dst $mode /R:1 /W:1 /XF *.lock /NP /NFL /NDL /NJH /NJS | Out-Null
    $rc = $LASTEXITCODE
    $count = @(Get-ChildItem -Path $dst -Recurse -File -Force -ErrorAction SilentlyContinue).Count
    if ($rc -ge 8) { Fail ("{0}: robocopy 실패 코드 {1}" -f $label, $rc) }
    else { Log ("[O] {0}: 파일 {1}개" -f $label, $count) }
}

Log "[.] PC 이전 백업(로컬 전용) 시작 — 저장소 $Repo, 비밀 값 폴더 $secrets"
Set-Location $Repo
# ── 1) DB ───────────────────────────────────────────────────────────────────────
$dbDir = Join-Path $migration "db"
New-Item -ItemType Directory -Force -Path $dbDir -ErrorAction Stop | Out-Null
if ($SkipDb) {
    Log "[.] -SkipDb — DB 덤프 생략(migration\db는 그대로)"
} else {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repo "scripts\backup_db.ps1") -BackupDir $BackupRoot -Container $Container
    if ($LASTEXITCODE -ne 0) {
        Fail "DB 백업 실패 — $BackupRoot\backup.log 확인(Docker Desktop·컨테이너 $Container 실행 여부)"
    } else {
        $dump = Get-ChildItem -Path $BackupRoot -Filter "autostock_*.dump" |
                Where-Object { $_.Name -match '^autostock_\d{8}_\d{4}\.dump$' } | Sort-Object Name -Descending | Select-Object -First 1
        Copy-Item -Path $dump.FullName -Destination $dbDir -Force -ErrorAction Stop
        $versionFile = $dump.FullName -replace '\.dump$', '.versions.txt'
        if (Test-Path $versionFile) { Copy-Item -Path $versionFile -Destination $dbDir -Force -ErrorAction Stop }
        Log ("[O] DB 덤프 migration\db\{0} ({1:N0} bytes)" -f $dump.Name, $dump.Length)

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
            Log "[O] 테이블 행 수 기록 $($tables.Count)개 — migration\db\rowcounts.txt"
        }
    }
}

# 자료는 저장소 밖에 복사하며 Git 인덱스와 원격 저장소를 변경하지 않는다.
CopyDir (Join-Path $Repo "app\logs") (Join-Path $migration "app-logs") "운영 로그"
CopyDir (Join-Path $Repo "data") (Join-Path $migration "data") "수집 데이터"
CopyDir (Join-Path $Repo "docs\measured") (Join-Path $migration "docs\measured") "API 실측 자료"
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
$allManifest = Join-Path $migration "MANIFEST.csv"
Get-ChildItem -LiteralPath $migration -Recurse -File -Force |
    Where-Object { $_.FullName -ne $allManifest -and $_.FullName -ne $logFile } |
    ForEach-Object {
        [pscustomobject]@{
            path = $_.FullName.Substring($migration.Length + 1)
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    } | Export-Csv -LiteralPath $allManifest -NoTypeInformation -Encoding UTF8
Log "[O] 로컬 백업 폴더: $migration (Git 업로드 없음)"
Log "[.] 비밀 값 폴더 $secrets — migration 폴더 전체를 외장 디스크로 옮긴다(클라우드 공유·메신저 금지)"

if ($failures.Count -gt 0) {
    Log "[X] 끝 — 실패 $($failures.Count)건: $($failures -join ' / ')"
    exit 1
}
Log "[O] 끝 — 새 PC: git clone 뒤 migration 폴더의 자료·비밀 값을 붙이고 DB 복원(aiDoc\restore-from-backup.md)"
exit 0
