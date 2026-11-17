[CmdletBinding()]
param(
    [switch]$RunTests,
    [switch]$SkipTests,
    [string]$Destination,
    [string]$Aapt2Override
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$signingFile = if ($env:NEXUS_SIGNING_PROPERTIES) {
    $env:NEXUS_SIGNING_PROPERTIES
} else {
    Join-Path $env:USERPROFILE '.nexus-signing\signing.properties'
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

if (@('storeFile', 'storePassword', 'keyAlias', 'keyPassword') | Where-Object { [string]::IsNullOrWhiteSpace($settings[$_]) }) {
    throw "Release signing properties are incomplete: $signingFile"
}
if ($RunTests -and $SkipTests) { throw 'Choose RunTests or SkipTests, not both.' }
$signingNames = @('NEXUS_RELEASE_STORE_FILE', 'NEXUS_RELEASE_STORE_PASSWORD', 'NEXUS_RELEASE_KEY_ALIAS', 'NEXUS_RELEASE_KEY_PASSWORD')
$signingProperties = @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')
$previousSigning = @{}
foreach ($name in $signingNames) { $previousSigning[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$gradleArgs = @('--no-daemon', '--no-configuration-cache', '--max-workers=1', '--console=plain', '-Pandroid.overridePathCheck=true')
if ($Aapt2Override) { $gradleArgs += "-Pandroid.aapt2FromMavenOverride=$([IO.Path]::GetFullPath($Aapt2Override))" }

Push-Location $repoRoot
try {
    for ($i = 0; $i -lt $signingNames.Count; $i++) {
        [Environment]::SetEnvironmentVariable($signingNames[$i], $settings[$signingProperties[$i]], 'Process')
    }
    if ($RunTests) {
        # Android's JVM tests are bound to the configured testBuildType (debug for this project).
        # The signed release APK is still assembled and checked separately below.
        & .\gradlew.bat :app:testDebugUnitTest @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Release unit tests failed.' }
    }
    & .\gradlew.bat :app:assembleRelease @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release assembly failed.' }
} finally {
    foreach ($name in $signingNames) { [Environment]::SetEnvironmentVariable($name, $previousSigning[$name], 'Process') }
    Pop-Location
}

$apk = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'app\build\outputs\apk\release') -Filter '*.apk' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { throw 'Release APK was not produced.' }

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) { $sdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
}
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { throw 'Android SDK was not found.' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw 'Release signature verifier was not found.' }
$signature = (& $apksigner verify --verbose --print-certs $apk.FullName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $signature -match 'CN=Android Debug' -or $signature -notmatch 'Verified using v[23].*: true') {
    throw 'A production v2/v3 release signature is required.'
}
$aapt2 = if ($Aapt2Override) { [IO.Path]::GetFullPath($Aapt2Override) } else { Join-Path $buildTools.FullName 'aapt2.exe' }
$badging = (& $aapt2 dump badging $apk.FullName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='io.github.zhh_lgtm.nexus'" -or $badging -match 'application-debuggable') {
    throw 'Release application identity or debuggable flag is invalid.'
}

$releaseVersion = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value
$releaseVersionCode = [int][regex]::Match($badging, "versionCode='([0-9]+)'").Groups[1].Value
& (Join-Path $PSScriptRoot 'verify-release.ps1') -Apk $apk.FullName -SdkRoot $sdkRoot `
    -ReportDirectory (Join-Path $repoRoot "build\reports\nexus-delivery\release-$releaseVersion") `
    -ExpectedVersion $releaseVersion -ExpectedVersionCode $releaseVersionCode

if ($Destination) {
    $destinationPath = [IO.Path]::GetFullPath($Destination)
    Copy-Item -LiteralPath $apk.FullName -Destination $destinationPath -Force
    $apk = Get-Item -LiteralPath $destinationPath
}

$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $apk.FullName
[IO.File]::WriteAllText("$($apk.FullName).sha256", "$($hash.Hash.ToLowerInvariant())  $($apk.Name)`n", [Text.Encoding]::ASCII)
[pscustomobject]@{
    Apk = $apk.FullName
    Bytes = $apk.Length
    Sha256 = $hash.Hash
} | Format-List
