param(
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '1.0.0',
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$image = Join-Path $projectRoot 'target\packaging\image\NetPulse'
$metadataFile = Join-Path $projectRoot 'target\packaging\package-manifest.json'
$msi = Join-Path $projectRoot "target\installer\NetPulse-$Version.msi"
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'target\release' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath (Join-Path $image 'NetPulse.exe'))) { throw 'Build the existing jpackage application image first: scripts\package-windows.ps1 -Stage All' }
if (-not (Test-Path -LiteralPath $msi)) { throw 'The matching MSI must exist so both release formats are retained.' }
$metadata = Get-Content -Raw -LiteralPath $metadataFile | ConvertFrom-Json
if ($metadata.version -ne $Version) { throw 'Portable version must match the existing application image.' }
if ($metadata.mainJar -notmatch '^[\w.-]+\.jar$') { throw 'Unexpected main JAR name.' }
$sourceFiles = @(Get-ChildItem -LiteralPath $image -Recurse -Force -File)
if ($sourceFiles | Where-Object { $_.Name -ieq 'speedtest.exe' }) { throw 'Ookla CLI must not be distributed.' }
if ($sourceFiles | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected redirected payload file.' }

# A fresh staging directory avoids changing the existing app image or installer.
$stage = Join-Path $projectRoot ('target\portable-staging\' + [Guid]::NewGuid().ToString('N'))
$packageName = "NetPulse-$Version-Portable"
$packageRoot = Join-Path $stage $packageName
New-Item -ItemType Directory -Path $packageRoot,$OutputDirectory -Force | Out-Null
Copy-Item -LiteralPath $image -Destination $packageRoot -Recurse -Force
$portableImage = Join-Path $packageRoot 'NetPulse'
$fileManifest = @()
foreach ($file in $sourceFiles) {
    $relative = $file.FullName.Substring($image.Length + 1)
    $copy = Join-Path $portableImage $relative
    if (-not (Test-Path -LiteralPath $copy)) { throw "Missing copied file: $relative" }
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne $hash) { throw "Copied payload differs: $relative" }
    $fileManifest += [ordered]@{path=$relative;sha256=$hash}
}
$launcher = (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'packaging\windows\Launch-NetPulse.cmd')).Replace('@MAIN_JAR@',$metadata.mainJar)
$launcher = $launcher.Replace("`r`n","`n").Replace("`n","`r`n")
[IO.File]::WriteAllText((Join-Path $packageRoot 'Launch-NetPulse.cmd'),$launcher,[Text.Encoding]::ASCII)
$readme = (Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'packaging\windows\README-Portable.txt')).Replace('@VERSION@',$Version)
[IO.File]::WriteAllText((Join-Path $packageRoot 'README.txt'),$readme,[Text.Encoding]::ASCII)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = Join-Path $OutputDirectory "$packageName.zip"
if (Test-Path -LiteralPath $zip -PathType Container) { throw 'The ZIP output path is a directory.' }
$temporaryZip = Join-Path $OutputDirectory ($packageName + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
# ZipFile includes hidden metadata and empty directories, unlike Compress-Archive.
[IO.Compression.ZipFile]::CreateFromDirectory($packageRoot,$temporaryZip,[IO.Compression.CompressionLevel]::Optimal,$true)
Move-Item -LiteralPath $temporaryZip -Destination $zip -Force
$releaseMsi = Join-Path $OutputDirectory "NetPulse-$Version.msi"
$sourceMsiHash = (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash
if (Test-Path -LiteralPath $releaseMsi) {
    if ((Get-FileHash -LiteralPath $releaseMsi -Algorithm SHA256).Hash -ne $sourceMsiHash) {
        throw 'A different MSI with this version already exists in the release directory.'
    }
} else { Copy-Item -LiteralPath $msi -Destination $releaseMsi }
[ordered]@{version=$Version;zip=$zip;zipBytes=(Get-Item -LiteralPath $zip).Length;zipSHA256=(Get-FileHash -LiteralPath $zip).Hash;msi=$releaseMsi;msiSHA256=$sourceMsiHash;imageFileCount=$sourceFiles.Count;imageFiles=$fileManifest} |
    ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory 'portable-manifest.json')
Write-Output "Portable ZIP: $zip"
Write-Output "MSI retained: $releaseMsi"
