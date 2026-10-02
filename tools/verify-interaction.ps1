# Interactive verification of the floating Termux window on the connected phone.
#
#   1. report window geometry with the soft keyboard hidden
#   2. tap the terminal to raise the keyboard, report geometry again (should shift up)
#   3. type a command with `input text` and press Enter
#   4. tap the CPY hotkey and dump the clipboard
#   5. write the result into files/float-debug.log on the device for inspection
#
# Usage: pwsh -File tools/verify-interaction.ps1

$ErrorActionPreference = 'Continue'

function Report-Window($label) {
    Write-Host "--- $label ---" -ForegroundColor Cyan
    adb shell "dumpsys window windows | grep -A 4 'Window{.*com.termux.window}' | grep -E 'mAttrs|Requested|frame='" 
}

Report-Window 'before keyboard'

# Tap the middle of the terminal area to focus it and raise the soft keyboard.
adb shell "input tap 540 900"
Start-Sleep -Seconds 3
Report-Window 'after tapping terminal'

# Type a harmless command and run it.
adb shell "input text 'echo+FLOAT_%s_OK'"
Start-Sleep -Milliseconds 500
adb shell "input keyevent 66"
Start-Sleep -Seconds 2

Write-Host '--- screenshot ---' -ForegroundColor Cyan
adb shell screencap -p /sdcard/verify1.png
adb pull /sdcard/verify1.png verify1.png | Out-Null

Write-Host '--- clipboard (before CPY) ---' -ForegroundColor Cyan
adb shell "cmd clipboard get-text 2>/dev/null || echo 'cmd clipboard unavailable'"

Write-Host '--- tap CPY hotkey (row 2, ~x=655 y=228) ---' -ForegroundColor Cyan
adb shell "input tap 655 228"
Start-Sleep -Seconds 1
adb shell "cmd clipboard get-text 2>/dev/null || echo 'cmd clipboard unavailable'"

Write-Host '--- tap PSTE hotkey (row 2, ~x=742 y=228) ---' -ForegroundColor Cyan
adb shell "input tap 742 228"
Start-Sleep -Seconds 1
adb shell screencap -p /sdcard/verify2.png
adb pull /sdcard/verify2.png verify2.png | Out-Null

Write-Host '--- app debug log ---' -ForegroundColor Cyan
adb shell "run-as com.termux.window cat files/float-debug.log" | Select-Object -Last 12
