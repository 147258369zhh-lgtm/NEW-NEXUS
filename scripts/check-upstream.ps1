[CmdletBinding()]
param(
    [string]$ManifestPath,
    [string]$Workspace,
    [string]$OutputPath,
    [switch]$WriteReport
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if (-not $ManifestPath) { $ManifestPath = Join-Path $repoRoot "upstream-fork.json" }
if (-not $Workspace) { $Workspace = $repoRoot }
if (-not $OutputPath) { $OutputPath = Join-Path $repoRoot "docs\UPSTREAM_SYNC_REPORT.md" }
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$repository = [string]$manifest.repository
$branch = [string]$manifest.branch
$baselineCommit = [string]$manifest.baselineCommit
$baselineRoot = Join-Path $Workspace ([string]$manifest.baselineSnapshot)
$headers = @{
    Accept = "application/vnd.github+json"
    "User-Agent" = "Eta-No-Git-Upstream-Check"
    "X-GitHub-Api-Version" = "2022-11-28"
}

function Get-RelativeUnixPath([string]$root, [string]$path) {
    [System.IO.Path]::GetRelativePath($root, $path).Replace('\', '/')
}

function Get-Excluded([string]$relativePath) {
    if ($relativePath -eq '.git' -or $relativePath.StartsWith('.git/')) { return $true }
    if ($relativePath -eq 'upstream-current' -or $relativePath.StartsWith('upstream-current/')) { return $true }
    foreach ($prefix in $manifest.generatedSourceRoots) {
        $normalized = ([string]$prefix).TrimEnd('/')
        if ($relativePath -eq $normalized -or $relativePath.StartsWith("$normalized/")) { return $true }
    }
    return $false
}

function Get-TextFileHashes([string]$root) {
    $hashes = @{}
    Get-ChildItem -LiteralPath $root -File -Recurse -Force | ForEach-Object {
        $relative = Get-RelativeUnixPath $root $_.FullName
        if (Get-Excluded $relative) { return }
        $hashes[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $hashes
}

function Get-GitBlobHash([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $header = [System.Text.Encoding]::ASCII.GetBytes("blob $($bytes.Length)`0")
    $payload = New-Object byte[] ($header.Length + $bytes.Length)
    [System.Buffer]::BlockCopy($header, 0, $payload, 0, $header.Length)
    [System.Buffer]::BlockCopy($bytes, 0, $payload, $header.Length, $bytes.Length)
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    try {
        return (($sha1.ComputeHash($payload) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha1.Dispose()
    }
}

function Get-BaselineGitHashes([string]$root) {
    $hashes = @{}
    Get-ChildItem -LiteralPath $root -File -Recurse -Force | ForEach-Object {
        $relative = Get-RelativeUnixPath $root $_.FullName
        $hashes[$relative] = Get-GitBlobHash $_.FullName
    }
    return $hashes
}

$latestRef = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repository/git/ref/heads/$branch"
$latestCommit = [string]$latestRef.object.sha
if ($latestCommit -eq $baselineCommit) {
    $report = @(
        "# Upstream Sync Report",
        "",
        "- Repository: ``$repository``",
        "- Baseline: ``$baselineCommit``",
        "- Latest: ``$latestCommit``",
        "- Result: baseline is current"
    ) -join [Environment]::NewLine
} else {
    $latestTreeResponse = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repository/git/trees/$latestCommit`?recursive=1"
    if ($latestTreeResponse.truncated) { throw "GitHub tree response was truncated." }
    $latestFiles = @{}
    foreach ($entry in $latestTreeResponse.tree) {
        if ($entry.type -eq 'blob') { $latestFiles[[string]$entry.path] = [string]$entry.sha }
    }

    $baselineFiles = Get-BaselineGitHashes $baselineRoot
    $localFiles = @{}
    Get-ChildItem -LiteralPath $Workspace -File -Recurse -Force | ForEach-Object {
        $relative = Get-RelativeUnixPath $Workspace $_.FullName
        if (Get-Excluded $relative) { return }
        $localFiles[$relative] = Get-GitBlobHash $_.FullName
    }
    $allPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in $latestFiles.Keys) { [void]$allPaths.Add($path) }
    foreach ($path in $baselineFiles.Keys) { [void]$allPaths.Add($path) }
    foreach ($path in $localFiles.Keys) { [void]$allPaths.Add($path) }

    $upstreamChanged = New-Object System.Collections.Generic.List[string]
    foreach ($path in $latestFiles.Keys) {
        if (-not $baselineFiles.ContainsKey($path)) { $upstreamChanged.Add($path); continue }
        if ($latestFiles[$path] -ne $baselineFiles[$path]) { $upstreamChanged.Add($path) }
    }

    $forkOnly = @($localFiles.Keys | Where-Object {
        -not $baselineFiles.ContainsKey($_) -and -not $latestFiles.ContainsKey($_)
    } | Sort-Object)
    $forkModified = @($localFiles.Keys | Where-Object {
        $baselineFiles.ContainsKey($_) -and
        $localFiles[$_] -ne $baselineFiles[$_]
    } | Sort-Object)
    $upstreamAdded = @($latestFiles.Keys | Where-Object { -not $baselineFiles.ContainsKey($_) } | Sort-Object)
    $upstreamDeleted = @($baselineFiles.Keys | Where-Object { -not $latestFiles.ContainsKey($_) } | Sort-Object)
    $safeUpdates = @($upstreamChanged | Where-Object {
        $baselineFiles.ContainsKey($_) -and $localFiles.ContainsKey($_) -and
        $localFiles[$_] -eq $baselineFiles[$_]
    } | Sort-Object)
    $mergeConflicts = @($upstreamChanged | Where-Object {
        $baselineFiles.ContainsKey($_) -and $localFiles.ContainsKey($_) -and
        $localFiles[$_] -ne $baselineFiles[$_]
    } | Sort-Object)
    $deletedConflicts = @($upstreamDeleted | Where-Object {
        $localFiles.ContainsKey($_) -and $localFiles[$_] -ne $baselineFiles[$_]
    } | Sort-Object)

    $reportLines = New-Object System.Collections.Generic.List[string]
    $reportLines.Add("# Upstream Sync Report")
    $reportLines.Add("")
    $reportLines.Add("- Repository: ``$repository``")
    $reportLines.Add("- Baseline: ``$baselineCommit``")
    $reportLines.Add("- Latest: ``$latestCommit``")
    $reportLines.Add("- Fork-only files: $($forkOnly.Count)")
    $reportLines.Add("- Locally modified upstream files: $($forkModified.Count)")
    $reportLines.Add("- Upstream-added files: $($upstreamAdded.Count)")
    $reportLines.Add("- Upstream-deleted files: $($upstreamDeleted.Count)")
    $reportLines.Add("- Upstream changes safe to review/copy: $($safeUpdates.Count)")
    $reportLines.Add("- Upstream/local merge conflicts: $($mergeConflicts.Count)")
    $reportLines.Add("- Upstream deletions with local edits: $($deletedConflicts.Count)")
    $reportLines.Add("")
    $reportLines.Add("## Review before import")
    $reportLines.Add("")
    $reportLines.Add("The script is read-only. Fetch the changed upstream files into a temporary directory, review them, and apply them with the three-way procedure in ``docs/FORK_UPSTREAM_SYNC.md``. Never overwrite a fork modification or fork-only path automatically.")
    foreach ($section in @(
        @("Upstream added", $upstreamAdded),
        @("Upstream deleted", $upstreamDeleted),
        @("Safe upstream updates (local matches baseline)", $safeUpdates),
        @("Three-way merge conflicts (both sides changed)", $mergeConflicts),
        @("Deletion conflicts", $deletedConflicts),
        @("Locally modified upstream", $forkModified)
    )) {
        $reportLines.Add("")
        $reportLines.Add("### $($section[0])")
        $reportLines.Add("")
        foreach ($path in $section[1]) { $reportLines.Add("- ``$path``") }
    }
    $report = $reportLines -join [Environment]::NewLine
}

if ($WriteReport) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    Set-Content -LiteralPath $OutputPath -Value $report -Encoding utf8
    Write-Output "Wrote $OutputPath"
} else {
    Write-Output $report
}
