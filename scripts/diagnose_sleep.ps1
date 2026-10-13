# diagnose_sleep.ps1 — 장중에 PC가 왜 잠들었나(절전·최신 대기 모드) 진단
#
# 실행(관리자 PowerShell 권장 — powercfg /requests·/requestsoverride·/sleepstudy는 관리자만 된다):
#   powershell -ExecutionPolicy Bypass -File scripts\diagnose_sleep.ps1
# 결과: data\diagnostics\sleep_diag_<시각>.txt (UTF-8), 관리자면 sleepstudy_<시각>.html 도
#
# 무엇을 보나(aiDoc/sleep-resume.md 5절):
#   - 대기 방식: S3 절전인지 최신 대기 모드(S0 저전원 유휴)인지
#   - 화면 끄기·절전·최대 절전·무인 절전 시간(AC/DC) — 최신 대기 모드는 화면이 유휴로 꺼지면 대기에 들어갈 수 있다
#   - 앱 절전 요청을 무시하는 설정(시스템 요청 허용 정책, requestsoverride)
#   - 최근 7일 대기 진입·해제 이벤트(Kernel-Power 506/507 사유 전문), 절전 진입(42)·복귀 원인(Power-Troubleshooter 1)
# 2026-10-12 처음 판: 무인 절전 시간 GUID가 틀려 그 항목만 실패했다(맞는 값 7bc4a2f9-d8fc-4469-b07b-33eb785aaca0).

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $root 'data\diagnostics'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$stamp = '{0:yyyyMMdd_HHmmss}' -f (Get-Date)
$out  = Join-Path $dir "sleep_diag_$stamp.txt"

function Write-Diag([string]$text) {
    Write-Output $text
    Add-Content -LiteralPath $out -Value $text -Encoding UTF8
}

function Section([string]$title) { Write-Diag "`n===== $title =====" }

# powercfg는 cmd를 거쳐 실행한다 — 오류(stderr)도 그냥 글자로 받는다(PowerShell 빨간 오류 덩어리 방지)
function Run-Native([string]$commandLine) {
    $lines = & cmd.exe /c "$commandLine 2>&1"
    return ($lines | Out-String -Width 300).TrimEnd()
}

$isAdmin = $false
try {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
} catch {
    $isAdmin = $false
}

Write-Diag "절전 진단 — $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  관리자 권한: $(if ($isAdmin) { '예' } else { '아니오 — requests/requestsoverride/sleepstudy 항목은 비거나 실패한다' })"

Section 'PC 종류 — 배터리가 있으면 노트북(덮개 닫기·배터리 설정도 영향)'
try {
    $os = Get-CimInstance Win32_OperatingSystem
    $chassis = (Get-CimInstance Win32_SystemEnclosure).ChassisTypes -join ','
    $battery = @(Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue).Count
    Write-Diag "OS: $($os.Caption) (빌드 $($os.BuildNumber))  ChassisTypes: $chassis  배터리: $battery 개"
} catch {
    Write-Diag "조회 실패: $($_.Exception.Message)"
}

Section 'powercfg /a — "대기 모드 (S0 저 전원 유휴)"가 있으면 최신 대기 모드(Modern Standby)'
Write-Diag (Run-Native 'powercfg /a')

Section 'powercfg /getactivescheme'
Write-Diag (Run-Native 'powercfg /getactivescheme')

Section '전원 설정 값(현재 구성표) — 시간은 초, 0 = 안 함'
# 숨은 설정도 GUID로 직접 조회하면 보인다. 안 되면 /qh(숨은 설정 포함 조회)로 한 번 더 본다.
$settings = @(
    @{ Label = '화면 끄기(VIDEOIDLE)';                   Sub = 'SUB_VIDEO';   Setting = '3c0bc021-c8a8-4e07-a973-6b14cbcb2b7e'; Time = $true },
    @{ Label = '잠금 화면에서 화면 끄기(VIDEOCONLOCK)';   Sub = 'SUB_VIDEO';   Setting = '8ec4b3a5-6868-48c2-be75-4f3044be88a7'; Time = $true },
    @{ Label = '절전(STANDBYIDLE)';                     Sub = 'SUB_SLEEP';   Setting = '29f6c1db-86da-48c5-9fdb-f2b67b1f44da'; Time = $true },
    @{ Label = '최대 절전(HIBERNATEIDLE)';               Sub = 'SUB_SLEEP';   Setting = '9d7815a6-7ee4-497e-8888-515a05f02364'; Time = $true },
    @{ Label = '무인 절전 — 자동으로 깨어난 뒤 다시 잠들기까지(UNATTENDSLEEP)'; Sub = 'SUB_SLEEP'; Setting = '7bc4a2f9-d8fc-4469-b07b-33eb785aaca0'; Time = $true },
    @{ Label = '앱의 시스템 절전 방지 요청 허용(ALLOWSYSTEMREQUIRED, 1 = 허용)'; Sub = 'SUB_SLEEP'; Setting = 'a4b195f5-8225-47d8-8012-9d41369786e2'; Time = $false },
    @{ Label = '절전 해제 타이머 허용(RTCWAKE, 0 끔 1 켬 2 중요한 것만)'; Sub = 'SUB_SLEEP'; Setting = 'bd3b718a-0680-4d9d-8ab2-e1d2b4ac806d'; Time = $false },
    @{ Label = '대기 중 네트워크 연결(0 끔 1 켬 2 Windows가 관리)'; Sub = 'fea3413e-7e05-4911-9a71-700331f1c294'; Setting = 'f15576e8-98b7-4186-b944-eafa664402d9'; Time = $false },
    @{ Label = '덮개 닫기 동작(LIDACTION, 0 아무것도 1 절전 2 최대 절전 3 종료)'; Sub = 'SUB_BUTTONS'; Setting = '5ca83367-6e45-459f-a27b-476b1d01c936'; Time = $false },
    @{ Label = '전원 단추 동작(PBUTTONACTION, 0 아무것도 1 절전 2 최대 절전 3 종료)'; Sub = 'SUB_BUTTONS'; Setting = '7648efa3-dd9c-4e3e-b566-50f929386280'; Time = $false }
)
foreach ($s in $settings) {
    $text = Run-Native "powercfg /q SCHEME_CURRENT $($s.Sub) $($s.Setting)"
    if ($text -notmatch '0x[0-9a-fA-F]{8}') {
        $text = Run-Native "powercfg /qh SCHEME_CURRENT $($s.Sub) $($s.Setting)"
    }
    $values = @()
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '(AC|DC)[^:]*:\s*0x([0-9a-fA-F]{8})') {
            $n = [Convert]::ToInt64($Matches[2], 16)
            if ($s.Time -and $n -gt 0) {
                $values += "$($Matches[1]) $n ($([math]::Round($n / 60, 1))분)"
            } elseif ($s.Time) {
                $values += "$($Matches[1]) 0 (안 함)"
            } else {
                $values += "$($Matches[1]) $n"
            }
        }
    }
    if ($values.Count -gt 0) {
        Write-Diag ("{0}: {1}" -f $s.Label, ($values -join ' / '))
    } else {
        $head = (($text -split "`r?`n") | Select-Object -First 2) -join ' '
        Write-Diag ("{0}: 값 없음 — {1}" -f $s.Label, $head)
    }
}

Section 'powercfg /requests — 장중이면 java.exe가 DISPLAY·SYSTEM에 보여야 한다(관리자)'
Write-Diag (Run-Native 'powercfg /requests')

Section 'powercfg /requestsoverride — 여기에 java.exe가 있으면 앱의 절전 방지 요청이 무시된다(관리자)'
Write-Diag (Run-Native 'powercfg /requestsoverride')

Section 'powercfg /lastwake'
Write-Diag (Run-Native 'powercfg /lastwake')

Section 'powercfg /waketimers'
Write-Diag (Run-Native 'powercfg /waketimers')

$since = (Get-Date).AddDays(-7)

Section '최근 7일 전원 이벤트(오래된 것부터) — 506 대기 진입·507 대기 해제(사유 포함), 42 절전 진입, 107 절전 복귀, 566 세션 전환'
try {
    $events = Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Kernel-Power'; Id = 42, 107, 506, 507, 566; StartTime = $since } -ErrorAction Stop |
        Sort-Object TimeCreated
    foreach ($e in $events) {
        $message = ($e.Message -replace '\s*\r?\n\s*', ' ').Trim()
        Write-Diag ("{0:yyyy-MM-dd HH:mm:ss}  {1}  {2}" -f $e.TimeCreated, $e.Id, $message)
    }
    Write-Diag "($(@($events).Count)건)"
} catch {
    Write-Diag "이벤트 없음 또는 조회 실패: $($_.Exception.Message)"
}

Section '최근 7일 절전 복귀 원인(Power-Troubleshooter 1)'
try {
    $wakes = Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Power-Troubleshooter'; Id = 1; StartTime = $since } -ErrorAction Stop |
        Sort-Object TimeCreated
    foreach ($e in $wakes) {
        $message = ($e.Message -replace '\s*\r?\n\s*', ' ').Trim()
        Write-Diag ("{0:yyyy-MM-dd HH:mm:ss}  {1}" -f $e.TimeCreated, $message)
    }
} catch {
    Write-Diag "이벤트 없음 또는 조회 실패: $($_.Exception.Message)"
}

if ($isAdmin) {
    Section 'powercfg /sleepstudy — 최신 대기 모드 세션 보고서(HTML, 최근 7일)'
    $study = Join-Path $dir "sleepstudy_$stamp.html"
    Write-Diag (Run-Native "powercfg /sleepstudy /duration 7 /output `"$study`"")
} else {
    Section 'powercfg /sleepstudy — 건너뜀(관리자 PowerShell에서 다시 실행하면 HTML 보고서도 남긴다)'
}

Write-Diag "`n끝 -> $out"
