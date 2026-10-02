# Tap hotkeys inside the floating window, using the *current* on-screen button bounds from a
# uiautomator dump. The hotkey bar moves when the soft keyboard opens (the window is squeezed and
# its bottom edge is parked just above the IME), so fixed coordinates do not work.
#
# Usage: pwsh -File tools/tap-hotkey.ps1 -Key KBD

param([string]$Key = 'KBD')

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$dump = Join-Path $repo 'ui-hotkeys.xml'

adb shell "uiautomator dump /sdcard/ui-hotkeys.xml >/dev/null 2>&1"
adb pull /sdcard/ui-hotkeys.xml $dump 2>$null | Out-Null

[xml]$xml = Get-Content $dump
$found = $null
function Walk($n) {
    if ($n.class -eq 'android.widget.Button' -and $n.text -eq $Key) { $script:found = $n.bounds }
    foreach ($c in $n.node) { Walk $c }
}
Walk $xml.hierarchy

if (-not $found) {
    Write-Host "Hotkey '$Key' not found on screen. Buttons present:" -ForegroundColor Yellow
    function List($n) {
        if ($n.class -eq 'android.widget.Button') { Write-Host "  '$($n.text)' $($n.bounds)" }
        foreach ($c in $n.node) { List $c }
    }
    List $xml.hierarchy
    exit 1
}

if ($found -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw "Unparsable bounds: $found" }
$x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
$x = [int](($x1 + $x2) / 2)
$y = [int](($y1 + $y2) / 2)

# Empirical correction: injected touch coordinates land a constant offset below the coordinates
# reported by uiautomator for this window, measured on the whole button height scale.
$offset = 22
$tapY = $y + $offset

Write-Host "Tapping '$Key' at ($x,$tapY) [bounds $found, offset $offset]" -ForegroundColor Cyan
adb shell "input tap $x $tapY"
Start-Sleep -Seconds 1
Write-Host '--- last debug log lines ---' -ForegroundColor Cyan
adb shell "run-as com.termux.window cat files/float-debug.log" 2>$null | Select-Object -Last 4
