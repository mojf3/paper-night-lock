param([switch]$IncludeDeviceTests)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
if (!$env:JAVA_HOME) {
    $studioRuntime = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioRuntime 'bin\java.exe')) { $env:JAVA_HOME = $studioRuntime }
}
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar'))) {
    throw 'Please install Android SDK Platform 36 in Android Studio, or configure ANDROID_HOME.'
}
$sdkProperty = 'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
Set-Content -LiteralPath (Join-Path $projectDirectory 'local.properties') -Value $sdkProperty -Encoding ascii
$gradleCache = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-9.1.0-all'
$cachedGradle = if (Test-Path -LiteralPath $gradleCache) {
    Get-ChildItem -LiteralPath $gradleCache -Recurse -Filter 'gradle.bat' | Select-Object -First 1 -ExpandProperty FullName
}
$gradleExecutable = if ($cachedGradle) { $cachedGradle } else { Join-Path $projectDirectory 'gradlew.bat' }
$tasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug')
if ($IncludeDeviceTests) { $tasks += ':app:assembleDebugAndroidTest' }
& $gradleExecutable -p $projectDirectory @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
Write-Host ('APK: ' + (Join-Path $projectDirectory 'app\build\outputs\apk\debug\app-debug.apk'))
