param(
    [string]$RealCli = '',
    [ValidateSet('system', '1.0', '1.25')][string]$UiScale = 'system',
    [switch]$LiveMonitor
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & .\mvnw.cmd --batch-mode --no-transfer-progress test-compile dependency:build-classpath '-Dmdep.outputFile=target/dashboard-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile the desktop verification harness.' }
    $dependencies = (Get-Content -Raw -LiteralPath target/dashboard-classpath.txt).Trim().Split([IO.Path]::PathSeparator)
    $modules = @($dependencies | Where-Object { (Split-Path -Leaf $_) -like 'javafx-*-win.jar' }) -join [IO.Path]::PathSeparator
    if (-not $modules) { throw 'Windows JavaFX modules were not resolved.' }
    $classPath = (@('target/test-classes', 'target/classes') + @($dependencies | Where-Object { (Split-Path -Leaf $_) -notlike 'javafx-*' })) -join [IO.Path]::PathSeparator
    $javaArguments = @('--module-path', $modules, '--add-modules', 'javafx.controls,javafx.fxml', '--enable-native-access=javafx.graphics,ALL-UNNAMED')
    if ($UiScale -ne 'system') { $javaArguments += "-Dglass.win.uiScale=$UiScale" }
    $javaArguments += @('-cp', $classPath, 'com.netpulse.speedtest.DashboardUiCheck')
    if ($RealCli) { $javaArguments += "--real-cli=$RealCli" }
    if ($LiveMonitor) { $javaArguments += '--live-monitor=true' }
    & java @javaArguments
    if ($LASTEXITCODE -ne 0) { throw 'Desktop verification failed; inspect its log.' }
} finally { Pop-Location }
