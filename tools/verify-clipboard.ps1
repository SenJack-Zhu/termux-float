# Verify the clipboard hotkeys (CPY/PSTE) and the sticky CTRL key inside the floating window.
#
# The phone's `cmd clipboard` is not implemented, so CPY/PSTE are verified end to end:
#   * CPY copies the terminal screen to the system clipboard
#   * PSTE pastes the clipboard back into the terminal, which is visible on screen
#   * sticky CTRL + the `c` key must produce SIGINT (^C) at the shell prompt
#
# Usage: pwsh -File tools/verify-clipboard.ps1

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

function Shot($name) {
    adb shell "screencap -p /sdcard/$name.png"
    adb pull "/sdcard/$name.png" (Join-Path $repo "$name.png") | Out-Null
}

# Clear the current shell line with a real Ctrl-C from the keyboard, then show the prompt.
adb shell "input keyevent 113"   # KEYCODE_CTRL_LEFT (down)
adb shell "input keyevent 31"    # KEYCODE_C
adb shell "input keyevent 114"   # KEYCODE_CTRL_LEFT up
Start-Sleep -Seconds 1

Write-Host '==> tap CPY (row 2) to copy the screen' -ForegroundColor Cyan
adb shell "input tap 655 228"
Start-Sleep -Seconds 1

Write-Host '==> tap PSTE (row 2) to paste it back' -ForegroundColor Cyan
adb shell "input tap 742 228"
Start-Sleep -Seconds 2
Shot 'verify-paste'

Write-Host '==> sticky CTRL: tap CTL (row 1), then send "c"' -ForegroundColor Cyan
adb shell "input tap 125 204"    # CTL button, row 1
Start-Sleep -Milliseconds 500
adb shell "input keyevent 31"    # c
Start-Sleep -Seconds 2
Shot 'verify-ctrl'

Write-Host '==> dump debug log' -ForegroundColor Cyan
adb shell "run-as com.termux.window cat files/float-debug.log" | Select-Object -Last 8
