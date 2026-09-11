# autoStock .env 로더 (공용) — bat와 대화형 PowerShell 양쪽에서 사용.
#
# 규칙:
#   KEY=VALUE            → 환경변수 KEY 설정 (# 주석/빈 줄 무시)
#   KEY_FILE=path        → path 파일 내용(Trim)을 환경변수 KEY로 설정 (도커 시크릿 관례).
#                          평문 KEY= 줄보다 나중에 처리되므로 항상 _FILE이 우선한다.
#                          경로는 리포 루트(D:\myApp\autoStock) 기준 상대경로 허용.
# 사용:
#   대화형 PS:  & D:\myApp\autoStock\scripts\load_env.ps1        (현재 세션에 env 설정)
#   bat 경유:   powershell -File ...\load_env.ps1 -OutCmd "%TEMP%\autostock_env.cmd"
#               (cmd용 "set" 스크립트를 추가로 생성 — bat가 call 후 즉시 삭제)
# 키 값은 어떤 경우에도 화면에 출력하지 않는다.
param([string]$OutCmd = "")

$root = Split-Path -Parent $PSScriptRoot   # scripts\ 의 부모 = 리포 루트
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) { Write-Host "[X] .env not found: $envFile"; exit 1 }

$vars = [ordered]@{}
Get-Content $envFile -Encoding UTF8 | ForEach-Object {
    $p = $_ -split '=', 2
    if ($p.Count -eq 2 -and $p[0].Trim() -and -not $p[0].Trim().StartsWith('#')) {
        $vars[$p[0].Trim()] = $p[1].Trim()
    }
}

# 2차 처리: *_FILE 간접 참조 해석 (평문보다 우선)
foreach ($name in @($vars.Keys)) {
    if ($name -like '*_FILE') {
        $path = $vars[$name]
        if (-not [System.IO.Path]::IsPathRooted($path)) { $path = Join-Path $root $path }
        if (Test-Path $path) {
            $base = $name.Substring(0, $name.Length - 5)   # "_FILE" 제거
            $vars[$base] = (Get-Content $path -Raw -Encoding UTF8).Trim()
        } else {
            Write-Host "[!] $name 가 가리키는 파일 없음: $path"
        }
    }
}

foreach ($kv in $vars.GetEnumerator()) {
    if ($kv.Key -like '*_FILE') { continue }   # 참조용 경로 변수 자체는 내보내지 않음
    Set-Item -Path ("Env:" + $kv.Key) -Value $kv.Value
}

if ($OutCmd) {
    $lines = foreach ($kv in $vars.GetEnumerator()) {
        if ($kv.Key -notlike '*_FILE') { 'set "' + $kv.Key + '=' + $kv.Value + '"' }
    }
    Set-Content -Path $OutCmd -Value $lines -Encoding ASCII
}
Write-Host "[O] .env loaded ($((@($vars.Keys) | Where-Object { $_ -notlike '*_FILE' }).Count) vars)"
