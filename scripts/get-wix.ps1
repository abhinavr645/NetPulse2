param([string]$Directory = '')
$ErrorActionPreference = 'Stop'
$candidates = New-Object 'System.Collections.Generic.List[string]'
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
function Add-WixCandidate([string]$Path) {
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $expanded = [Environment]::ExpandEnvironmentVariables($Path.Trim().Trim('"'))
        if ($seen.Add($expanded)) { $candidates.Add($expanded) }
    }
}
if ($Directory) {
    Add-WixCandidate $Directory
} else {
    foreach ($tool in @('candle.exe','light.exe')) {
        $command = Get-Command $tool -ErrorAction SilentlyContinue
        if ($command) { Add-WixCandidate (Split-Path -Parent $command.Source) }
    }
    foreach ($scope in @('Process','Machine','User')) {
        $savedPath = [Environment]::GetEnvironmentVariable('Path',$scope)
        if ($savedPath) { foreach ($entry in $savedPath.Split([IO.Path]::PathSeparator)) { Add-WixCandidate $entry } }
        $wixRoot = [Environment]::GetEnvironmentVariable('WIX',$scope)
        if ($wixRoot) { Add-WixCandidate $wixRoot; Add-WixCandidate (Join-Path $wixRoot 'bin') }
    }
    foreach ($root in @($env:ProgramFiles,${env:ProgramFiles(x86)},(Join-Path $env:LOCALAPPDATA 'Programs'))) {
        if ($root -and (Test-Path -LiteralPath $root)) {
            foreach ($folder in Get-ChildItem -LiteralPath $root -Directory -Filter 'WiX Toolset*') {
                Add-WixCandidate (Join-Path $folder.FullName 'bin')
                Add-WixCandidate $folder.FullName
            }
        }
    }
    foreach ($key in @('HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
                       'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
                       'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*')) {
        foreach ($product in Get-ItemProperty $key -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -like 'WiX Toolset*' -and $_.InstallLocation }) {
            Add-WixCandidate $product.InstallLocation
            Add-WixCandidate (Join-Path $product.InstallLocation 'bin')
        }
    }
}
foreach ($candidate in $candidates) {
    $candle = Join-Path $candidate 'candle.exe'
    $light = Join-Path $candidate 'light.exe'
    if ((Test-Path -LiteralPath $candle) -and (Test-Path -LiteralPath $light)) {
        $compilerVersion = (Get-Item -LiteralPath $candle).VersionInfo
        $linkerVersion = (Get-Item -LiteralPath $light).VersionInfo
        if ($compilerVersion.FileMajorPart -ne 3 -or $linkerVersion.FileMajorPart -ne 3 -or $compilerVersion.FileVersion -ne $linkerVersion.FileVersion) {
            throw "Incompatible classic WiX tool pair: candle $($compilerVersion.FileVersion), light $($linkerVersion.FileVersion). Matching WiX 3.x tools are required by this script."
        }
        Write-Output (Resolve-Path -LiteralPath $candidate).Path
        return
    }
}
throw 'Installed candle.exe/light.exe were not found together. Install compatible WiX 3.x or pass -WixPath with its bin directory. No toolset was downloaded or substituted.'
