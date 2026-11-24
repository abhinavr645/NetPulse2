# NetPulse 1.0.1 MSI rebuild

Built and tested on Windows 11 x64 on 19 November 2026. This patch release
contains the current dashboard layout correction. No application code,
dependency versions or packaging architecture changed during this rebuild.

## Deliverables

- Release MSI: `target/release/NetPulse-1.0.1.msi`
- Additional copy: `target/installer/NetPulse-1.0.1.msi`
- Size: **54,158,216 bytes** (51.65 MiB).
- SHA-256: `9349ABB223ED8FE96B9B0F1B7E64C075BA08590BA7D8A46B41560744721F6182`
- Checksum file: `target/release/NetPulse-1.0.1.msi.sha256`
- Application image:
  `target/msi-build-1.0.1-20261119-213624/target/packaging/image/NetPulse/`

The previous 1.0.0 MSI and portable ZIP were preserved and their SHA-256 hashes
checked unchanged. A new portable ZIP was not requested or generated.

## Build and toolchain

The existing scripts used Temurin JDK/jlink/jpackage **25.0.5**, Maven wrapper
**3.9.16**, JavaFX **25.0.1** and installed WiX candle/light **3.14.1.8722**.
`get-wix.ps1` detected `C:\Program Files (x86)\WiX Toolset v3.14\bin`.
Only the packaging process received the temporary WiX PATH update.

The build used an isolated copy of the current build inputs so Maven clean would
preserve earlier release artifacts. To reproduce this arrangement from the root:

```powershell
$buildRoot = Join-Path (Get-Location).Path ('target\msi-build-1.0.1-' + (Get-Date -Format yyyyMMdd-HHmmss))
New-Item -ItemType Directory -Path $buildRoot | Out-Null
foreach ($entry in @('pom.xml','mvnw','mvnw.cmd','.mvn','src','scripts','packaging')) {
    Copy-Item -LiteralPath $entry -Destination $buildRoot -Recurse -Force
}
& (Join-Path $buildRoot 'scripts\package-windows.ps1') -Stage All -Version 1.0.1
```

The generated MSI is under `$buildRoot\target\installer\`. The usual build in
the original checkout also supports
`.\scripts\package-windows.ps1 -Stage All -Version 1.0.1`, but its Maven clean
removes that checkout's existing target outputs.

The script ran `clean verify`, copied runtime dependencies, linked Java/JavaFX,
created the application image and invoked `jpackage --type msi --app-image ...`.
The MSI uses version **1.0.1**, vendor **NetPulse College Project**, the existing
multi-resolution icon, relative installation directory `NetPulse`, Start Menu
and Desktop shortcuts, and standard Windows Installer registration/uninstall.
The stable upgrade UUID is unchanged. The Maven JAR keeps the existing
`1.0.0-SNAPSHOT` name; MSI/native executable metadata is verified as 1.0.1.

The complete invocation, tool output and jpackage arguments are in
[build.txt](phase8-evidence/release-1.0.1/build.txt).
WiX returned success with its existing **LGHT1105** warning: full ICE validation
could not run under this host's system policy. No validation/security settings
were changed.

## Verification results

- **227 tests passed**, zero failures, errors or skips in the clean build.
- The packaged FXML contains the single gauge HBox, top-aligned columns and
  preferred-height benchmark card from the latest UI fix. CSS, both icon source
  assets and the saved-Windows-PATH discovery class are present.
- The installed configuration uses relative application paths and contains no
  development checkout, Maven cache or test-class path.
- Bundled Java/JavaFX runtime modules and the private JVM are installed. No Ookla
  executable is included.
- Actual MSI installation returned **0**. Start Menu and Desktop shortcuts were
  created with targets in `%LOCALAPPDATA%\NetPulse`, not the development image.
- Actual Start Menu and Desktop launch attempts were **blocked by Windows
  Application Control**, with the explicit error recorded in
  [shortcut-launch.json](phase8-evidence/release-1.0.1/shortcut-launch.json).
  Successful native application launch is not claimed.
- Actual uninstallation returned **0** and removed the installed directory,
  shortcuts and product registration. An initial verification script incorrectly
  treated a JSON array as one pipeline item; explicit per-path observations
  corrected the false shortcut warning. See
  [uninstall.json](phase8-evidence/release-1.0.1/uninstall.json).
- The MSI was reinstalled after cleanup testing, returning **0**, so **1.0.1 is
  left installed**. All **232 installed payload files** match the image hashes.
  The image's build-only `app/.jpackage.xml` is excluded from that comparison.
  See [file-audit.json](phase8-evidence/release-1.0.1/file-audit.json).

Installation commands used the release MSI with
`msiexec /i "<MSI>" /qn /norestart /l*v "<log>"`. Uninstall used
`msiexec /x {E0F509EB-2E97-38F0-B8C1-4716DCF7FB5E} /qn /norestart /l*v "<log>"`.
Complete logs remain at the project root as `latest-msi-*.log`; relevant excerpts
and structured results are under `docs/phase8-evidence/release-1.0.1/`.

## Limits

The application image includes its own runtime and does not require a separate
Java installation. Native launch is nevertheless blocked on this particular host;
no signing, alternate launcher, policy bypass or security change was introduced.
Consequently, dashboard rendering, live OSHI traffic/graph/totals, CLI discovery
from the installed Start Menu shortcut, manual selection and interactive shutdown
could not be retested through this installed native launcher. The source UI had
already passed actual Windows 100%/125% layout and live-monitor checks in the
[UI verification](ui-layout-verification.md), which is separate evidence.

Existing automatic/manual Ookla discovery and banner validation are retained;
the independent official CLI remains required and no agreements are accepted
automatically. No new benchmark was run. **No clean-machine/VM test was performed.**

MSI generation, installed payload integrity and install/uninstall are verified.
Native launch verification remains blocked; this is not a claim of completed
deployment readiness on this host. No further phase was started.
