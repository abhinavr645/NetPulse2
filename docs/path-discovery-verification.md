# Default Ookla PATH discovery verification

This task changes the launch environment, not Phase 6 functionality or Ookla
execution. The existing locator already defaults to the inherited Windows PATH,
and the default application constructs `SpeedTestService()` without an override.
Its existing service validates the official version banner before `--format=json`.

## Cause and fix

Windows' saved user PATH already contained the Winget-installed CLI directory.
The running editor/tool session inherited an older PATH without that entry. This
explains why `speedtest` worked in a fresh CMD but was not discoverable by a Java
process launched from this older session.

`scripts/start-netpulse.ps1` reads the current saved machine and user PATH, expands
environment variables, removes duplicate entries without regard to case, then
appends process-only entries so inherited development tools remain available.
It launches the normal Maven/JavaFX entry point with that child environment and
restores the caller's original PATH in `finally`. Saved Windows environment
variables are never modified. No Winget directory, user name or executable
location is hardcoded in the launcher or application.

Launch from PowerShell:

```powershell
.\scripts\start-netpulse.ps1
```

In a fresh terminal, `.\mvnw.cmd javafx:run` also continues to work directly.
Launching directly from a stale IDE environment requires restarting the IDE after
the installation, or using the recommended launcher. Existing JVM environment
variables do not automatically change when Windows' saved PATH changes.

## Discovery and fallback

- The default locator searches absolute directories on PATH, in order, for
  `speedtest.exe`. It supports quoted directories and directories with spaces.
- Malformed/empty/relative entries are skipped. It checks that a candidate is a
  readable Windows executable and resolves its real path. Invalid file candidates
  do not prevent searching later PATH entries.
- The unchanged SpeedTestService runs `--version` and requires the official Ookla
  banner before starting its existing JSON command.
- If no candidate exists, the dashboard shows its existing actionable missing-CLI
  message. Invalid files or incorrect version banners produce existing errors.
- **Choose CLI…** remains the explicit manual fallback. A user-selected path takes
  precedence for that session; an invalid explicit selection is reported rather
  than silently running a different executable.

There is no automatic license acceptance, executable download, new provider,
dependency, benchmark behavior, JSON/result change, or Phase 6 modification.

## Files changed

- Added `scripts/start-netpulse.ps1`.
- Added test helper `src/test/java/com/netpulse/speedtest/PathDiscoveryFixture.java`.
- Extended `src/test/java/com/netpulse/speedtest/SpeedTestExecutableLocatorTest.java`.
- Updated README launch instructions.
- Added this report, process evidence and two Windows screenshots.

No production Java, FXML, CSS, Maven configuration, existing service lifecycle,
traffic chart, shading, parser or result code was changed.

## Tests

Command:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Result: **220 tests, 0 failures, 0 errors, 0 skipped** (`path-discovery-build.log`).
All 217 existing tests remain, with three new discovery regressions:

1. A separate JVM with its own controlled PATH exercises the **public default
   constructor**, finding an executable in a directory with spaces without an
   override or custom search-path constructor.
2. PATH ordering is respected, including an invalid candidate followed by valid
   executables.
3. An invalid PATH executable is reported, and an explicit valid manual fallback
   succeeds.

Existing tests cover missing commands, malformed/empty PATH entries, invalid
files, explicit-override precedence, incorrect version banners, valid official
identity, JSON schema/units/optional fields, cancellation, result handling, graph
correlation, monitoring and shutdown. No test requires a real internet benchmark.

## Actual Windows verification

- The installed Winget binary reported **Speedtest by Ookla 1.2.0.84
  (ea6b6773cf), Windows AMD64**, matching the supported existing CLI/schema.
- NetPulse was launched through `start-netpulse.ps1` from the stale environment.
  Its actual JavaFX window opened and displayed live OSHI monitoring/graph data.
- **Start Speed Test** was clicked without opening **Choose CLI…** or setting an
  executable override. The existing version-banner check succeeded and the UI
  reached **Ookla CLI is running; progress is indeterminate**.
- Windows process inspection confirmed that NetPulse's own Java PID **25500**
  spawned CLI PID **23996**, from the Winget package, with `--format=json`.
  The parent PID matched the application's window process. The runtime-observed
  path and command are recorded in
  [process evidence](path-discovery-process-evidence.json); they are evidence,
  not application configuration.
- The discovery check was cancelled after the benchmark process was observed.
  The UI showed **Speed test cancelled**, and no owned CLI child remained. No
  license flags were passed or terms accepted. No completed benchmark result was
  produced in this check; JSON compatibility is covered by the existing parser
  regression suite and the prior verified schema.
- Normal window closure exited the application/Maven launcher with code 0. The
  wrapper check confirmed **Caller PATH restored after launch**. The launch log
  contained no runtime or CSS errors.

Screenshots: [automatic PATH discovery running](screenshots/path-discovery/automatic-path-running.png)
and [cancelled with PATH still selected](screenshots/path-discovery/automatic-path-cancelled.png).

## Limitations and stop point

No unresolved discovery issue remains with the recommended launcher. A direct
launch from an already-running, stale IDE/JVM still inherits that older PATH;
restart it or use the launcher. Manual fallback remains available if PATH is
absent or the candidate fails validation. The installed CLI and its terms remain
the user's responsibility.

Only this preparation task is complete. No further phase has started.
