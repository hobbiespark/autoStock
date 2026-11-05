# autoStock release mode (VALUNIVO 4-2 S8, implementation 590).
# Reads <RuntimeRoot>\release.json written by VALUNIVO scripts\dev\Invoke-AppDeployment.ps1 (profile autostock).
#   no release.json  -> writes nothing, exit 0: start_autostock.bat keeps the gradle bootRun path (development worktrees, or before the first release).
#   valid release    -> writes "set" lines (jar path, release name) to -OutCmd for start_autostock.bat, exit 0.
#   anything wrong   -> exit 1 with the reason. It never falls back to bootRun silently, because that would run unreviewed source.
# Checks: JSON fields, jar under <RuntimeRoot>\releases, jar exists, SHA-256 equals the recorded value, source commit is 40 hex.
# Default RuntimeRoot: the folder "autoStock-runtime" next to this checkout (C:\ai\autoStock -> C:\ai\autoStock-runtime).
# AUTOSTOCK_RUNTIME_ROOT overrides it (tests). Prints no secret values (release.json holds none).
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutCmd, [string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
try {
    if (-not $RuntimeRoot) { $RuntimeRoot = $env:AUTOSTOCK_RUNTIME_ROOT }
    if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'autoStock-runtime' }
    $RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
    if (Test-Path -LiteralPath $OutCmd) { Remove-Item -LiteralPath $OutCmd -Force }
    $releaseFile = Join-Path $RuntimeRoot 'release.json'
    if (-not (Test-Path -LiteralPath $releaseFile -PathType Leaf)) {
        Write-Output "[.] no release.json in $RuntimeRoot - gradle bootRun (source checkout)"
        exit 0
    }
    $release = [IO.File]::ReadAllText($releaseFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $name = [string]$release.Name
    $jar = [string]$release.Jar
    $sha = [string]$release.Sha256
    $commit = [string]$release.SourceCommit
    if ($name -notmatch '^[0-9]{8}-[0-9]{6}-[a-z0-9-]{1,40}$') { throw "release name has an unexpected form: $name" }
    if ($commit -notmatch '^[a-f0-9]{40}$') { throw 'release SourceCommit is not 40 hex characters' }
    if ($sha -notmatch '^[a-f0-9]{64}$') { throw 'release Sha256 is not 64 hex characters' }
    if (-not $jar) { throw 'release Jar is empty' }
    $jar = [IO.Path]::GetFullPath($jar)
    $releasesRoot = [IO.Path]::GetFullPath((Join-Path $RuntimeRoot 'releases')) + [IO.Path]::DirectorySeparatorChar
    if (-not $jar.StartsWith($releasesRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "release jar is not under $releasesRoot" }
    if ($jar.Contains('"') -or $jar.Contains('%')) { throw 'release jar path has a quote or percent sign' }
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "release jar is missing: $jar" }
    $actual = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $sha) { throw "release jar SHA-256 differs from release.json (expected $($sha.Substring(0,12))..., actual $($actual.Substring(0,12))...)" }
    $lines = @(
        "set ""AUTOSTOCK_RELEASE_JAR=$jar""",
        "set ""AUTOSTOCK_RELEASE_NAME=$name""",
        "set ""AUTOSTOCK_RELEASE_COMMIT=$($commit.Substring(0,12))"""
    )
    [IO.File]::WriteAllLines($OutCmd, $lines, [Text.Encoding]::ASCII)
    Write-Output "[O] release $name (commit $($commit.Substring(0,12)), jar SHA-256 checked)"
    exit 0
} catch {
    Write-Output "[X] release check failed: $($_.Exception.Message)"
    exit 1
}
