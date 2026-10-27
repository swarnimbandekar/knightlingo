param(
    [Parameter(Mandatory = $true)]
    [string]$KeyStore,
    [Parameter(Mandatory = $true)]
    [string]$PasswordFile,
    [string]$KeyAlias = 'knightlingo'
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$keyStorePath = (Resolve-Path -LiteralPath $KeyStore).ProviderPath
$passwordPath = (Resolve-Path -LiteralPath $PasswordFile).ProviderPath
$sdkDir = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$buildTools = Join-Path $sdkDir 'build-tools\36.0.0'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$studioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (Test-Path -LiteralPath (Join-Path $studioJava 'bin\java.exe')) {
    $env:JAVA_HOME = $studioJava
}
foreach ($tool in @($zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Android Build Tools 36.0.0 not found: $tool" }
}

Push-Location -LiteralPath $projectDir
try {
    if (-not (Test-Path -LiteralPath 'local.properties')) {
        Set-Content -LiteralPath 'local.properties' -Value ('sdk.dir=' + $sdkDir.Replace('\', '/').Replace(':', '\:')) -Encoding utf8
    }
    & '.\gradlew.bat' :app:assembleRelease :app:lintRelease :app:testDebugUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Release build failed with exit code $LASTEXITCODE" }

    $releaseDir = Join-Path $projectDir 'app\build\outputs\apk\release'
    $metadata = Get-Content -LiteralPath (Join-Path $releaseDir 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.elements.Count -ne 1) { throw 'Expected one ARM64 release APK.' }
    $version = $metadata.elements[0].versionName
    $unsignedApk = Join-Path $releaseDir $metadata.elements[0].outputFile
    $outputDir = Join-Path $projectDir "artifacts\release-$version"
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $apkName = "Knightlingo-$version-arm64-v8a.apk"
    $signedApk = Join-Path $outputDir $apkName
    $alignedApk = Join-Path $releaseDir 'knightlingo-aligned-unsigned.apk'

    & $zipalign -f -P 16 4 $unsignedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & $apksigner sign --ks $keyStorePath --ks-key-alias $KeyAlias --ks-pass "file:$passwordPath" --v4-signing-enabled false --out $signedApk $alignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $zipalign -c -P 16 4 $signedApk
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    & $apksigner verify --verbose --print-certs $signedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

    $hash = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumPath = Join-Path $outputDir 'SHA256SUMS.txt'
    [System.IO.File]::WriteAllText($checksumPath, "$hash  $apkName`n", [System.Text.UTF8Encoding]::new($false))
    Write-Output "Release APK: $signedApk"
    Write-Output "SHA-256: $hash"
}
finally { Pop-Location }
