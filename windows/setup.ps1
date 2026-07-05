# Sinx — Firewall + Task Scheduler setup
# Right-click this file → "Run with PowerShell" (it self-elevates)

if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    exit
}

Write-Host "=== Sinx Setup ===" -ForegroundColor Cyan

# ── 1. Firewall rule ──────────────────────────────────────────────────────────
$ruleName = "Sinx Receiver"
if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
    Write-Host "  [SKIP] Firewall rule '$ruleName' already exists." -ForegroundColor Yellow
} else {
    New-NetFirewallRule `
        -DisplayName $ruleName `
        -Direction   Inbound `
        -Protocol    TCP `
        -LocalPort   8765 `
        -Action      Allow `
        -Profile     Any | Out-Null
    Write-Host "  [OK]   Inbound TCP 8765 rule created." -ForegroundColor Green
}

# ── 2. Task Scheduler entry ───────────────────────────────────────────────────
$scriptDir  = Split-Path -Parent $PSCommandPath
$serverPath = Join-Path $scriptDir "server.py"
$batPath    = Join-Path $scriptDir "startup.bat"
$taskName   = "SinxReceiver"

# Find Python
$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) {
    Write-Host "  [WARN] python not found in PATH; Task Scheduler action skipped." -ForegroundColor Yellow
} elseif (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Write-Host "  [SKIP] Scheduled task '$taskName' already exists." -ForegroundColor Yellow
} else {
    $action  = New-ScheduledTaskAction  -Execute $python -Argument "`"$serverPath`"" -WorkingDirectory $scriptDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet `
        -ExecutionTimeLimit (New-TimeSpan -Hours 0) `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -StartWhenAvailable

    Register-ScheduledTask `
        -TaskName  $taskName `
        -Action    $action `
        -Trigger   $trigger `
        -Settings  $settings `
        -RunLevel  Limited `
        -Force | Out-Null

    Write-Host "  [OK]   Task '$taskName' registered — starts at every logon." -ForegroundColor Green
}

Write-Host ""
Write-Host "Done. The server will start automatically at next logon." -ForegroundColor Cyan
Write-Host "Press any key to close..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
