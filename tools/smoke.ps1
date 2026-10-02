# Human-visible smoke test of the installed Termux:Float build.
#
#   1. tap the terminal to raise the keyboard and type a command with `input text`
#   2. long press the output to enter text selection mode (blue selection bar must appear)
#   3. tap COPY in the selection bar and paste it back with the PST hotkey
#
# Screenshots are written next to this repository as smoke-*.png.
#
# Usage: pwsh -File tools/smoke.ps1

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

function Shot($name) {
    adb shell "screencap -p /sdcard/$name.png"
    adb pull "/sdcard/$name.png" (Join-Path $repo "$name.png") 2>$null | Out-Null
    Write-Host "  screenshot: $name.png" -ForegroundColor DarkGray
}

function FindButton($text) {
    adb shell "uiautomator dump /sdcard/smoke-ui.xml >/dev/null 2>&1"
    adb pull /sdcard/smoke-ui.xml (Join-Path $repo 'smoke-ui.xml') 2>$null | Out-Null
    [xml]$xml = Get-Content (Join-Path $repo 'smoke-ui.xml')
    $script:hit = $null
    function Walk($n) {
        if ($n.class -eq 'android.widget.Button' -and $n.text -eq $text) { $script:hit = $n.bounds }
        foreach ($c in $n.node) { Walk $c }
    }
    Walk $xml.hierarchy
    return $script:hit
}

function TapBounds($bounds, $offset = 22) {
    if ($bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $false }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2) + $offset
    adb shell "input tap $x $y" | Out-Null
    return $true
}

Write-Host '== 1. raise keyboard and type a command ==' -ForegroundColor Cyan
adb shell "input tap 400 1000"
Start-Sleep -Seconds 3
adb shell "input text 'uname'"
Start-Sleep -Milliseconds 500
adb shell "input keyevent 66"       # Enter
Start-Sleep -Seconds 3
Shot 'smoke-1-typed'

Write-Host '== 2. long press to select text ==' -ForegroundColor Cyan
adb shell "input swipe 300 1200 300 1200 800"
Start-Sleep -Seconds 2
$selBounds = $null
adb shell "uiautomator dump /sdcard/smoke-ui.xml >/dev/null 2>&1"
adb pull /sdcard/smoke-ui.xml (Join-Path $repo 'smoke-ui.xml') 2>$null | Out-Null
[xml]$xml = Get-Content (Join-Path $repo 'smoke-ui.xml')
function WalkSel($n) {
    if ($n.text -eq 'COPY' -or $n.text -eq 'COPY ALL') { $script:selBounds = $n.bounds }
    foreach ($c in $n.node) { WalkSel $c }
}
$script:selBounds = $null
WalkSel $xml.hierarchy
if ($selBounds) {
    Write-Host "  selection bar visible, COPY at $selBounds" -ForegroundColor Green
} else {
    Write-Host '  selection bar NOT found in the hierarchy' -ForegroundColor Yellow
}
Shot 'smoke-2-selection'

Write-Host '== 3. COPY then PST ==' -ForegroundColor Cyan
if ($selBounds) {
    TapBounds $selBounds | Out-Null
    Start-Sleep -Seconds 1
}
$pst = FindButton 'PST'
if ($pst) { TapBounds $pst | Out-Null; Start-Sleep -Seconds 2 }
Shot 'smoke-3-pasted'

Write-Host '== 4. service still alive ==' -ForegroundColor Cyan
$pid_ = adb shell "pidof com.termux.window"
Write-Host "  pid=$pid_"
if (-not $pid_) { exit 1 }
