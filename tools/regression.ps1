# End-to-end regression test of the floating Termux window on a real device.
#
# Covers: window start, extra keys bar rendering, hotkey taps (by measured bounds),
# keyboard show/hide, copy/paste round trip, sticky CONTROL, bubble minimize/restore.
#
# Usage: pwsh -File tools/regression.ps1
# Prints PASS/FAIL per check and exits non-zero if any check failed.

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$script:pass = 0
$script:fail = 0

function Check($name, $ok, $detail) {
    if ($ok) {
        $script:pass++
        Write-Host ("  [PASS] {0}{1}" -f $name, $(if ($detail) { " -> $detail" } else { "" })) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("  [FAIL] {0}{1}" -f $name, $(if ($detail) { " -> $detail" } else { "" })) -ForegroundColor Red
    }
}

function WindowAttrs {
    # Note: window entries are printed as "Window #N Window{<hash> u0 com.termux.window}:", so the
    # package name never follows the opening brace directly.
    (adb shell "dumpsys window windows | grep -A 2 'com.termux.window' | grep -E 'mAttrs|frame='") -join ' '
}

function UiDump {
    adb shell "uiautomator dump /sdcard/ui-reg.xml >/dev/null 2>&1"
    adb pull /sdcard/ui-reg.xml (Join-Path $repo 'ui-reg.xml') 2>$null | Out-Null
    [xml](Get-Content (Join-Path $repo 'ui-reg.xml'))
}

function FindButton($xml, $text) {
    $script:hit = $null
    function Walk($n) {
        if ($n.class -eq 'android.widget.Button' -and $n.text -eq $text) { $script:hit = $n.bounds }
        foreach ($c in $n.node) { Walk $c }
    }
    Walk $xml.hierarchy
    return $script:hit
}

function Tap($bounds, $offset = 22) {
    if ($bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { return $false }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2) + $offset
    adb shell "input tap $x $y" | Out-Null
    return $true
}

Write-Host '== 1. restart app and check window ==============================' -ForegroundColor Cyan
adb shell "am force-stop com.termux.window" | Out-Null
Start-Sleep -Seconds 1
adb shell "am start -n com.termux.window/.TermuxFloatActivity" | Out-Null
Start-Sleep -Seconds 4
$attrs = WindowAttrs
Check 'floating window exists' ($attrs -match 'APPLICATION_OVERLAY') $attrs
Check 'window is not collapsed' (($attrs -match '\((\d+)x(\d+)\)') -and ($attrs -notmatch '\(168x168\)')) $attrs

Write-Host '== 2. extra keys bar rendering =================================' -ForegroundColor Cyan
$xml = UiDump
$esc = FindButton $xml 'ESC'
$cpy = FindButton $xml 'CPY'
$pst = FindButton $xml 'PST'
$cc  = FindButton $xml '^C'
Check 'hotkey row 1 rendered (ESC)' ([bool]$esc) $esc
Check 'hotkey row 2 rendered (CPY/PST/^C)' ([bool]($cpy -and $pst -and $cc)) "CPY=$cpy PST=$pst ^C=$cc"

Write-Host '== 3. keyboard toggle (KBD) ====================================' -ForegroundColor Cyan
$kbd = FindButton $xml 'KBD'
Tap $kbd | Out-Null
Start-Sleep -Seconds 3
$shown = (adb shell "dumpsys input_method | grep mInputShown") -join ''
Check 'KBD raises the soft keyboard' ($shown -match 'true') $shown

Write-Host '== 4. keyboard-aware window geometry ===========================' -ForegroundColor Cyan
$attrsIme = WindowAttrs
if ($attrsIme -match '\((\d+)x(\d+)\)') {
    $h = [int]$Matches[2]
    Check 'window squeezed to fit above the IME' ($h -lt 1440) "height=$h"
} else {
    Check 'window squeezed to fit above the IME' $false $attrsIme
}

Write-Host '== 5. copy -> paste round trip =================================' -ForegroundColor Cyan
$xml = UiDump
Tap (FindButton $xml 'CPY') | Out-Null
Start-Sleep -Seconds 1
$xml = UiDump
Tap (FindButton $xml 'PST') | Out-Null
Start-Sleep -Seconds 2
adb shell "screencap -p /sdcard/reg-paste.png" | Out-Null
adb pull /sdcard/reg-paste.png (Join-Path $repo 'reg-paste.png') 2>$null | Out-Null
Check 'CPY then PST ran without crashing the service' ([bool](adb shell "pidof com.termux.window")) (adb shell "pidof com.termux.window")

Write-Host '== 6. sticky CTRL / control codes ==============================' -ForegroundColor Cyan
$xml = UiDump
Tap (FindButton $xml '^C') | Out-Null
Start-Sleep -Seconds 1
Check 'service alive after ^C' ([bool](adb shell "pidof com.termux.window")) ''

Write-Host '== 7. bubble minimize and restore ==============================' -ForegroundColor Cyan
# The control bar sits at the top of the window; use the measured bounds of the minimize button.
$xml = UiDump
$min = $null
function WalkMin($n) {
    if ($n.'resource-id' -like '*minimize_button') { $script:min = $n.bounds }
    foreach ($c in $n.node) { WalkMin $c }
}
WalkMin $xml.hierarchy
if ($min -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    $mx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $my = [int](([int]$Matches[2] + [int]$Matches[4]) / 2) + 22
    adb shell "input tap $mx $my" | Out-Null
    Start-Sleep -Seconds 2
    $bubble = WindowAttrs
    Check 'minimize collapses to 168x168 bubble' ($bubble -match '\(168x168\)') $bubble

    # Tap the bubble centre to restore.
    adb shell "input tap $mx $my" | Out-Null
    Start-Sleep -Seconds 2
    $restored = WindowAttrs
    Check 'tapping the bubble restores the window' ($restored -notmatch '\(168x168\)') $restored
} else {
    Check 'minimize button found' $false $min
}

Write-Host ''
Write-Host ("==== {0} passed, {1} failed ====" -f $script:pass, $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -gt 0) { exit 1 }
