# NetPulse portable ZIP verification

The portable release was created from the existing jpackage image. No application
code, dependency, runtime module, dashboard or benchmark behavior changed.

## Release artifacts

Both formats are kept together under the ignored release output directory:

| Artifact | Exact path | Size |
| --- | --- | ---: |
| Portable ZIP | `C:\Users\abhin\projects\NetPulse\target\release\NetPulse-1.0.0-Portable.zip` | 52,791,546 bytes (50.35 MiB) |
| MSI copy | `C:\Users\abhin\projects\NetPulse\target\release\NetPulse-1.0.0.msi` | 54,158,216 bytes (51.65 MiB) |

The original MSI remains at
`C:\Users\abhin\projects\NetPulse\target\installer\NetPulse-1.0.0.msi`.
Its SHA256 and the release copy both match the Phase 8 MSI:
`1DE274255697C66BCFA960CFFD46D8EC6661E0F53634DDD2C57BF636A81F19B3`.

ZIP SHA256:
`28926A872A7BA9B18DFA9137227271F657D8BF59288648B6B0A88E7D428B1A5F`.

`target/release/portable-manifest.json` records the version, artifact hashes and
the complete image-file hash inventory. As with the existing MSI/image outputs,
Maven clean removes generated target output; copy releases elsewhere to retain
them across a clean build. Generated ZIPs/MSIs/runtime images are not source files.

## Package and launcher

The ZIP has one top-level folder:

```text
NetPulse-1.0.0-Portable/
  Launch-NetPulse.cmd
  README.txt
  NetPulse/                 Complete existing jpackage image
    NetPulse.exe
    app/
    runtime/
```

The script copies the entire image, including hidden metadata and directories,
then uses .NET ZipFile rather than Compress-Archive so hidden files are retained.
Every staged image file is hash-checked against its source before archiving.
The ZIP contains the **233 image files plus the launcher and README**. The bundled
Java/JavaFX runtime and application dependencies retain their original structure.
There is no `speedtest.exe` in the archive.

`Launch-NetPulse.cmd` resolves the image using `%~dp0NetPulse`, independent of the
working directory. It checks the native application, launcher configuration,
main JAR, JVM DLL and runtime modules before launching. Missing files produce
actionable extraction errors and a nonzero exit. It launches only NetPulse.exe;
there is no alternate Java command, policy modification, file unblocking,
SmartScreen suppression, or code-signing replacement.

README.txt explains Extract All, intact folders, launching, normal closure,
independent official Ookla installation, PATH/manual discovery, terms and security
warnings for unsigned binaries. Monitoring is independent of Ookla. No separately
installed Java, Maven, WiX or development checkout is required by the package.

## Build command and changed files

From the project directory, with the existing image and MSI already built:

```powershell
.\scripts\package-portable.ps1
```

This command does not rebuild or clean the application/MSI. It creates fresh
staging output, replaces the generated versioned ZIP, and retains an identical
MSI copy in the release directory. An optional `-OutputDirectory` is supported.

Added:

- `scripts/package-portable.ps1`.
- `packaging/windows/Launch-NetPulse.cmd` (build template).
- `packaging/windows/README-Portable.txt` (build template).
- This report and `docs/portable-evidence/`.

README was updated. No Java code or Maven configuration changed; the existing
227-test Phase 8 result was not rerun for this ZIP-only task. Verification here
was performed on the actual ZIP, extracted image and launcher.

## Extraction and launcher tests

The archive was extracted to a new directory under
`target/portable-verification/<unique-id>/Extracted copy & spaces/`, separate from
the source image and containing both spaces and an ampersand.

- All **233 extracted image files matched their source SHA256 hashes**.
- All **35 source directories** were retained.
- Archive content count was **235 files**, with no bundled Ookla executable.
- The original MSI and release MSI copy remained byte-identical.
- The launcher was run with working directory `C:\Windows\System32`, JAVA_HOME
  cleared and PATH containing only Windows System32. Its checks resolved the
  relocated files correctly rather than looking in the working/development directory.
- Missing executable, missing application configuration and missing bundled runtime
  scenarios each displayed the expected useful error and returned exit code 2.

Evidence: [extraction audit](portable-evidence/extraction-audit.json),
[launcher tests](portable-evidence/launcher-tests.json),
[missing-configuration check](portable-evidence/missing-configuration.json).

## Native launch and required limitation

The complete extracted launcher attempted to execute the relocated NetPulse.exe.
**Windows Device Guard/Application Control blocked the unsigned executable.**
The launch attempt returned nonzero, printed the Windows policy error and the
wrapper's explanatory message. No portable GUI process was observed.

[Code Integrity evidence](portable-evidence/code-integrity.txt) and the full
stdout/stderr in the launcher-test evidence record this failure. No security
setting was changed, warning suppressed, or alternate launch method attempted.

Consequently, **monitoring, graph updates and application shutdown could not be
verified through the portable launcher on this host**. The earlier Phase 8 payload
diagnostics are not counted as a portable-launch pass. The wrapper failure path
terminated; that does not establish application shutdown because the application
did not start. Verification on a Windows environment that permits this unsigned
binary remains necessary. No clean-machine/VM compatibility is claimed.

The ZIP build, complete extraction/hash verification and missing-file handling
passed. Native launch and the subsequent GUI checks remain blocked by host policy.
The MSI is preserved alongside the ZIP. Work stops after this portable task.
