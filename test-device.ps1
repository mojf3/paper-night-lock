param([string]$Serial = 'emulator-5556')
$ErrorActionPreference = 'Stop'
if ($Serial -notlike 'emulator-*') { throw 'This destructive test suite is restricted to a fresh emulator.' }
$sdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adbExecutable = Join-Path $sdkDirectory 'platform-tools\adb.exe'
function Invoke-TestAdb([string[]]$Arguments) {
    $result = & $adbExecutable -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
function Invoke-TestPhase([string]$Phase) {
    $arguments = @('shell','am','instrument','-w')
    if ($Phase -ne 'full') { $arguments += @('-e','phase',$Phase) }
    $arguments += 'com.paper.nightlock.test/com.paper.nightlock.SmokeInstrumentation'
    $output = Invoke-TestAdb $arguments
    Write-Host $output
    $expected = if ($Phase -eq 'full') { 'ALL SMOKE TESTS PASSED' } else { "PHASE $Phase PASSED" }
    if (!$output.Contains($expected)) { throw "Device test phase failed: $Phase" }
}
$api = Invoke-TestAdb @('shell','getprop','ro.build.version.sdk')
if ($api.Trim() -ne '36') { throw 'Start an Android 16 / API 36 emulator for this acceptance suite.' }
Invoke-TestAdb @('install','-r',(Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')) | Write-Host
Invoke-TestAdb @('install','-r',(Join-Path $PSScriptRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) | Write-Host
# The full runner itself requires an empty library before making any changes.
Invoke-TestPhase 'full'
Invoke-TestPhase 'recovery-setup'
Invoke-TestAdb @('reboot') | Out-Null
$deadline = [DateTime]::UtcNow.AddMinutes(4)
$booted = $false
while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Seconds 2
    try { $booted = (Invoke-TestAdb @('shell','getprop','sys.boot_completed')).Trim() -eq '1' } catch { continue }
    if ($booted) { break }
}
if (!$booted) { throw 'Emulator reboot timed out; recovery test data is still present.' }
Invoke-TestAdb @('shell','input','keyevent','KEYCODE_WAKEUP') | Out-Null
Invoke-TestAdb @('shell','wm','dismiss-keyguard') | Out-Null
$bound = $false
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $accessibility = Invoke-TestAdb @('shell','dumpsys','accessibility')
    if ($accessibility -match 'Bound services:\{Service') { $bound = $true; break }
    Start-Sleep -Milliseconds 500
}
if (!$bound) { throw 'Accessibility service did not bind after reboot.' }
$alarms = Invoke-TestAdb @('shell','dumpsys','alarm')
if (!$alarms.Contains('com.paper.nightlock.START') -or !$alarms.Contains('com.paper.nightlock.END')) {
    throw 'Night boundaries were not rescheduled after reboot.'
}
Invoke-TestAdb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
Invoke-TestAdb @('shell','am','start','-n','com.paper.nightlock.test/com.paper.nightlock.TestTargetActivity') | Out-Null
Start-Sleep -Seconds 2
$windows = Invoke-TestAdb @('shell','dumpsys','window','windows')
if (!$windows.Contains('type=ACCESSIBILITY_OVERLAY') -and !$windows.Contains('ty=ACCESSIBILITY_OVERLAY')) {
    throw 'Expected a fresh typing overlay after reboot.'
}
Write-Host 'PASS real reboot restored service, both alarms, and fresh typing restriction'
# Android intentionally kills the application when this permission is revoked, so check from outside its process.
Invoke-TestAdb @('shell','appops','set','com.paper.nightlock','SCHEDULE_EXACT_ALARM','deny') | Out-Null
Start-Sleep -Seconds 1
Invoke-TestPhase 'post-revoke-check'
Invoke-TestPhase 'cleanup'
Write-Host 'ALL ANDROID 16 ACCEPTANCE TESTS PASSED'
