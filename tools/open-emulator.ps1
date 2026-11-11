param([string]$SdkPath = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (!$SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkPath 'platform-tools\adb.exe'
$avdRoot = Join-Path ([IO.Path]::GetTempPath()) 'NoteLab-avd'
$project = Split-Path $PSScriptRoot -Parent
if (!(Test-Path -LiteralPath (Join-Path $avdRoot 'NoteLabDemo.ini'))) {
    throw 'NoteLabDemo AVD not found in the temporary directory.'
}
$devices = & $adb devices
if (!($devices -match '^emulator-5556\s+device')) {
    $env:ANDROID_AVD_HOME = $avdRoot
    $env:ANDROID_SDK_ROOT = $SdkPath
    Start-Process -FilePath (Join-Path $SdkPath 'emulator\emulator.exe') -WindowStyle Normal `
        -ArgumentList @('-avd','NoteLabDemo','-gpu','host','-feature','-Vulkan',
            '-memory','2048','-cores','2','-port','5556','-no-audio','-no-snapshot','-no-boot-anim')
}
& $adb -s emulator-5556 wait-for-device
$ready = $false
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ((& $adb -s emulator-5556 shell getprop sys.boot_completed) -match '1') { $ready = $true; break }
    Start-Sleep -Seconds 2
}
if (!$ready) { throw 'Android boot timed out.' }
& $adb -s emulator-5556 shell wm size 720x1600
& $adb -s emulator-5556 shell wm density 280
Push-Location -LiteralPath $project
try {
    if (!(& $adb -s emulator-5556 shell pm path com.example.notelab)) {
        & $adb -s emulator-5556 install -r 'build\NoteLab-debug.apk'
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
    }
    & $adb -s emulator-5556 shell am start -n com.example.notelab/.MainActivity
} finally { Pop-Location }
