# Records the Play Console "Foreground Service (phone call)" declaration video
# on the Android emulator: places a call, goes Home (status-bar call timer),
# opens the shade (ongoing-call notification), taps it back into the call,
# then ends the call. Output: an .mp4 on the Desktop.
#
# Run from PowerShell (no admin needed):
#   powershell -ExecutionPolicy Bypass -File tools\record-fgs-video.ps1 -Apk C:\path\to\Dialer8-1.2.0.apk
#
# Close other big programs first; stop Gradle (.\gradlew.bat --stop) if it ran.

param(
    [string]$Apk = "$HOME\Desktop\Dialer8-1.2.0.apk",
    [string]$Number = "5551234",
    [string]$Avd = "Medium_Phone_API_37.0",
    [string]$Out = "$HOME\Desktop\dialer8-fgs-video.mp4",
    # Use when Dialer 8 is already installed on the emulator.
    [switch]$SkipInstall
)

# Continue, not Stop: Windows PowerShell turns any adb stderr line into a
# terminating error under Stop. Failures that matter throw explicitly.
$ErrorActionPreference = "Continue"
$pkg = "com.fancyshark.wpdialer"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

if (-not $SkipInstall -and -not (Test-Path $Apk)) { throw "APK not found: $Apk  (pass -Apk <path>, or -SkipInstall)" }
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $emulator)) { throw "emulator not found at $emulator" }

# --- 1. Emulator -------------------------------------------------------------
function Get-EmulatorSerial {
    $lines = & $adb devices | Select-String "^emulator-\d+\s+device"
    if ($lines) { return ($lines[0].Line -split "\s+")[0] }
    return $null
}

$serial = Get-EmulatorSerial
if (-not $serial) {
    Step "Starting emulator $Avd (a window will open)"
    Start-Process -FilePath $emulator -ArgumentList "-avd $Avd -gpu swiftshader_indirect -no-snapshot -memory 2048"
    for ($i = 0; $i -lt 120 -and -not $serial; $i++) { Start-Sleep 5; $serial = Get-EmulatorSerial }
    if (-not $serial) { throw "Emulator did not come up" }
}
function Adb { & $adb -s $serial @args }

Step "Waiting for Android to finish booting ($serial)"
for ($i = 0; $i -lt 120; $i++) {
    $booted = (Adb shell getprop sys.boot_completed 2>$null)
    if ("$booted".Trim() -eq "1") { break }
    Start-Sleep 5
}
Start-Sleep 15   # let the launcher and System UI settle

# --- UI helpers (uiautomator) -----------------------------------------------
function Get-Ui {
    # uiautomator needs an idle screen; a ticking call timer can keep it
    # busy ("could not get idle state"), so a failed dump returns "".
    Adb shell rm -f /sdcard/ui.xml 2>$null | Out-Null
    $r = (Adb shell uiautomator dump /sdcard/ui.xml 2>&1) -join " "
    if ($r -notmatch "dumped") { return "" }
    return (Adb shell cat /sdcard/ui.xml 2>$null) -join ""
}
# Screen size, for fallback taps at fixed relative positions.
$size = ((Adb shell wm size) -join " ") -replace '.*?(\d+)x(\d+).*', '$1 $2'
$W = [int]($size -split " ")[0]; $H = [int]($size -split " ")[1]
function Tap-At($fx, $fy) { Adb shell input tap ([int]($W * $fx)) ([int]($H * $fy)) }
function Find-Center($pattern) {
    $ui = Get-Ui
    $rx = '(?:text|content-desc)="(' + $pattern + ')"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($ui, $rx, "IgnoreCase")
    if (-not $m.Success) { return $null }
    $x = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    $y = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2
    return @([int]$x, [int]$y)
}
function Tap-Text($pattern) {
    $c = Find-Center $pattern
    if ($c) { Adb shell input tap $c[0] $c[1]; return $true }
    return $false
}
function Dismiss-Anr {
    # Software-GPU emulators often show "System UI isn't responding" at boot.
    if (Find-Center "[^""]*isn.t responding[^""]*") { Tap-Text "Wait" | Out-Null; Start-Sleep 2 }
}

Dismiss-Anr

# --- 2. Install and make it the default phone app ---------------------------
if (-not $SkipInstall) {
    Step "Installing $Apk"
    Adb uninstall $pkg 2>$null | Out-Null   # an older debug-signed copy would block the install
    Adb install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw "Install failed" }
}

Step "Making Dialer 8 the default phone app and granting permissions"
Adb shell cmd role add-role-holder android.app.role.DIALER $pkg 0
foreach ($p in "CALL_PHONE", "READ_PHONE_STATE", "READ_CALL_LOG", "WRITE_CALL_LOG",
               "READ_CONTACTS", "WRITE_CONTACTS", "POST_NOTIFICATIONS") {
    Adb shell pm grant $pkg "android.permission.$p" 2>$null | Out-Null
}
Adb shell input keyevent KEYCODE_HOME
Start-Sleep 3
Dismiss-Anr

# --- 3. Record the demo ------------------------------------------------------
Step "Recording"
Adb shell rm -f /sdcard/fgs.mp4 2>$null | Out-Null
$rec = Start-Process -FilePath $adb -ArgumentList "-s $serial shell screenrecord --time-limit 120 /sdcard/fgs.mp4" -PassThru -WindowStyle Hidden
Start-Sleep 3

Write-Host "  placing a call to $Number (the emulator answers by itself)"
Adb shell am start -a android.intent.action.CALL -d "tel:$Number" | Out-Null
Start-Sleep 8

Write-Host "  going Home - the call timer shows in the status bar"
Adb shell input keyevent KEYCODE_HOME
Start-Sleep 5

Write-Host "  opening the notification shade - the ongoing-call notification"
Adb shell cmd statusbar expand-notifications
Start-Sleep 5

Write-Host "  tapping the notification to return to the call"
if (-not (Tap-Text "Ongoing call")) {
    if (-not (Tap-Text "Dialer 8[^""]*")) {
        # The call notification is pinned at the top of the expanded shade.
        Tap-At 0.50 0.30
    }
}
Start-Sleep 6

Write-Host "  ending the call"
if (-not (Tap-Text "end call")) {
    # Dialer 8's end-call tile: bottom-left of the in-call screen.
    Tap-At 0.35 0.92
}
Start-Sleep 3
# Safety net if the tap missed: hang up through Telecom.
Adb shell input keyevent KEYCODE_ENDCALL 2>$null | Out-Null
Start-Sleep 4

# --- 4. Save -----------------------------------------------------------------
Step "Saving video"
Adb shell pkill -INT screenrecord 2>$null | Out-Null
Start-Sleep 4
if (-not $rec.HasExited) { $rec | Stop-Process -Force -ErrorAction SilentlyContinue }
Adb pull /sdcard/fgs.mp4 $Out
Write-Host ""
Write-Host "Done: $Out" -ForegroundColor Green
Write-Host "Upload it to YouTube as Unlisted and paste the link into the Play Console declaration."
