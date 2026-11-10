[CmdletBinding()]
param(
    [switch]$SkipTests,
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$signingFile = if ($env:ETA_SIGNING_PROPERTIES) {
    $env:ETA_SIGNING_PROPERTIES
} else {
    Join-Path $env:USERPROFILE '.eta-signing\signing.properties'
}

if (-not (Test-Path -LiteralPath $signingFile -PathType Leaf)) {
    throw "Release signing properties not found: $signingFile"
}

$settings = @{}
Get-Content -LiteralPath $signingFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $settings[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$env:ETA_RELEASE_STORE_FILE = $settings['storeFile']
$env:ETA_RELEASE_STORE_PASSWORD = $settings['storePassword']
$env:ETA_RELEASE_KEY_ALIAS = $settings['keyAlias']
$env:ETA_RELEASE_KEY_PASSWORD = $settings['keyPassword']

if (@($env:ETA_RELEASE_STORE_FILE, $env:ETA_RELEASE_STORE_PASSWORD, $env:ETA_RELEASE_KEY_ALIAS, $env:ETA_RELEASE_KEY_PASSWORD) -contains '') {
    throw "Release signing properties are incomplete: $signingFile"
}

Push-Location $repoRoot
try {
    if (-not $SkipTests) {
        # Android's JVM tests are bound to the configured testBuildType (debug for this project).
        # The signed release APK is still assembled and checked separately below.
        & .\gradlew.bat :app:testDebugUnitTest --no-configuration-cache --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Release unit tests failed.' }
    }
    & .\gradlew.bat :app:assembleRelease --no-configuration-cache --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release assembly failed.' }
} finally {
    Pop-Location
}

$apk = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'app\build\outputs\apk\release') -Filter '*.apk' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { throw 'Release APK was not produced.' }

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (Test-Path -LiteralPath $apksigner) {
    & $apksigner verify --verbose --print-certs $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed.' }
}

if ($Destination) {
    $destinationPath = [IO.Path]::GetFullPath($Destination)
    Copy-Item -LiteralPath $apk.FullName -Destination $destinationPath -Force
    $apk = Get-Item -LiteralPath $destinationPath
}

$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $apk.FullName
[pscustomobject]@{
    Apk = $apk.FullName
    Bytes = $apk.Length
    Sha256 = $hash.Hash
} | Format-List
