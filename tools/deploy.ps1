# Push & install the freshly built Termux:Float APK onto the connected phone and (re)start it.
#
# Usage:  pwsh -File tools/deploy.ps1
#
# Prereqs:
#   * adb on PATH and the phone connected (USB or wifi-adb; verify with `adb devices`)
#   * a debug APK built with tools/build.ps1

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $repo 'app\build\outputs\apk\debug\termux-float-app_v0.17.0+debug.apk'
$pkg = 'com.termux.window'

if (-not (Test-Path $apk)) {
    throw "APK not found: $apk  (run tools/build.ps1 first)"
}

$devices = (adb devices | Select-String -Pattern '\sdevice$').Count
if ($devices -lt 1) {
    throw "No adb device connected. Run 'adb connect <ip>:<port>' or plug in USB."
}

Write-Host "==> Installing $apk" -ForegroundColor Cyan
adb install -r -d $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed with exit code $LASTEXITCODE" }

Write-Host "==> Stopping old service" -ForegroundColor Cyan
adb shell "am force-stop $pkg" | Out-Null
Start-Sleep -Milliseconds 500

Write-Host "==> Starting Termux:Float" -ForegroundColor Cyan
adb shell "am start -n $pkg/.TermuxFloatActivity" | Out-Null
Start-Sleep -Seconds 3

Write-Host "==> State" -ForegroundColor Cyan
adb shell "pidof $pkg"
adb shell "dumpsys activity services $pkg" | Select-String -Pattern 'ServiceRecord|isForeground|startRequested' | Select-Object -First 5

Write-Host "==> Recent logcat" -ForegroundColor Cyan
adb logcat -d -t 60 | Select-String -Pattern 'TermuxFloat|termux' | Select-Object -Last 25

Write-Host "==> Done" -ForegroundColor Green
