# Phase 8 — Windows MSI packaging with installed WiX

**Latest rebuild:** [NetPulse 1.0.1 verification](release-1.0.1-verification.md)
records the MSI containing the dashboard layout fix, 227 passing tests and current
installation results. The original 1.0.0 packaging record follows below.

Verification date: **19 November 2026**. An actual MSI was built with JDK jpackage
and the machine's installed WiX toolset, installed successfully, and uninstalled
successfully. **Native application/shortcut launch is blocked by this host's
Windows Application Control policy. Deployment verification is therefore partial.**

## Environment inspected

Reviewed pom.xml, Maven build/wrapper, application entry point, FXML/CSS/resources,
runtime dependencies, Phase 7 report, Ookla locator and development PATH launcher.
The existing Maven version/dependency pins and single-module architecture remain.

| Tool | Observed version/location |
| --- | --- |
| JDK, jpackage, jlink | Temurin 25.0.5+8, jpackage 25.0.5 |
| Maven wrapper distribution | 3.9.16 |
| JavaFX | 25.0.1 |
| Installed WiX candle/light | Both 3.14.1.8722 |
| Detected WiX directory | `C:\Program Files (x86)\WiX Toolset v3.14\bin` |

Both WiX executables were run successfully and used by jpackage. Their directory
was absent from the inherited terminal PATH; this was not treated as a missing
installation. `get-wix.ps1` checks process/saved PATH, WIX environment variables,
standard installation roots and installed-product registry locations. An explicit
`-WixPath` is also supported. The detected bin directory is temporarily prepended
for jpackage and the caller's PATH is restored. No replacement toolset was
downloaded or installed in this final workflow.

Evidence: [toolchain](phase8-evidence/final-toolchain.json),
[complete build output](phase8-evidence/final-build.txt).

## Packaging architecture

### Stage A — self-contained application image

`package-windows.ps1 -Stage Image` runs Maven **clean verify**, collects only
runtime dependencies using the pinned Maven Dependency Plugin 3.8.1, and creates
a private runtime with jlink. JavaFX's Windows modules are linked into that runtime;
their native DLLs are copied from the resolved Windows JARs into runtime/bin without
replacing existing JDK DLLs. The application and non-JavaFX runtime dependencies
remain ordinary JARs on the native launcher's relative `$APPDIR` classpath.

Linked roots: `java.se`, `jdk.unsupported`, `jdk.management`, `javafx.controls`,
`javafx.fxml`; transitive modules include JavaFX base/graphics. The runtime retains
its legal notices. There is no dependency on Maven, a source checkout, ~/.m2,
test classes or a separate Java/JavaFX installation at runtime.

jpackage creates `NetPulse.exe`, its relative launcher configuration and the
private runtime. The launch options enable JavaFX controls/FXML and native access
for JavaFX graphics/JNA. FXML, CSS, PNG and original SVG resources are in the main
application JAR. Existing dashboard layout/features were not redesigned.

### Stage B — MSI

The second jpackage invocation uses **`--type msi --app-image`** with the installed
candle/light pair. Metadata:

- Name: **NetPulse**; Windows application version **1.0.0**.
- Vendor: **NetPulse College Project**.
- Description: **Live network traffic monitor and Ookla CLI dashboard**.
- Per-user installation, relative installation directory **NetPulse**.
- Start Menu group **NetPulse**, Desktop shortcut and directory chooser.
- Stable upgrade UUID `3d17d0bf-80b7-4a9d-b49d-609f216b8e43`.
- Standard Windows Installer uninstall registration; no custom uninstaller.

The observed installation directory was `%LOCALAPPDATA%\NetPulse`. The source
does not hardcode a user account or user-specific install/WiX/Winget directory.
The Maven project keeps its existing `1.0.0-SNAPSHOT` version; the Windows package
has the explicit numeric version required for MSI metadata.

## Original application icon

The final icon is an original teal pulse between two blue network nodes on a
rounded dark background. It replaces the earlier provisional monogram, with no
third-party logo. Its source is
`src/main/resources/icons/netpulse.svg`. `new-netpulse-icon.ps1` renders that source
into the application's 256px PNG and a valid 32-bit Windows ICO containing
**16, 24, 32, 48, 64, 128 and 256px** images.

The ICO is used by jpackage; the PNG is used by the JavaFX window. The small source
assets are retained in the project, while the runtime image and installer are
under ignored target output. Icon generation is part of the image build.

## Reproduction commands

From PowerShell in the project directory, with JDK 25 and compatible installed
WiX 3.x candle/light tools available:

```powershell
# Application build and complete test suite only
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify

# Both packaging stages, detecting the installed toolset
.\scripts\package-windows.ps1 -Stage All

# Or run the stages independently
.\scripts\package-windows.ps1 -Stage Image
.\scripts\package-windows.ps1 -Stage Installer

# Resolve/inspect the installed WiX bin directory
.\scripts\get-wix.ps1
```

`-WixPath <installed-bin-directory>` can be passed to the packaging script if an
installation has a nonstandard layout. Missing/incompatible classic tools produce
a useful error; no other packager/toolset is silently substituted. The final build
log contains the actual jpackage-generated candle/light commands and exit codes.

The MSI generation command assembled by the script is equivalent to:

```powershell
jpackage --type msi --name NetPulse --app-version 1.0.0 `
  --vendor 'NetPulse College Project' `
  --description 'Live network traffic monitor and Ookla CLI dashboard' `
  --app-image target\packaging\image\NetPulse --dest target\installer `
  --icon packaging\windows\NetPulse.ico --install-dir NetPulse `
  --win-per-user-install --win-menu --win-menu-group NetPulse `
  --win-shortcut --win-dir-chooser `
  --win-upgrade-uuid 3d17d0bf-80b7-4a9d-b49d-609f216b8e43 --verbose
```

The script handles Windows PowerShell 5.1 native stderr correctly: deliberate
failure-test warnings do not abort a successful Maven run, but nonzero native exit
codes fail the build. It also safely clears read-only attributes left in its own
generated packaging tree before Maven clean, rejecting redirected output trees.

Commands/versions/output names are reproducible; byte-identical MSI output is not
promised because jpackage/WiX generate package metadata/timestamps.

## Actual output and audits

| Artifact | Location | Actual size |
| --- | --- | ---: |
| Application image | `target/packaging/image/NetPulse/` | 72,029,449 bytes, 233 files |
| MSI installer | `target/installer/NetPulse-1.0.0.msi` | **54,158,216 bytes** (about 51.65 MiB) |
| Build manifest | `target/packaging/package-manifest.json` | Version, JDK, dependency/module inventory and JAR hash |

Final MSI SHA256:
`1DE274255697C66BCFA960CFFD46D8EC6661E0F53634DDD2C57BF636A81F19B3`.

The MSI database was opened and inspected; it has ProductName NetPulse,
ProductVersion 1.0.0 and ProductCode
`{1B4FC6E6-2FD8-3A3D-95E1-63D236F97019}`. Installation exercised the actual package,
not just its image. Inspection confirmed:

- All four required FXML/CSS/PNG/SVG resources are inside the application JAR.
- No JUnit/Surefire/test helper classes or test dependencies are in the payload.
- No `speedtest.exe` is in the image/installer payload.
- Launcher configuration and shortcut target contain no development-only paths.
- Bundled JavaFX modules and native libraries are present.

[Artifact audit and hash](phase8-evidence/final-artifact-audit.json),
[MSI properties](phase8-evidence/final-msi-properties.json).

## Installation and uninstallation verification

The actual MSI was installed on this existing Windows 11 machine with:

```powershell
msiexec /i "target\installer\NetPulse-1.0.0.msi" /qn /norestart /l*v phase8-install-final.log
```

**Installation returned 0.** Both the per-user Start Menu and Desktop shortcuts
were created and pointed to the installed executable, not the development image.
The installed private JVM existed. Evidence:
[install result](phase8-evidence/final-install-attempt.json),
[shortcut metadata](phase8-evidence/final-shortcuts.json).

**Actual Start Menu and Desktop launch attempts failed.** Windows Application
Control rejected the unsigned NetPulse.exe. Code Integrity events 3077/3033 report
an Enterprise signing-level/policy violation. This is a host security restriction;
no security setting was disabled or bypassed and no code signing was implemented.
The same policy blocked launching the unpacked native image.

- [Start Menu launch failure](phase8-evidence/final-startmenu-launch.json).
- [Desktop launch failure](phase8-evidence/final-desktop-launch.json).
- [Code Integrity events](phase8-evidence/final-code-integrity.txt).

After diagnostics, only the product installed for this test was uninstalled:

```powershell
msiexec /x '{1B4FC6E6-2FD8-3A3D-95E1-63D236F97019}' /qn /norestart /l*v phase8-uninstall-final.log
```

**Uninstallation returned 0.** The install directory, both shortcuts and uninstall
registration were removed. The independent Winget Ookla CLI remained installed.
[Uninstall/removal checks](phase8-evidence/final-uninstall.json).

## Installed-payload diagnostics and Ookla

These checks are explicitly **not successful native/shortcut launch checks**.
To isolate payload correctness from the blocked generated EXE, the installed
application was separately tested using its bundled signed `runtime/bin/java.exe`,
with JAVA_HOME cleared, PATH reduced to Windows System32 and the working directory
set to the system temporary directory. No source/JDK/Maven directory was used.

The process loaded its JVM from the installed NetPulse runtime. The original
dashboard, new window icon, styles, live OSHI Wi-Fi measurements, rolling graph and
session totals rendered and updated. This demonstrates the installed payload works
with its private runtime; it does not establish that native shortcut launch works.

For stale inherited environments, the locator now falls back to Windows' current
saved machine/user PATH **only if the inherited PATH has no valid candidate**.
The read-only registry access uses JNA already supplied by OSHI, on the existing
CLI worker. It expands PATH variables and does not search hardcoded Winget/user
directories. The manual override still takes precedence. Existing executable and
official version-banner validation, errors, process execution and JSON parsing
remain intact.

In the installed-payload diagnostic, the independent official CLI was discovered
despite System32-only inherited PATH, passed banner validation and started with
`--format=json`. It was cancelled after discovery and reaped. The native file
chooser also successfully selected that independently installed executable. Closing
the diagnostic returned 0 with no owned CLI child remaining.

[Runtime/CLI/cleanup evidence](phase8-evidence/final-installed-payload.json).
Screenshots: [installed payload dashboard](phase8-evidence/final-installed-payload-dashboard.png),
[automatic discovery/running state](phase8-evidence/final-installed-payload-cli.png),
[manual fallback selection](phase8-evidence/final-installed-payload-manual.png).

**CLI discovery from a successful installed Start Menu launch remains unverified**
because that launch was blocked. The direct installed-payload diagnostic is not
presented as a substitute pass for that requirement. Ookla itself is not bundled,
downloaded, removed by uninstall or given automatic license-acceptance flags.

## Regression results and changed files

The final image build ran the complete Maven clean verification:
**227 tests, 0 failures, 0 errors, 0 skipped**. All original 224 tests remain.
Three added tests cover the verified standalone-packaging environment gap:
saved-PATH fallback, inherited/manual precedence without unnecessary registry reads,
and invalid/missing candidates with fallback.

Production changes are limited to `WindowsSearchPath`, the locator's lazy fallback,
and application icon loading/resources. Monitoring, graph, totals, benchmark
execution/parser/results and dashboard design were preserved. Build changes are
the installed-tool detector, icon renderer and two-stage packaging script.
The existing Maven dependency/plugin versions were not changed.

New/updated files: `scripts/get-wix.ps1`, `scripts/new-netpulse-icon.ps1`,
`scripts/package-windows.ps1`, `packaging/windows/NetPulse.ico`, the PNG/SVG icon
resources, `NetPulseApplication.java`, `SpeedTestExecutableLocator.java`,
`WindowsSearchPath.java`, locator regression tests, README and this report/evidence.
Generated installers/runtime images remain under the existing ignored target tree.

## Limitations and final assessment

- **MSI creation, actual installation and actual uninstallation passed. Native EXE,
  Start Menu and Desktop launch are blocked on this host. The package is not
  declared deployment-ready here.** Policy resolution needs an appropriate
  approved Windows environment/administrative decision; this phase does not change
  policy, add signing, rewrite shortcuts or implement an alternate native launcher.
- WiX emitted LGHT1105: full ICE validation could not run under this machine's
  policy. Its compiler/linker returned 0, and actual install/uninstall returned 0;
  full ICE validation is not claimed.
- **No clean Windows machine/VM testing was performed.** The existing host has
  development tools; the isolated-PATH/private-runtime test is not clean-machine
  certification. Testing native launch and discovery on an approved clean host
  remains required before declaring deployment verification complete.
- No code signing, auto-update, distribution infrastructure, new provider,
  database/backend/cloud service or further phase was started.

References: [JDK 25 packaging overview](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html),
[application image/runtime customization](https://docs.oracle.com/en/java/javase/25/jpackage/image-and-runtime-modifications.html),
[jpackage options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html).

**Stopped at Phase 8 with the above launch-verification limitation recorded.**
