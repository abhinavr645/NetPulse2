# Refresh only this launch's environment, so a recently installed Winget CLI is
# visible even when the parent editor/terminal inherited an older PATH.
$ErrorActionPreference = 'Stop'
$inheritedPath = $env:Path
$pathSources = @(
    [Environment]::GetEnvironmentVariable('Path', 'Machine')
    [Environment]::GetEnvironmentVariable('Path', 'User')
    $inheritedPath
)
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$entries = New-Object 'System.Collections.Generic.List[string]'
foreach ($source in $pathSources) {
    if ([string]::IsNullOrWhiteSpace($source)) { continue }
    foreach ($entry in $source.Split([IO.Path]::PathSeparator)) {
        $expanded = [Environment]::ExpandEnvironmentVariables($entry.Trim())
        if (-not [string]::IsNullOrWhiteSpace($expanded) -and $seen.Add($expanded)) {
            $entries.Add($expanded)
        }
    }
}

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    # Saved Windows PATH entries have priority; process-only tooling stays available.
    $env:Path = $entries -join [IO.Path]::PathSeparator
    & .\mvnw.cmd --batch-mode --no-transfer-progress javafx:run
    if ($LASTEXITCODE -ne 0) { throw "NetPulse launch failed (exit code $LASTEXITCODE)." }
} finally {
    $env:Path = $inheritedPath
    Pop-Location
}
