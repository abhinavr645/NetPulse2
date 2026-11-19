param(
    [ValidateSet('Image','Installer','All')][string]$Stage = 'All',
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '1.0.0',
    [string]$WixPath = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Build Windows packages on Windows.' }
$javaCommand = (Get-Command java.exe -ErrorAction Stop).Source
$jdkRoot = Split-Path -Parent (Split-Path -Parent $javaCommand)
$jpackage = Join-Path $jdkRoot 'bin\jpackage.exe'
$jlink = Join-Path $jdkRoot 'bin\jlink.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw 'A full JDK 25 with jpackage/jlink is required.' }
$jdkVersion = (& $jpackage --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $jdkVersion -notmatch '^25\.') { throw "Expected JDK 25; found $jdkVersion" }
$originalPath = $env:Path
if ($Stage -ne 'Image') {
    $WixPath = & (Join-Path $PSScriptRoot 'get-wix.ps1') -Directory $WixPath
    Write-Output ("Installed WiX: " + $WixPath + ' (candle/light ' + (Get-Item -LiteralPath (Join-Path $WixPath 'candle.exe')).VersionInfo.FileVersion + ')')
}
function Invoke-CheckedNative([string]$Executable, [string[]]$Arguments) {
    # Windows PowerShell 5.1 turns redirected native stderr into error records.
    # Maven's intentional failure-fixture warnings must not abort a successful build.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($nativeExit -ne 0) { throw "$Executable failed (exit code $nativeExit)." }
}
Push-Location $projectRoot
try {
    $work = Join-Path $projectRoot 'target\packaging'
    $image = Join-Path $work 'image\NetPulse'
    $icon = Join-Path $projectRoot 'packaging\windows\NetPulse.ico'
    if ($Stage -ne 'Installer') {
        & (Join-Path $PSScriptRoot 'new-netpulse-icon.ps1')
        # Maven clean operates only in this project. Reject redirected target trees.
        if ((Test-Path -LiteralPath 'target') -and ((Get-Item -LiteralPath 'target' -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Refusing to clean a redirected target directory.'
        }
        # jpackage/WiX can leave an image launcher read-only. Clear that flag only
        # in our generated packaging tree before Maven's normal target cleanup.
        if (Test-Path -LiteralPath $work) {
            $generatedRoot = (Resolve-Path -LiteralPath $work).Path
            $allowedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target\packaging'))
            if ($generatedRoot -ne $allowedRoot) { throw 'Unexpected packaging output location.' }
            $generatedItems = @(Get-Item -LiteralPath $work -Force) + @(Get-ChildItem -LiteralPath $work -Recurse -Force)
            if ($generatedItems | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
                throw 'Refusing to change attributes in a redirected packaging tree.'
            }
            foreach ($file in $generatedItems | Where-Object { -not $_.PSIsContainer -and $_.IsReadOnly }) { $file.IsReadOnly = $false }
        }
        Invoke-CheckedNative (Join-Path $projectRoot 'mvnw.cmd') @('--batch-mode','--no-transfer-progress','clean','verify')
        $dependencies = Join-Path $work 'dependencies'
        $modules = Join-Path $work 'javafx-modules'
        $applicationInput = Join-Path $work 'input'
        $runtime = Join-Path $work 'runtime'
        New-Item -ItemType Directory -Path $dependencies,$modules,$applicationInput -Force | Out-Null
        Invoke-CheckedNative (Join-Path $projectRoot 'mvnw.cmd') @('--batch-mode','--no-transfer-progress','org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies','-DincludeScope=runtime',"-DoutputDirectory=$dependencies")
        [xml]$pom = Get-Content -Raw -LiteralPath pom.xml
        $mainJar = "$($pom.project.artifactId)-$($pom.project.version).jar"
        Copy-Item -LiteralPath (Join-Path 'target' $mainJar) -Destination $applicationInput
        foreach ($jar in Get-ChildItem -LiteralPath $dependencies -Filter '*.jar') {
            if ($jar.Name -like 'javafx-*-win.jar') { Copy-Item -LiteralPath $jar.FullName -Destination $modules }
            elseif ($jar.Name -notlike 'javafx-*') { Copy-Item -LiteralPath $jar.FullName -Destination $applicationInput }
        }
        if (@(Get-ChildItem -LiteralPath $modules -Filter '*.jar').Count -ne 4) { throw 'Expected JavaFX base, graphics, controls and FXML Windows modules.' }
        Invoke-CheckedNative $jlink @('--module-path',((Join-Path $jdkRoot 'jmods') + [IO.Path]::PathSeparator + $modules),'--add-modules','java.se,jdk.unsupported,jdk.management,javafx.controls,javafx.fxml','--strip-debug','--no-header-files','--no-man-pages','--compress=zip-6','--output',$runtime)
        # Maven's Windows JavaFX JARs contain the native libraries that JMOD builds
        # normally put in bin. Copy them there for reliable clean-machine loading.
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        foreach ($jar in Get-ChildItem -LiteralPath $modules -Filter '*.jar') {
            $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
            try {
                foreach ($entry in $zip.Entries) {
                    if ($entry.Name -like '*.dll') {
                        $nativeFile = Join-Path $runtime ('bin\' + $entry.Name)
                        if (-not (Test-Path -LiteralPath $nativeFile)) {
                            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$nativeFile,$false)
                        }
                    }
                }
            } finally { $zip.Dispose() }
        }
        $imageArguments = @('--type','app-image','--name','NetPulse','--app-version',$Version,
            '--vendor','NetPulse College Project','--description','Live network traffic monitor and Ookla CLI dashboard',
            '--input',$applicationInput,'--main-jar',$mainJar,'--main-class','com.netpulse.ui.NetPulseApplication',
            '--runtime-image',$runtime,'--dest',(Join-Path $work 'image'),'--icon',$icon,
            '--java-options','--add-modules=javafx.controls,javafx.fxml',
            '--java-options','--enable-native-access=javafx.graphics,ALL-UNNAMED')
        Invoke-CheckedNative $jpackage $imageArguments
        $cfg = Get-Content -Raw -LiteralPath (Join-Path $image 'app\NetPulse.cfg')
        if ($cfg.Contains($projectRoot) -or $cfg -match '\.m2|test-classes') { throw 'Launcher contains development-only paths.' }
        if (Get-ChildItem -LiteralPath $image -Recurse -File -Filter 'speedtest.exe') { throw 'Ookla must never be included in the package.' }
        $manifest = [ordered]@{name='NetPulse';version=$Version;jdk=$jdkVersion;mainJar=$mainJar;mainJarSha256=(Get-FileHash (Join-Path $applicationInput $mainJar)).Hash;
            dependencies=@(Get-ChildItem $applicationInput -Filter '*.jar' | ForEach-Object Name);bundledModules=@(& (Join-Path $runtime 'bin\java.exe') --list-modules)}
        $manifest | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $work 'package-manifest.json')
        Write-Output "Application image: $image"
    }
    if ($Stage -ne 'Image') {
        if (-not (Test-Path -LiteralPath (Join-Path $image 'NetPulse.exe'))) { throw 'Build Stage Image first.' }
        $manifest = Get-Content -Raw -LiteralPath (Join-Path $work 'package-manifest.json') | ConvertFrom-Json
        if ($manifest.version -ne $Version) { throw 'Installer version must match the application image.' }
        $env:Path = "$WixPath;$originalPath"
        $destination = Join-Path $projectRoot 'target\installer'
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        $installer = Join-Path $destination "NetPulse-$Version.msi"
        if (Test-Path -LiteralPath $installer) { Remove-Item -LiteralPath $installer }
        Invoke-CheckedNative $jpackage @('--type','msi','--name','NetPulse','--app-version',$Version,'--vendor','NetPulse College Project','--description','Live network traffic monitor and Ookla CLI dashboard','--app-image',$image,'--dest',$destination,'--icon',$icon,'--install-dir','NetPulse','--win-per-user-install','--win-menu','--win-menu-group','NetPulse','--win-shortcut','--win-dir-chooser','--win-upgrade-uuid','3d17d0bf-80b7-4a9d-b49d-609f216b8e43','--verbose')
        Write-Output "Installer: $installer"
    }
} finally {
    $env:Path = $originalPath
    Pop-Location
}
