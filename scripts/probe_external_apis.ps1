# 외부 API 실측 (트랙 A2·A3) — FRED / ECOS / 공공데이터포털 특일 / 텔레그램 응답 원문을 docs\measured 에 저장한다.
# .env 는 load_env.ps1 로 읽는다. 키 값은 화면·파일 어디에도 남기지 않는다(URL 은 저장하지 않음).
# 사용: 자택망(회사망은 일부 차단)에서  powershell -File scripts\probe_external_apis.ps1
param([string]$Tag = (Get-Date -Format "yyyyMMdd"))

$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "load_env.ps1") | Out-Null
$out = Join-Path $root "docs\measured"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Save($name, $obj) {
    $path = Join-Path $out "ext_probe_${Tag}_$name.json"
    $obj | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding UTF8
    "  -> $path"
}
function Probe($name, $url) {
    try {
        $r = Invoke-RestMethod -Uri $url -TimeoutSec 20
        "[$name] OK  keys=$(($r.PSObject.Properties.Name) -join ',')"
        Save $name $r
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        "[$name] FAIL http=$code $($_.Exception.Message)"
        try { $body = $_.ErrorDetails.Message; if ($body) { Save "${name}_error" @{ http = $code; body = $body } } } catch {}
    }
}

$today = Get-Date
$d0 = $today.AddDays(-14).ToString("yyyyMMdd"); $d1 = $today.ToString("yyyyMMdd")

# ── FRED ──
if ($env:FRED_API_KEY) {
    Probe "fred_VIXCLS"   "https://api.stlouisfed.org/fred/series/observations?series_id=VIXCLS&api_key=$($env:FRED_API_KEY)&file_type=json&sort_order=desc&limit=3"
    Probe "fred_DTWEXBGS" "https://api.stlouisfed.org/fred/series/observations?series_id=DTWEXBGS&api_key=$($env:FRED_API_KEY)&file_type=json&sort_order=desc&limit=3"
} else { "[fred] FRED_API_KEY 없음 — 건너뜀" }

# ── ECOS: 앱과 동일 호출(오늘 1일, item 없음) + 보완 호출(14일 범위, item 코드) ──
if ($env:ECOS_API_KEY) {
    $k = $env:ECOS_API_KEY
    Probe "ecos_731Y001_today_noitem" "https://ecos.bok.or.kr/api/StatisticSearch/$k/json/kr/1/1/731Y001/D/$d1/$d1"
    Probe "ecos_731Y001_range_usd"    "https://ecos.bok.or.kr/api/StatisticSearch/$k/json/kr/1/20/731Y001/D/$d0/$d1/0000001"
    Probe "ecos_722Y001_today_noitem" "https://ecos.bok.or.kr/api/StatisticSearch/$k/json/kr/1/1/722Y001/D/$d1/$d1"
    Probe "ecos_722Y001_range_base"   "https://ecos.bok.or.kr/api/StatisticSearch/$k/json/kr/1/20/722Y001/D/$d0/$d1/0101000"
} else { "[ecos] ECOS_API_KEY 없음 — 건너뜀" }

# ── 공공데이터포털 특일(getRestDeInfo) — 서비스키는 URL 인코딩된 형태로 발급되는 경우가 있어 두 방식 모두 시도 ──
if ($env:DATA_GO_KR_SERVICE_KEY) {
    $sk = $env:DATA_GO_KR_SERVICE_KEY
    $base = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo"
    Probe "holiday_202610_raw"     "$base`?serviceKey=$sk&pageNo=1&numOfRows=50&solYear=2026&solMonth=10&_type=json"
    Probe "holiday_202610_encoded" "$base`?serviceKey=$([uri]::EscapeDataString($sk))&pageNo=1&numOfRows=50&solYear=2026&solMonth=10&_type=json"
} else { "[holiday] DATA_GO_KR_SERVICE_KEY 없음 — 건너뜀" }

# ── 텔레그램 ──
if ($env:TELEGRAM_BOT_TOKEN) {
    Probe "telegram_getMe"      "https://api.telegram.org/bot$($env:TELEGRAM_BOT_TOKEN)/getMe"
    Probe "telegram_getUpdates" "https://api.telegram.org/bot$($env:TELEGRAM_BOT_TOKEN)/getUpdates?limit=5"
} else { "[telegram] TELEGRAM_BOT_TOKEN 없음 — 건너뜀(BotFather 발급 후 .env 에 추가)" }

"완료. 위 [이름] 줄과 docs\measured\ext_probe_${Tag}_*.json 을 확인."
