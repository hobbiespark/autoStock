# VALUNIVO 612 (D301): started by the app after a paper/live switch request. Waits until the app has released
# port 8080, then starts autoStock again with the existing launcher (load_env.ps1 reads trading-mode.json and
# picks the profile and database). ASCII only (Windows PowerShell 5.1). Prints no secrets.
param(
    [string]$Launcher = 'C:\ai\VALUNIVO-production\scripts\dev\Manage-DesktopApp.ps1',
    [string]$Log = 'C:\ai\autoStock-runtime\logs\mode-switch.log',
    [int]$WaitSeconds = 180
)
$ErrorActionPreference = 'Continue'
function Note([string]$Text) {
    $dir = Split-Path -Parent $Log
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Add-Content -LiteralPath $Log -Value ((Get-Date).ToString('o') + ' ' + $Text) -Encoding ASCII
}
function PortOpen {
    try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 8080); $c.Close(); return $true } catch { return $false }
}
Note 'mode switch: waiting for the app to stop'
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((PortOpen) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
if (PortOpen) { Note 'mode switch: app still listening after wait; not starting a second copy'; exit 1 }
Start-Sleep -Seconds 3
Note 'mode switch: starting autoStock'
& powershell -NoProfile -ExecutionPolicy RemoteSigned -File $Launcher -App AutoStock -Action Start
Note ('mode switch: launcher exit ' + $LASTEXITCODE)
