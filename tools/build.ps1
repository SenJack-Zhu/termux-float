# Build the Termux:Float debug APK locally.
#
# Usage:  pwsh -File tools/build.ps1
#
# Environment (defaults match the machine this was developed on; override as needed):
#   JAVA_HOME                  JDK 17
#   ANDROID_HOME               Android SDK with platform 35 + build-tools 35.0.0
#   TERMUX_FLOAT_LOCAL_REPO    optional maven-layout mirror; used to satisfy the
#                              com.termux.termux-app:*:8aca6dbbf4 artifacts when jitpack.io
#                              is unreachable or fails its TLS handshake through a proxy
#   HTTPS_PROXY                optional, e.g. http://127.0.0.1:7890

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

$sdkRoot = 'D:\2_DevEnv\Libraries\AndroidBuildKit'
if (-not $env:JAVA_HOME)    { $env:JAVA_HOME    = Join-Path $sdkRoot 'jdk17' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $sdkRoot 'sdk' }
if (-not $env:TERMUX_FLOAT_LOCAL_REPO -and (Test-Path (Join-Path $sdkRoot 'm2'))) {
    $env:TERMUX_FLOAT_LOCAL_REPO = Join-Path $sdkRoot 'm2'
}

Write-Host "JAVA_HOME                = $env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "ANDROID_HOME             = $env:ANDROID_HOME" -ForegroundColor Cyan
Write-Host "TERMUX_FLOAT_LOCAL_REPO  = $env:TERMUX_FLOAT_LOCAL_REPO" -ForegroundColor Cyan

Push-Location $repo
try {
    & .\gradlew.bat assembleDebug --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$apk = Join-Path $repo 'app\build\outputs\apk\debug\termux-float-app_v0.17.0+debug.apk'
if (Test-Path $apk) {
    $item = Get-Item $apk
    Write-Host ("APK: {0} ({1:N0} bytes, {2})" -f $item.FullName, $item.Length, $item.LastWriteTime) -ForegroundColor Green
} else {
    throw "APK not found at $apk"
}
