# autoStock DB 백업 (Phase 0.8 — aiDoc/backup.md, RUNBOOK 8절)
#
# 동작: 운영 컨테이너(autostock-db) 안에서 pg_dump(-Fc, 압축 사용자 지정 형식) → 호스트로 docker cp → 목차(pg_restore --list)로
#       파일이 읽히는지 확인 → 버전 기록(같은 이름의 .versions.txt: 이미지·PostgreSQL·확장 버전) → 보존 규칙 적용
#       (최근 N개 + 월별 마지막 M개). 실패하면 종료 코드 1(작업 스케줄러에 실패로 표시).
# 버전 기록 이유(2026-10-02 TimescaleDB 전환, aiDoc/db-switch-timescale.md): TimescaleDB 덤프는 덤프 때와 같은 확장 버전으로
#       복원해야 한다(공식 문서). 복원 리허설(restore_check.ps1)은 운영 컨테이너와 같은 이미지를 쓴다.
# 바이너리를 PowerShell 파이프로 받지 않는다 — Windows PowerShell 5.1은 파이프 출력을 텍스트로 다뤄 덤프가 깨진다.
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\backup_db.ps1
#         (옵션) -BackupDir E:\backup\autostock -KeepDaily 14 -KeepMonthly 12 -OffsiteDir "C:\Users\me\OneDrive\autostock-backup"
# 권장:   작업 스케줄러 — 평일 16:30(15:45 분봉 적재·15:50 리포트 뒤), "사용자의 로그온 여부에 관계없이 실행" 불필요
#         (Docker Desktop이 로그인 세션에서 돌기 때문 — 로그인 상태에서 실행되게 둔다).
param(
    [string]$BackupDir = "C:\ai\backups\autostock",
    [int]$KeepDaily = 14,
    [int]$KeepMonthly = 12,
    [string]$OffsiteDir = "",
    [string]$Container = "",
    [string]$RuntimeRoot = ""
)
# 대상 DB(VALUNIVO 1-1 autoStock D6 단계 C, 구현 514): Get-AutoStockDatabase가 정한다. 상태 파일(<RuntimeRoot>\database.json)이 없으면
#   지금과 같은 autostock-db·autostock, D6 전환 뒤에는 생성 env의 컨테이너·DB·관리자 계정. -Container는 옛 판 예약 인수와의 호환용 덮어쓰기다.
$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
. (Join-Path $scriptRoot 'Get-AutoStockDatabase.ps1')
if (-not $RuntimeRoot) { $RuntimeRoot = Get-AutoStockRuntimeRoot (Split-Path -Parent $scriptRoot) }

# 오류 처리: 네이티브 명령(docker)은 종료 코드($LASTEXITCODE)로 판정한다. $ErrorActionPreference를 "Stop"으로 두지 않는다 —
# Windows PowerShell 5.1은 네이티브 명령의 stderr를 리다이렉트(2>$null)하면 오류 레코드로 바꿔 "Stop"에서 스크립트를 멈춘다
# (예: 없는 컨테이너 docker rm -f). cmdlet은 -ErrorAction Stop을 개별로 준다.
$ErrorActionPreference = "Continue"
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
New-Item -ItemType Directory -Force -Path $BackupDir -ErrorAction Stop | Out-Null
$logFile = Join-Path $BackupDir "backup.log"

function Log([string]$message) {
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8 -ErrorAction Stop
}

$inContainer = "/tmp/autostock_$stamp.dump"
$target = Join-Path $BackupDir "autostock_$stamp.dump"
try {
    $database = Get-AutoStockDatabase $RuntimeRoot
    if (-not $Container) { $Container = $database.Container }
    $dbUser = $database.AdminUser; $dbName = $database.Database
    # TimescaleDB DB의 덤프는 "circular foreign-key constraints … continuous_agg" 경고를 늘 낸다 — 공식 문서상 정상이라 걸러 낸다
    $dumpOutput = @(docker exec $Container pg_dump -U $dbUser -d $dbName -Fc -f $inContainer 2>&1 | ForEach-Object { "$_" })
    $dumpExit = $LASTEXITCODE
    $dumpOutput | Where-Object { $_ -and $_ -notmatch "circular foreign-key|continuous_agg|--disable-triggers|data-only dump" } |
            ForEach-Object { Write-Host "    $_" }
    if ($dumpExit -ne 0) { throw "pg_dump 실패(exit $dumpExit) — 컨테이너 $Container 실행 여부 확인" }

    # 목차를 읽어 덤프가 온전한지 확인(항목 0이면 실패)
    $entries = @(docker exec $Container pg_restore --list $inContainer | Where-Object { $_ -and -not $_.StartsWith(";") })
    if ($LASTEXITCODE -ne 0 -or $entries.Count -eq 0) { throw "덤프 목차 확인 실패(항목 $($entries.Count)개)" }

    docker cp "${Container}:$inContainer" $target
    if ($LASTEXITCODE -ne 0) { throw "docker cp 실패(exit $LASTEXITCODE)" }
    $size = (Get-Item $target -ErrorAction Stop).Length
    if ($size -le 0) { throw "백업 파일 크기 0: $target" }
    # db·container·mode를 남긴다 — VALUNIVO Test-BackupTargets.ps1이 앱 DB와 대조한다(RM-45)
    Log ("[O] 백업 완료 {0} ({1:N0} bytes, 목차 {2}개) db={3}, container={4}, mode={5}" -f $target, $size, $entries.Count, $dbName, $Container, $database.Mode)

    # 버전 기록 — 실패해도 백업 자체는 유효하므로 경고만 남긴다
    $versionFile = $target -replace '\.dump$', '.versions.txt'
    $image = (docker inspect -f "{{.Config.Image}}" $Container) -join ""
    $imageId = (docker inspect -f "{{.Image}}" $Container) -join ""
    $versions = @(docker exec $Container psql -X -U $dbUser -d $dbName -tA `
            -c "select 'server ' || current_setting('server_version')" `
            -c "select 'extension ' || extname || ' ' || extversion from pg_extension order by extname")
    if ($LASTEXITCODE -eq 0) {
        $content = @("# autoStock 백업 버전 기록 — 복원은 같은 PostgreSQL 메이저·TimescaleDB 버전의 이미지로 한다",
                     "dump $(Split-Path -Leaf $target)", "database $dbName", "container $Container", "image $image", "image_id $imageId") + $versions
        Set-Content -Path $versionFile -Value $content -Encoding UTF8 -ErrorAction Stop
        Log ("[O] 버전 기록 {0} ({1})" -f (Split-Path -Leaf $versionFile), (($versions | Where-Object { $_ -like "extension timescaledb *" }) -join ", "))
    } else {
        Log "[!] 버전 기록 실패(psql exit $LASTEXITCODE) — 백업 파일은 유효"
    }

    # VALUNIVO 1-8 A-4(554, 플랫폼 401·글쓰기 529와 같음): 역할 정의(암호 제외)와 덤프·역할의 SHA-256. 실패하면 백업을 실패로 본다.
    $globals = $target -replace '\.dump$', '.globals.sql'; $hashFile = $target -replace '\.dump$', '.sha256'
    $globalsInContainer = "/tmp/autostock_$stamp.globals.sql"
    docker exec $Container pg_dumpall -U $dbUser -l $dbName --globals-only --no-role-passwords -f $globalsInContainer
    if ($LASTEXITCODE -ne 0) { throw "역할 덤프 실패(exit $LASTEXITCODE)" }
    docker cp "${Container}:$globalsInContainer" $globals
    $cpExit = $LASTEXITCODE
    docker exec -u root $Container rm -f $globalsInContainer | Out-Null
    if ($cpExit -ne 0) { throw "역할 덤프 docker cp 실패(exit $cpExit)" }
    $globalLines = [IO.File]::ReadAllLines($globals)
    $passwordLines = @($globalLines | Where-Object { $_ -match "PASSWORD\s+'|SCRAM-SHA-256\$|md5[0-9a-f]{32}" }).Count
    if ($passwordLines -ne 0) { throw "역할 덤프에 암호로 보이는 줄 $passwordLines" }
    $roleLines = @($globalLines | Where-Object { $_ -match '^CREATE ROLE ' }).Count
    if ($roleLines -eq 0) { throw '역할 덤프에 CREATE ROLE 줄이 없다' }
    $hashes = foreach ($path in @($target, $globals)) { [pscustomobject]@{ Name = (Split-Path -Leaf $path); Hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() } }
    # sha256sum 형식(BOM 없음·LF): Git Bash `sha256sum -c`로 그대로 확인한다
    [IO.File]::WriteAllText($hashFile, ((($hashes | ForEach-Object { "$($_.Hash)  $($_.Name)" }) -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    $readBack = @([IO.File]::ReadAllLines($hashFile) | Where-Object { $_ })
    if ($readBack.Count -ne 2) { throw 'SHA-256 파일 다시 읽기 실패' }
    if (Test-Path $versionFile) { Add-Content -Path $versionFile -Value ("# integrity: sha256sum -c $(Split-Path -Leaf $hashFile)  (roles in $(Split-Path -Leaf $globals), passwords excluded)") -Encoding UTF8 }
    Log ("[O] 역할·SHA-256 기록 {0} (CREATE ROLE {1}, 암호 줄 0, dump sha256={2})" -f (Split-Path -Leaf $hashFile), $roleLines, $hashes[0].Hash)

    if ($OffsiteDir) {
        New-Item -ItemType Directory -Force -Path $OffsiteDir -ErrorAction Stop | Out-Null
        Copy-Item -Path $target -Destination $OffsiteDir -Force -ErrorAction Stop
        Log "[O] 오프사이트 복사 $OffsiteDir"
    }
}
catch {
    Log "[X] 백업 실패: $($_.Exception.Message)"
    exit 1
}
finally {
    docker exec $Container rm -f $inContainer 2>$null | Out-Null
}

# 보존 규칙 — 이 스크립트가 만든 파일(autostock_yyyyMMdd_HHmm.dump)만 대상. 최근 KeepDaily개 + 월별 마지막 백업 KeepMonthly개월.
$all = @(Get-ChildItem -Path $BackupDir -Filter "autostock_*.dump" | Where-Object { $_.Name -match '^autostock_(\d{8})_(\d{4})\.dump$' } |
        Sort-Object Name -Descending)
$keep = New-Object System.Collections.Generic.HashSet[string]
$all | Select-Object -First $KeepDaily | ForEach-Object { [void]$keep.Add($_.Name) }
$all | Group-Object { $_.Name.Substring(10, 6) } | Sort-Object Name -Descending | Select-Object -First $KeepMonthly |
        ForEach-Object { [void]$keep.Add(($_.Group | Sort-Object Name -Descending | Select-Object -First 1).Name) }
$removed = 0
foreach ($file in $all) {
    if (-not $keep.Contains($file.Name)) {
        Remove-Item -Path $file.FullName -Force -ErrorAction Stop
        foreach ($suffix in @('.versions.txt', '.globals.sql', '.sha256')) {
            $side = $file.FullName -replace '\.dump$', $suffix
            if (Test-Path $side) { Remove-Item -Path $side -Force -ErrorAction Stop }
        }
        $removed++
    }
}
Log ("[O] 보존 정리: 유지 {0}개, 삭제 {1}개" -f $keep.Count, $removed)
exit 0
