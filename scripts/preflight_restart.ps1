# autoStock 재기동 전 점검 (aiDoc/restart-preflight.md)
#
# 이유(2026-10-26 사고): start_autostock.bat은 gradlew :app:bootRun으로 작업 폴더 소스를 그대로 컴파일·기동하고,
#       Flyway(application.yml spring.flyway.enabled=true)가 db/migration의 새 파일을 운영 DB에 바로 적용한다.
#       운용 자금 변경 재기동 때 V21~V29가 한꺼번에 적용됐고, 그중 V29는 다른 AI 작업의 미커밋 파일이었다.
# 동작(읽기 전용 — 파일·DB·앱을 바꾸지 않는다. DB는 SELECT 1건만):
#   1) git status: bootRun 입력(app/src/main, common/src/main, gradle 빌드 파일)의 미커밋·미추적 변경 목록
#   2) 운영 DB flyway_schema_history ↔ 작업 폴더 db/migration 비교
#      - 재기동 시 새로 적용될 마이그레이션(미커밋 여부 표시)
#      - 적용됐는데 작업 폴더에서 사라진 파일, 체크섬 불일치(Flyway CRC32 규칙 직접 계산), 실패 기록
#      - 최신 적용 버전보다 낮은데 미적용인 파일(outOfOrder=false라 기동 시 검증 실패)
# 종료 코드: 0 = 이상 없음, 1 = 확인 필요(목록 참고), 2 = 점검 자체 실패(git·docker·DB 접근 불가)
#       새 마이그레이션은 커밋돼 있어도 운영 DB 스키마를 바꾸므로 기본으로 1이다. 목록을 보고 적용할 생각이면
#       -AcceptPending으로 다시 실행해 0을 확인한다(미커밋 파일은 -AcceptPending이어도 1).
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File C:\ai\autoStock\scripts\preflight_restart.ps1
#         (옵션) -AcceptPending -Container autostock-db
param(
    [switch]$AcceptPending,
    [string]$Container = "autostock-db",
    [string]$DbUser = "autostock",
    [string]$DbName = "autostock"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$migrationRel = "app/src/main/resources/db/migration"
$migrationDir = Join-Path $repo $migrationRel
$buildInputs = @("app/src/main", "common/src/main", "build.gradle", "settings.gradle", "app/build.gradle", "common/build.gradle", "gradle")
$issues = New-Object System.Collections.Generic.List[string]

function Write-Section([string]$title) { Write-Host ""; Write-Host "== $title ==" }

# Flyway 체크섬(org.flywaydb.core.internal.resolver.ChecksumCalculator, 11.x·12.x 동일):
# 파일을 줄 단위로 읽어(\r\n·\r·\n 구분, 줄바꿈 문자 제외) 첫 줄의 BOM을 빼고 각 줄의 UTF-8 바이트를 CRC32에 이어 넣는다.
# 결과는 Java int(부호 있는 32비트)로 저장된다.
$crcTable = New-Object 'uint32[]' 256
for ($n = 0; $n -lt 256; $n++) {
    [uint32]$c = $n
    for ($k = 0; $k -lt 8; $k++) {
        if ($c -band 1) { $c = [uint32]([uint32]3988292384 -bxor ($c -shr 1)) } else { $c = [uint32]($c -shr 1) }
    }
    $crcTable[$n] = $c
}
function Get-FlywayChecksum([string]$path) {
    $text = [System.IO.File]::ReadAllText($path, (New-Object System.Text.UTF8Encoding($false)))
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    $lines = [regex]::Split($text, "\r\n|\r|\n")
    # 파일이 줄바꿈으로 끝나면 Java readLine은 마지막 빈 줄을 돌려주지 않는다
    $count = $lines.Length
    if ($count -gt 0 -and $lines[$count - 1] -eq "") { $count-- }
    [uint32]$crc = [uint32]::MaxValue
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    for ($i = 0; $i -lt $count; $i++) {
        foreach ($b in $utf8.GetBytes($lines[$i])) {
            $crc = [uint32]($crcTable[($crc -bxor $b) -band 0xFF] -bxor ($crc -shr 8))
        }
    }
    $crc = [uint32]($crc -bxor [uint32]::MaxValue)
    return [BitConverter]::ToInt32([BitConverter]::GetBytes($crc), 0)
}

# 버전 비교 키: "1.2" → 각 자리 10자리 0 채움(숫자 비교)
function Get-VersionKey([string]$v) { (($v -split '\.') | ForEach-Object { $_.PadLeft(10, '0') }) -join '.' }

Write-Host "autoStock 재기동 전 점검 (읽기 전용) — $repo"

# ---- 1) bootRun 입력의 미커밋 변경 ----
Write-Section "1. 작업 폴더 미커밋·미추적 변경 (bootRun이 그대로 컴파일한다)"
$gitArgs = @("-C", $repo, "-c", "core.quotepath=false", "status", "--porcelain", "--untracked-files=all", "--") + $buildInputs
$statusLines = & git @gitArgs
if ($LASTEXITCODE -ne 0) { Write-Host "[X] git status 실패 — 점검 중단"; exit 2 }
$dirtyPaths = @{}
$statusLines = @($statusLines | Where-Object { $_ })
if ($statusLines.Count -eq 0) {
    Write-Host "[O] 없음"
} else {
    foreach ($line in $statusLines) {
        $code = $line.Substring(0, 2)
        $path = $line.Substring(3)
        if ($path -match ' -> ') { $path = ($path -split ' -> ')[1] }
        $path = $path.Trim('"')
        $dirtyPaths[$path] = $code
        $mark = if ($path.StartsWith($migrationRel)) { "  <- 마이그레이션: 운영 DB에 적용될 수 있음" } else { "" }
        Write-Host ("[!] {0} {1}{2}" -f $code, $path, $mark)
    }
    $issues.Add("bootRun 입력에 미커밋·미추적 변경 $($statusLines.Count)건 — 누구의 작업인지 확인하고 커밋/정리 후 재기동")
}

# ---- 2) 운영 DB 이력 ↔ 작업 폴더 마이그레이션 ----
Write-Section "2. 운영 DB 마이그레이션 이력 비교 ($Container, SELECT만)"
$sql = "SELECT installed_rank, coalesce(version,''), type, script, coalesce(checksum::text,''), success FROM flyway_schema_history ORDER BY installed_rank"
# PS 5.1은 네이티브 stderr를 ErrorRecord로 감싸 Stop이면 바로 예외가 된다 — 이 호출만 Continue로 받아 종료 코드 2로 처리
$ErrorActionPreference = "Continue"
$rows = & docker exec $Container psql -U $DbUser -d $DbName -At -F "|" -c $sql 2>&1
$dbExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($dbExit -ne 0) {
    Write-Host "[X] DB 조회 실패 — Docker·$Container 상태 확인 (점검 중단)"
    $rows | Select-Object -First 3 | ForEach-Object { Write-Host "    $_" }
    exit 2
}
$applied = @{}
$latestKey = ""
$latestVersion = ""
foreach ($r in @($rows | Where-Object { $_ })) {
    $f = "$r" -split '\|'
    if ($f.Count -lt 6) { continue }
    $h = [pscustomobject]@{ Rank = $f[0]; Version = $f[1]; Type = $f[2]; Script = $f[3]; Checksum = $f[4]; Success = ($f[5] -eq 't') }
    if (-not $h.Success) {
        Write-Host ("[X] 실패 기록: rank {0} {1} — Flyway가 기동을 거부한다(repair 필요 여부 확인)" -f $h.Rank, $h.Script)
        $issues.Add("flyway_schema_history 실패 기록: $($h.Script)")
    }
    if ($h.Version -eq "") { continue }   # 반복(R__) 마이그레이션은 버전 비교 대상 아님
    $applied[$h.Version] = $h
    $key = Get-VersionKey $h.Version
    if ($h.Success -and $key -gt $latestKey) { $latestKey = $key; $latestVersion = $h.Version }
}
Write-Host "운영 DB 최신 적용 버전: V$latestVersion (버전 기록 $($applied.Count)건)"

$files = @{}
foreach ($file in Get-ChildItem -Path $migrationDir -Filter "V*__*.sql" -File) {
    if ($file.Name -notmatch '^V([0-9][0-9_.]*)__') { continue }
    $ver = ($Matches[1] -replace '_', '.').Trim('.')
    if ($files.ContainsKey($ver)) {
        Write-Host "[X] 같은 버전 파일 2개: $($files[$ver].Name), $($file.Name)"
        $issues.Add("중복 버전 V$ver")
        continue
    }
    $files[$ver] = $file
}
$maxFileKey = ($files.Keys | ForEach-Object { Get-VersionKey $_ } | Sort-Object | Select-Object -Last 1)
$maxFileVersion = ($files.Keys | Where-Object { (Get-VersionKey $_) -eq $maxFileKey })
Write-Host "작업 폴더 최대 버전: V$maxFileVersion (파일 $($files.Count)개)"

# 2-a) 재기동 시 새로 적용될 것 / 순서 어긋남
$pending = @($files.Keys | Where-Object { -not $applied.ContainsKey($_) } | Sort-Object { Get-VersionKey $_ })
Write-Host ""
if ($pending.Count -eq 0) {
    Write-Host "[O] 재기동 시 새로 적용될 마이그레이션 없음"
} else {
    Write-Host "[!] 재기동 시 운영 DB에 새로 적용될 마이그레이션 $($pending.Count)건:"
    foreach ($v in $pending) {
        $name = $files[$v].Name
        $rel = "$migrationRel/$name"
        $state = if ($dirtyPaths.ContainsKey($rel)) { "미커밋($($dirtyPaths[$rel].Trim()))" } else { "커밋됨" }
        $order = if ((Get-VersionKey $v) -lt $latestKey) { " — 최신 적용 버전보다 낮음: outOfOrder=false라 기동 검증 실패" } else { "" }
        Write-Host ("    V{0}  {1}  [{2}]{3}" -f $v, $name, $state, $order)
        if ($state -ne "커밋됨") { $issues.Add("미커밋 마이그레이션이 적용 대기: $name") }
        if ($order) { $issues.Add("순서 어긋난 미적용 마이그레이션: $name") }
    }
    if (-not $AcceptPending) { $issues.Add("새 마이그레이션 $($pending.Count)건 적용 대기 — 내용 확인 후 적용할 생각이면 -AcceptPending으로 재실행") }
}

# 2-b) 이미 적용된 버전의 파일 존재·이름·체크섬
Write-Host ""
$okCount = 0
foreach ($v in ($applied.Keys | Sort-Object { Get-VersionKey $_ })) {
    $h = $applied[$v]
    if ($h.Type -notmatch 'SQL') { $okCount++; continue }   # BASELINE·JDBC 등은 파일 비교 대상 아님
    if (-not $files.ContainsKey($v)) {
        Write-Host "[X] V$v 적용됨($($h.Script)) — 작업 폴더에 파일 없음 (Flyway 검증 실패 원인)"
        $issues.Add("적용된 V$v 파일이 작업 폴더에 없음")
        continue
    }
    $file = $files[$v]
    $problems = @()
    if ($file.Name -ne $h.Script) { $problems += "이름 다름(DB $($h.Script))" }
    $sum = Get-FlywayChecksum $file.FullName
    if ($h.Checksum -ne "" -and "$sum" -ne $h.Checksum) { $problems += "체크섬 불일치(DB $($h.Checksum), 파일 $sum)" }
    if ($problems.Count -gt 0) {
        Write-Host ("[X] V{0} {1} — {2}" -f $v, $file.Name, ($problems -join ", "))
        $issues.Add("적용된 V$v 파일 변경: $($problems -join ', ')")
    } else { $okCount++ }
}
Write-Host "[O] 적용된 버전 중 파일·이름·체크섬 일치: $okCount / $($applied.Count)"

# ---- 결과 ----
Write-Section "결과"
if ($issues.Count -eq 0) {
    Write-Host "[O] 이상 없음 — 재기동해도 위 범위의 예상 밖 변경은 없다"
    exit 0
}
Write-Host "[!] 확인 필요 $($issues.Count)건:"
$issues | ForEach-Object { Write-Host "    - $_" }
exit 1
