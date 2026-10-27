param(
    [ValidateSet('check', 'build', 'install', 'launch', 'logs')]
    [string]$Action = 'check'
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adbExe = Join-Path $sdkDir 'platform-tools\adb.exe'
$studioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path -LiteralPath (Join-Path $studioJava 'bin\java.exe')) {
    $env:JAVA_HOME = $studioJava
}

function Invoke-Adb {
    if (-not (Test-Path -LiteralPath $adbExe)) { throw "ADB not found: $adbExe" }
    & $adbExe @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed with exit code $LASTEXITCODE" }
}

Push-Location -LiteralPath $projectDir
try {
    if (-not (Test-Path -LiteralPath 'local.properties')) {
        Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir=' + $sdkDir.Replace('\', '/').Replace(':', '\:')) -Encoding utf8
    }
    switch ($Action) {
        'check' { Invoke-Adb devices -l }
        'build' {
            & '.\gradlew.bat' :app:assembleDebug :app:lintDebug :app:testDebugUnitTest --console=plain
            if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE" }
        }
        'install' { Invoke-Adb -d install -r '.\app\build\outputs\apk\debug\app-debug.apk' }
        'launch' { Invoke-Adb -d shell am start -W --display 0 -n 'io.github.swarnimbandekar.knightlingo/.MainActivity' }
        'logs' { Invoke-Adb -d logcat -v time 'ChessTip:I' 'AndroidRuntime:E' '*:S' }
    }
}
finally { Pop-Location }
