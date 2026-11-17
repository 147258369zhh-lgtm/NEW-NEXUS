[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Destination
)

$ErrorActionPreference = 'Stop'
$sourcePath = [IO.Path]::GetFullPath($Source).TrimEnd('\', '/')
$destinationPath = [IO.Path]::GetFullPath($Destination)
if (-not (Test-Path -LiteralPath $sourcePath -PathType Container)) { throw "Source directory not found: $sourcePath" }
if ([IO.Path]::GetExtension($destinationPath) -ne '.zip') { throw 'Destination must end in .zip' }
if (-not (Test-Path -LiteralPath ([IO.Path]::GetDirectoryName($destinationPath)) -PathType Container)) {
    throw "Destination directory not found: $([IO.Path]::GetDirectoryName($destinationPath))"
}
if (Test-Path -LiteralPath $destinationPath) { throw "Destination already exists: $destinationPath" }

$excludedSegments = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@('build', '.gradle', '.git', '.kotlin', '.analysis', '.cxx') | ForEach-Object { [void]$excludedSegments.Add($_) }
$excludedExtensions = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@('.apk', '.aab', '.class') | ForEach-Object { [void]$excludedExtensions.Add($_) }
$sourceParent = [IO.Path]::GetDirectoryName($sourcePath)
$files = Get-ChildItem -LiteralPath $sourcePath -Recurse -File -Force | Where-Object {
    $relative = $_.FullName.Substring($sourcePath.Length).TrimStart('\', '/') -replace '\\', '/'
    $segments = $relative -split '/'
    -not ($segments | Where-Object { $excludedSegments.Contains($_) }) -and
        -not $excludedExtensions.Contains($_.Extension)
}
$relativePaths = $files | ForEach-Object { $_.FullName.Substring($sourceParent.Length).TrimStart('\', '/') }
$listPath = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllLines($listPath, $relativePaths, [Text.UTF8Encoding]::new($false))
    & tar.exe -a -c -f $destinationPath -C $sourceParent -T $listPath
    if ($LASTEXITCODE -ne 0) { throw "tar failed with exit code $LASTEXITCODE" }
} finally {
    if (Test-Path -LiteralPath $listPath) { [IO.File]::Delete($listPath) }
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationPath).Hash.ToUpperInvariant()
[IO.File]::WriteAllText("$destinationPath.sha256", "$hash  $([IO.Path]::GetFileName($destinationPath))`n", [Text.Encoding]::ASCII)
[pscustomobject]@{ Source = $sourcePath; Destination = $destinationPath; Files = $files.Count; Sha256 = $hash }
