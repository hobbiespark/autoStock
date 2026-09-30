# autoStock DB 백업 (Phase 0.8 — aiDoc/backup.md, RUNBOOK 8절)
#
# 동작: 운영 컨테이너(autostock-db) 안에서 pg_dump(-Fc, 압축 사용자 지정 형식) → 호스트로 docker cp → 목차(pg_restore --list)로
#       파일이 읽히는지 확인 → 보존 규칙 적용(최근 N개 + 월별 마지막 M개). 실패하면 종료 코드 1(작업 스케줄러에 실패로 표시).
# 바이너리를 PowerShell 파이프로 받지 않는다 — Windows PowerShell 5.1은 파이프 출력을 텍스트로 다뤄 덤프가 깨진다.
#
# 사용:   powershell -NoProfile -ExecutionPolicy Bypass -File D:\myApp\autoStock\scripts\backup_db.ps1
#         (옵션) -BackupDir E:\backup\autostock -KeepDaily 14 -KeepMonthly 12 -OffsiteDir "C:\Users\me\OneDrive\autostock-backup"
# 권장:   작업 스케줄러 — 평일 16:30(15:45 분봉 적재·15:50 리포트 뒤), "사용자의 로그온 여부에 관계없이 실행" 불필요
#         (Docker Desktop이 로그인 세션에서 돌기 때문 — 로그인 상태에서 실행되게 둔다).
param(
    [string]$BackupDir = "D:\backup\autostock",
    [int]$KeepDaily = 14,
    [int]$KeepMonthly = 12,
    [string]$OffsiteDir = "",
    [string]$Container = "autostock-db"
)

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
    docker exec $Container pg_dump -U autostock -d autostock -Fc -f $inContainer
    if ($LASTEXITCODE -ne 0) { throw "pg_dump 실패(exit $LASTEXITCODE) — 컨테이너 $Container 실행 여부 확인" }

    # 목차를 읽어 덤프가 온전한지 확인(항목 0이면 실패)
    $entries = @(docker exec $Container pg_restore --list $inContainer | Where-Object { $_ -and -not $_.StartsWith(";") })
    if ($LASTEXITCODE -ne 0 -or $entries.Count -eq 0) { throw "덤프 목차 확인 실패(항목 $($entries.Count)개)" }

    docker cp "${Container}:$inContainer" $target
    if ($LASTEXITCODE -ne 0) { throw "docker cp 실패(exit $LASTEXITCODE)" }
    $size = (Get-Item $target -ErrorAction Stop).Length
    if ($size -le 0) { throw "백업 파일 크기 0: $target" }
    Log ("[O] 백업 완료 {0} ({1:N0} bytes, 목차 {2}개)" -f $target, $size, $entries.Count)

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
        $removed++
    }
}
Log ("[O] 보존 정리: 유지 {0}개, 삭제 {1}개" -f $keep.Count, $removed)
exit 0
