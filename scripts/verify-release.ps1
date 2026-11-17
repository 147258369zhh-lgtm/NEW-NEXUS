[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$SdkRoot,
    [Parameter(Mandatory)][string]$ReportDirectory,
    [string]$ExpectedVersion = '0.3.0',
    [int]$ExpectedVersionCode = 2026111102
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkFile = Get-Item -LiteralPath $Apk
$reportRoot = [IO.Path]::GetFullPath($ReportDirectory)
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$signature = (& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apkFile.FullName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $signature -match 'CN=Android Debug' -or $signature -notmatch 'Verified using v3 scheme.*: true') {
    throw 'A production v3 signature is required.'
}
# Newer Android verification uses v3 and need not evaluate the v2 block. Verify that block separately.
$signatureV2 = (& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --min-sdk-version 24 --max-sdk-version 27 $apkFile.FullName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $signatureV2 -notmatch 'Verified using v2 scheme.*: true') { throw 'The production v2 signature is missing or invalid.' }
[IO.File]::WriteAllText((Join-Path $reportRoot 'signature.txt'), $signature)
[IO.File]::WriteAllText((Join-Path $reportRoot 'signature-v2.txt'), $signatureV2)
$certificate = [regex]::Match($signature, 'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]{64})').Groups[1].Value
if (-not $certificate) { throw 'Signing certificate fingerprint is missing.' }
$analyzer = Join-Path $SdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'
$manifestText = (& $analyzer manifest print $apkFile.FullName | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Could not read the final APK manifest.' }
[xml]$manifest = $manifestText
$androidNs = 'http://schemas.android.com/apk/res/android'
$root = $manifest.DocumentElement
$application = $root.SelectSingleNode('application')
if ($root.GetAttribute('package') -ne 'io.github.zhh_lgtm.nexus' -or
    $root.GetAttribute('versionName', $androidNs) -ne $ExpectedVersion -or
    $root.GetAttribute('versionCode', $androidNs) -ne [string]$ExpectedVersionCode -or
    $application.GetAttribute('debuggable', $androidNs) -eq 'true' -or
    $application.GetAttribute('testOnly', $androidNs) -eq 'true') { throw 'APK identity, version or release flags do not match.' }
$requiredComponents = @(
    'io.github.mangi.eta.ui.MainActivity',
    'io.github.mangi.eta.agent.runtime.AgentRuntimeService',
    'io.github.mangi.eta.agent.runtime.AgentExecutionService',
    'io.github.mangi.eta.agent.accessibility.AgentAccessibilityService',
    'io.github.mangi.eta.agent.voice.EtaRecognitionService',
    'io.github.mangi.eta.agent.voice.ReadAloudPlaybackService',
    'io.github.mangi.eta.agent.voice.BackgroundWakeWordService',
    'io.github.mangi.eta.agent.packages.PackageSandboxService',
    'io.github.mangi.eta.agent.packages.PackageDesktopWidgetReceiver',
    'io.github.mangi.eta.ui.widget.PackageWidgetActivity',
    'io.github.mangi.eta.fork.tasks.AutomationSystemReceiver',
    'io.github.mangi.eta.fork.tasks.AutomationMonitorService'
)
$componentNodes = @($application.SelectNodes('activity|service|receiver|provider'))
foreach ($name in $requiredComponents) {
    if (-not ($componentNodes | Where-Object { $_.GetAttribute('name', $androidNs) -eq $name })) { throw "Required APK component is missing: $name" }
}
$sandbox = $componentNodes | Where-Object { $_.GetAttribute('name', $androidNs) -eq 'io.github.mangi.eta.agent.packages.PackageSandboxService' }
if ($sandbox.GetAttribute('isolatedProcess', $androidNs) -ne 'true' -or $sandbox.GetAttribute('exported', $androidNs) -ne 'false') {
    throw 'Plugin process isolation is missing in the release manifest.'
}
$accessibility = $componentNodes | Where-Object { $_.GetAttribute('name', $androidNs) -eq 'io.github.mangi.eta.agent.accessibility.AgentAccessibilityService' }
if ($accessibility.GetAttribute('permission', $androidNs) -ne 'android.permission.BIND_ACCESSIBILITY_SERVICE') { throw 'Accessibility binding permission is missing.' }
[IO.File]::WriteAllText((Join-Path $reportRoot 'AndroidManifest.xml'), $manifestText)
& (Join-Path $buildTools.FullName 'zipalign.exe') -c -P 16 4 $apkFile.FullName
if ($LASTEXITCODE -ne 0) { throw 'Final APK ZIP alignment failed.' }

$nativeReport = [Collections.Generic.List[object]]::new()
$archive = [IO.Compression.ZipFile]::OpenRead($apkFile.FullName)
try {
    $libraries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' })
    if ($libraries.Count -eq 0 -or ($libraries | Where-Object { $_.FullName -notlike 'lib/arm64-v8a/*' })) { throw 'Only ARM64 native libraries are allowed.' }
    foreach ($library in $libraries) {
        $stream = $library.Open(); $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        if ($bytes.Length -lt 64 -or [BitConverter]::ToUInt32($bytes, 0) -ne 0x464c457f -or $bytes[4] -ne 2 -or
            $bytes[5] -ne 1 -or [BitConverter]::ToUInt16($bytes, 18) -ne 183) { throw "Invalid ARM64 ELF: $($library.FullName)" }
        $headerOffset = [BitConverter]::ToUInt64($bytes, 32)
        $headerSize = [BitConverter]::ToUInt16($bytes, 54)
        $headerCount = [BitConverter]::ToUInt16($bytes, 56)
        if ($headerSize -lt 56 -or $headerCount -eq 0 -or $headerOffset + [uint64]$headerSize * $headerCount -gt $bytes.Length) { throw 'Invalid ELF program header table.' }
        $segments = [Collections.Generic.List[object]]::new()
        for ($index = 0; $index -lt $headerCount; $index++) {
            $offset = [int]($headerOffset + [uint64]$headerSize * $index)
            if ([BitConverter]::ToUInt32($bytes, $offset) -ne 1) { continue }
            $fileOffset = [BitConverter]::ToUInt64($bytes, $offset + 8)
            $address = [BitConverter]::ToUInt64($bytes, $offset + 16)
            $alignment = [BitConverter]::ToUInt64($bytes, $offset + 48)
            if ($alignment -lt 16384 -or ($alignment -band ($alignment - 1)) -ne 0 -or $fileOffset % 16384 -ne $address % 16384) {
                throw "Native library is not compatible with 16 KiB pages: $($library.FullName)"
            }
            $segments.Add([pscustomobject]@{Alignment=$alignment;Offset=$fileOffset;VirtualAddress=$address})
        }
        if ($segments.Count -eq 0) { throw 'ELF has no loadable segments.' }
        $nativeReport.Add([pscustomobject]@{Path=$library.FullName;Bytes=$library.Length;LoadSegments=$segments})
    }
    $names = @($archive.Entries | ForEach-Object FullName)
    foreach ($required in @('libquickjsjni.so','libtoolpkgwasm.so','libsherpa-onnx-jni.so','libiris_llama.so','libiris_mnn.so','liblitertlm_jni.so','libeta_pty.so')) {
        if ('lib/arm64-v8a/' + $required -notin $names) { throw "Required runtime is missing: $required" }
    }
} finally { $archive.Dispose() }
$report = [pscustomobject]@{
    Apk=$apkFile.FullName;Bytes=$apkFile.Length;Sha256=(Get-FileHash -LiteralPath $apkFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    ApplicationId=$root.GetAttribute('package');Version=$ExpectedVersion;VersionCode=$ExpectedVersionCode
    Debuggable=$false;TestOnly=$false;V2Signature=$true;V3Signature=$true;CertificateSha256=$certificate
    ZipAlignment=$true;Native16KiB=$true;NativeLibraries=$nativeReport;RequiredComponents=$requiredComponents
}
[IO.File]::WriteAllText((Join-Path $reportRoot 'verification.json'), ($report | ConvertTo-Json -Depth 8), [Text.Encoding]::UTF8)
[pscustomobject]@{Apk=$apkFile.FullName;Version=$ExpectedVersion;NativeLibraries=$nativeReport.Count;Native16KiB=$true;CertificateSha256=$certificate;Sha256=$report.Sha256} | Format-List
