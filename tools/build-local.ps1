param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Tests
)
$ErrorActionPreference = 'Stop'
if (!$SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$JavaHome) { throw 'Set JAVA_HOME to a JDK (17 or newer).' }
$project = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $project
$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    New-Item -ItemType Directory -Path 'build' -Force | Out-Null
    if (!(Test-Path -LiteralPath 'build\debug.keystore')) {
        & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore 'build\debug.keystore' `
            -storepass android -keypass android -alias androiddebugkey -dname 'CN=NoteLab Debug' -keyalg RSA -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Could not create debug signing key.' }
    }
    $tasks = @(':app:assembleDebug')
    if ($Tests) { $tasks += ':app:assembleDebugAndroidTest' }
    & (Join-Path $JavaHome 'bin\java.exe') -Xmx64m -cp 'gradle\wrapper\gradle-wrapper.jar' `
        org.gradle.wrapper.GradleWrapperMain @tasks --no-daemon --max-workers=2 `
        '-Pandroid.overridePathCheck=true' '-Pkotlin.compiler.execution.strategy=in-process' `
        '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination 'build\NoteLab-debug.apk' -Force
    Write-Output 'Built build\NoteLab-debug.apk'
    if ($Tests) {
        Copy-Item -LiteralPath 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk' -Destination 'build\NoteLab-tests.apk' -Force
        Write-Output 'Built build\NoteLab-tests.apk'
    }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
    Pop-Location
}
