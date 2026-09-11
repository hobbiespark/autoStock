# ka10080 (minute chart) historical depth probe - mockapi only, no key values printed.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\probe_ka10080_depth.ps1
# NOTE: ASCII-only on purpose - PS 5.1 misparses BOM-less UTF-8 Korean.
param([int]$MaxPages = 60, [string]$Symbol = "005930", [string]$TicScope = "1")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

# ---- load mock keys from .env (values never printed) ----
$envMap = @{}
# -Encoding UTF8 required: PS 5.1 defaults to ANSI/CP949 for BOM-less files, and a Korean
# comment's trailing UTF-8 byte can swallow the newline, merging the next KEY line into the comment.
Get-Content (Join-Path $root ".env") -Encoding UTF8 | ForEach-Object {
    # strip invisible prefixes (BOM, zero-width) that hide behind identical-looking names
    $line = ($_ -replace '^[^\x20-\x7E#]+', '').Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $idx = $line.IndexOf("=")
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        $envMap[$k] = $v
    }
}
$appKey = $envMap["KIWOOM_MOCK_G_APP_KEY"]
$secret = $envMap["KIWOOM_MOCK_G_APP_SECRET"]
if (-not $appKey -or -not $secret) {
    Write-Host "[X] mock keys not found in .env"
    Write-Host "    scriptRoot=$PSScriptRoot"
    Write-Host "    root=$root"
    Write-Host "    envFileExists=$(Test-Path (Join-Path $root '.env'))"
    Write-Host "    parsedKeyNames: $($envMap.Keys -join ', ')"   # key NAMES only, never values
    exit 1
}

$base = "https://mockapi.kiwoom.com"

# ---- token (au10001) ----
$tokenBody = @{ grant_type = "client_credentials"; appkey = $appKey; secretkey = $secret } | ConvertTo-Json
$tokenRes = Invoke-RestMethod -Method Post -Uri "$base/oauth2/token" -ContentType "application/json;charset=UTF-8" -Body $tokenBody
$token = $tokenRes.token
if (-not $token) { Write-Host "[X] token failed: return_code=$($tokenRes.return_code) $($tokenRes.return_msg)"; exit 1 }
Write-Host "[O] token OK"

# ---- page through ka10080 via cont-yn / next-key to measure depth ----
$symbol = $Symbol
$ticScope = $TicScope    # 1-minute bars by default
$maxPages = $MaxPages    # 0.4s sleep per page for rate limit
$totalRows = 0
$earliest = $null
$latest = $null
$contYn = "N"; $nextKey = ""
$listKey = $null

for ($page = 1; $page -le $maxPages; $page++) {
    $headers = @{ "authorization" = "Bearer $token"; "api-id" = "ka10080"; "cont-yn" = $contYn; "next-key" = $nextKey }
    $body = @{ stk_cd = $symbol; tic_scope = $ticScope; upd_stkpc_tp = "1" } | ConvertTo-Json
    $res = Invoke-WebRequest -Method Post -Uri "$base/api/dostk/chart" -ContentType "application/json;charset=UTF-8" -Headers $headers -Body $body -UseBasicParsing
    $json = $res.Content | ConvertFrom-Json

    if ($json.return_code -ne 0) { Write-Host "[X] p$page return_code=$($json.return_code) $($json.return_msg)"; break }

    if ($page -eq 1) {
        Write-Host "[measured] top-level keys: $((($json | Get-Member -MemberType NoteProperty).Name) -join ', ')"
        foreach ($p in ($json | Get-Member -MemberType NoteProperty).Name) {
            if ($json.$p -is [System.Array] -and $json.$p.Count -gt 0) { $listKey = $p; break }
        }
        if (-not $listKey) {
            Write-Host "[X] no array field found - raw head: $($res.Content.Substring(0, [Math]::Min(500, $res.Content.Length)))"
            break
        }
        Write-Host "[measured] list key: $listKey / element keys: $((($json.$listKey[0] | Get-Member -MemberType NoteProperty).Name) -join ', ')"
    }

    $rows = $json.$listKey
    if (-not $rows -or $rows.Count -eq 0) { Write-Host "[.] p$page empty page - stop"; break }
    $totalRows += $rows.Count

    # find timestamp-like field (12-14 digits)
    $timeField = ($rows[0] | Get-Member -MemberType NoteProperty).Name |
        Where-Object { "$($rows[0].$_)" -match "^\d{12,14}$" } | Select-Object -First 1
    if ($timeField) {
        $pageTimes = $rows | ForEach-Object { "$($_.$timeField)" }
        $pMin = ($pageTimes | Measure-Object -Minimum).Minimum
        $pMax = ($pageTimes | Measure-Object -Maximum).Maximum
        if (-not $earliest -or $pMin -lt $earliest) { $earliest = $pMin }
        if (-not $latest -or $pMax -gt $latest) { $latest = $pMax }
    }

    $contYn = $res.Headers["cont-yn"]; $nextKey = $res.Headers["next-key"]
    if ($contYn -is [System.Array]) { $contYn = $contYn[0] }
    if ($nextKey -is [System.Array]) { $nextKey = $nextKey[0] }
    Write-Host ("p{0}: {1} rows (total {2}) cont-yn={3}" -f $page, $rows.Count, $totalRows, $contYn)
    if ($contYn -ne "Y") { Write-Host "[.] no more pages - server returned everything it has"; break }
    Start-Sleep -Milliseconds 400
}

Write-Host ""
Write-Host "===== ka10080 depth result ($symbol, $ticScope-min bars) ====="
Write-Host "total rows: $totalRows / newest: $latest / oldest: $earliest"
if ($page -gt $maxPages) { Write-Host "[!] hit page cap ($maxPages) - real depth is deeper; raise cap to re-probe" }
