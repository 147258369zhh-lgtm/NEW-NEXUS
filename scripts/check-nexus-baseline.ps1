[CmdletBinding()]
param(
    [string]$Aapt2Override
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$reportDirectory = Join-Path $repoRoot ('build\reports\nexus-baseline\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null

Push-Location $repoRoot
try {
    & python scripts/verify_nexus_identity.py
    if ($LASTEXITCODE -ne 0) { throw 'Nexus identity verification failed.' }

    $gradleArguments = @('checkNexusBaseline', '--no-daemon', '--no-configuration-cache', '--max-workers=1', '--console=plain')
    if ($Aapt2Override) {
        if (-not (Test-Path -LiteralPath $Aapt2Override -PathType Leaf)) { throw 'AAPT2 override does not exist.' }
        $gradleArguments += "-Pandroid.aapt2FromMavenOverride=$Aapt2Override"
    }
    & .\gradlew.bat @gradleArguments 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'gradle.log')
    $buildExitCode = $LASTEXITCODE

    # Preserve the actual reports before a later focused run replaces Gradle's test outputs.
    foreach ($module in @('app', 'kernel-api', 'application-api')) {
        $source = Join-Path $repoRoot "$module\build\test-results\testDebugUnitTest"
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $reportDirectory $module) -Recurse
        }
    }
    if ($buildExitCode -ne 0) { throw "Nexus baseline failed. Evidence: $reportDirectory" }
    Write-Output "Nexus baseline passed. Evidence: $reportDirectory"
} finally {
    Pop-Location
}
