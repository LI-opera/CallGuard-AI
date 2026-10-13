param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!(Test-Path (Join-Path $JdkPath 'bin/java.exe'))) { throw 'Provide -JdkPath with an installed JDK 21 directory.' }
if (!(Test-Path (Join-Path $SdkPath 'platforms/android-36/android.jar'))) { throw 'Provide -SdkPath with Android SDK platform 36 installed.' }
$saved = @{}
foreach ($key in @('JAVA_HOME','ANDROID_HOME','GRADLE_USER_HOME','JAVA_TOOL_OPTIONS')) { $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    $env:JAVA_HOME = $JdkPath
    $env:ANDROID_HOME = $SdkPath
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    $tempPath = Join-Path $projectRoot '.tmp'
    New-Item -ItemType Directory -Force $tempPath | Out-Null
    $env:JAVA_TOOL_OPTIONS = ($saved['JAVA_TOOL_OPTIONS'] + ' "-Djdk.net.unixdomain.tmpdir=' + $tempPath + '"').Trim()
    & (Join-Path $projectRoot 'android/gradlew.bat') -p (Join-Path $projectRoot 'android') @Tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}
