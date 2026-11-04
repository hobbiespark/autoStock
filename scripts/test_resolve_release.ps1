# Regression cases for scripts\resolve_release.ps1 (VALUNIVO 4-2 S8, implementation 590).
# Uses a temporary runtime folder only (never C:\ai\...). No app, no DB, no network.
# Cases: missing release.json, valid release, SHA-256 mismatch, jar outside releases, bad name, missing jar, broken JSON.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$resolve = Join-Path $PSScriptRoot 'resolve_release.ps1'
$root = Join-Path ([IO.Path]::GetTempPath()) ('autostock-release-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
if ($root.StartsWith('C:\ai\', [StringComparison]::OrdinalIgnoreCase)) { throw 'test root must not be under C:\ai' }
$out = Join-Path $root 'out.cmd'
$results = New-Object System.Collections.ArrayList
function Check([string]$Case, [bool]$Ok, [string]$Detail) {
    [void]$results.Add($Ok)
    Write-Output ('{0} {1} - {2}' -f $(if ($Ok) { '[O]' } else { '[X]' }), $Case, $Detail)
}
function Run {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $text = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $resolve -OutCmd $out -RuntimeRoot $root 2>&1 | ForEach-Object { [string]$_ }); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previous }
    return [pscustomobject]@{ Code = $code; Text = ($text -join ' ') }
}
function Write-Release([hashtable]$Values) { [IO.File]::WriteAllText((Join-Path $root 'release.json'), ($Values | ConvertTo-Json), [Text.UTF8Encoding]::new($false)) }

try {
    New-Item -ItemType Directory -Path (Join-Path $root 'releases\20261105-160000-test') -Force | Out-Null
    $jar = Join-Path $root 'releases\20261105-160000-test\autostock-app.jar'
    [IO.File]::WriteAllText($jar, 'FAKE-JAR')
    $hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    $good = @{ Name = '20261105-160000-test'; Jar = $jar; SourceCommit = ('a' * 40); Sha256 = $hash; PublishedAt = '2026-11-05T16:00:00+09:00'; BackupDirectory = '' }

    $r = Run
    Check '1 no release.json' ($r.Code -eq 0 -and -not (Test-Path $out)) "exit $($r.Code), no set file"

    Write-Release $good
    [IO.File]::WriteAllText($out, 'stale')
    $r = Run
    $lines = if (Test-Path $out) { @(Get-Content -LiteralPath $out) } else { @() }
    $ok = $r.Code -eq 0 -and $lines.Count -eq 3 -and $lines[0] -eq ('set "AUTOSTOCK_RELEASE_JAR=' + $jar + '"') -and $lines[1] -eq 'set "AUTOSTOCK_RELEASE_NAME=20261105-160000-test"'
    Check '2 valid release' $ok "exit $($r.Code), set lines $($lines.Count), stale file replaced"

    $bad = $good.Clone(); $bad.Sha256 = ('b' * 64); Write-Release $bad
    $r = Run
    Check '3 SHA-256 mismatch' ($r.Code -eq 1 -and -not (Test-Path $out) -and $r.Text -like '*SHA-256 differs*') "exit $($r.Code)"

    $outside = Join-Path $root 'outside.jar'; [IO.File]::WriteAllText($outside, 'FAKE-JAR')
    $bad = $good.Clone(); $bad.Jar = $outside; Write-Release $bad
    $r = Run
    Check '4 jar outside releases' ($r.Code -eq 1 -and -not (Test-Path $out) -and $r.Text -like '*not under*') "exit $($r.Code)"

    $bad = $good.Clone(); $bad.Name = 'Bad Name'; Write-Release $bad
    $r = Run
    Check '5 bad release name' ($r.Code -eq 1 -and -not (Test-Path $out)) "exit $($r.Code)"

    $bad = $good.Clone(); $bad.Jar = (Join-Path $root 'releases\20261105-160000-test\missing.jar'); Write-Release $bad
    $r = Run
    Check '6 missing jar' ($r.Code -eq 1 -and -not (Test-Path $out) -and $r.Text -like '*missing*') "exit $($r.Code)"

    [IO.File]::WriteAllText((Join-Path $root 'release.json'), '{ broken')
    $r = Run
    Check '7 broken JSON' ($r.Code -eq 1 -and -not (Test-Path $out)) "exit $($r.Code)"
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
$failed = @($results | Where-Object { -not $_ }).Count
Write-Output ('total: pass {0} / fail {1}' -f ($results.Count - $failed), $failed)
if ($failed -gt 0 -or $results.Count -eq 0) { exit 1 }
exit 0
