# Phase 7 — Integration, reliability and readiness

Verified on 18 November 2026 on Windows 11 x64, Temurin Java 25.0.5,
JavaFX 25.0.1 and the existing Maven 3.9.16 wrapper. The single-module architecture,
pinned dependencies and Ookla-only benchmark provider are preserved.

## Inspected components and prior evidence

Read the architecture and Phase 1–4 records in README, the Phase 5 dashboard
report, Phase 6 graph/correlation report and PATH discovery prerequisite report.
Reviewed:

- `OshiNetworkStatsProvider`, `AdapterSelector`, `NetworkMonitorService` and their
  metadata filtering, scheduler, freshness guards and publication/shutdown rules.
- `NetworkSpeedCalculator`, immutable measurements/deltas, `SessionTrafficTracker`,
  `RollingTrafficWindow` and coherent `MonitoringDataSnapshot` ownership.
- `TrafficChartData`, `TrafficChartView`, `SpeedometerView` and dashboard presentation,
  including gap/boundary rendering, scales, coalesced UI refresh and result retention.
- `SpeedTestExecutableLocator`, `SpeedTestService`, parser/result/state models and
  `SpeedTestPeriodTracker`, including pipes, deadlines, cancellation, process cleanup,
  token ordering and terminal outcomes.
- `FxDashboardController`, FXML/CSS, `NetPulseApplication` and the two existing
  PowerShell launch/verification scripts.

No previous NetPulse implementation was inspected or imported.

## Verified defect and focused fix

**Early controller cleanup could fail before releasing its services.** A regression
constructed the controller and closed it before FXML initialization. Before the
fix it failed with `IllegalStateException: Toolkit not initialized` from
`Timeline.stop()`; cleanup exited before the service-close calls. An aborted FXML
load can also leave the injected gauge/chart fields absent.

The fix in `FxDashboardController.close()` skips stopping a never-started/stopped
refresh timer and closes UI components only if injected. Both services are then
closed normally. Fully initialized UI behavior and ownership are unchanged.
Two regressions cover close-before-load/idempotency and failed FXML initialization
with an already-running monitor; they verify services are closed/terminal.

[Original failing reproduction](phase7-evidence/startup-defect-reproduction.txt)
is retained separately from the passing final suite. Surefire now grants native
access to its unnamed test modules, matching the application's native-access
configuration and avoiding Java 25 warnings from the new FXML regression tests.

No monitoring, chart, calculation, CLI execution, parsing or result-handling defect
was reproduced in the remaining checks, so those production components were not
rewritten. README statements that still described gauges/charts as unimplemented
were corrected.

## Tests and results

Final command:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

**224 tests; 0 failures, 0 errors, 0 skipped.** Compilation and JAR verification
passed. All 220 existing tests remain. Four tests were added:

1. Controller cleanup before FXML initialization, including repeated close.
2. Cleanup after an actual failed FXML load with a running monitor.
3. **29,400 simulated one-second polls**, representing **8 hours 10 minutes**, over
   2,100 cycles of valid traffic, idle zero, counter reset, adapter switching,
   failed reads and disconnect/reconnect. Independent golden totals are **63,000
   downloaded bytes** and **113,400 uploaded bytes**. Every cycle checks exact
   totals, at-most-61 retained samples, valid graph timestamps and separated paths.
   This is accelerated deterministic verification, not eight hours of real time.
4. **24 sequential controlled benchmarks**: six successes, six nonzero failures,
   six malformed outputs and six cancellations. All **48 child processes**
   (preflight plus benchmark) were reaped, IDs/closed intervals stayed correct,
   one orchestration worker was reused and that worker terminated after close.

Existing tests also cover stale/blocked samples, sampling gaps, unavailable
measurements, overlapping/replayed intervals, exact BigInteger totals beyond long
range, immutable concurrent reads, rolling expiration, chart zero/gap/boundary
projection, PATH/missing/invalid executables, timeout, forced termination,
cancellation during startup/processing, pipe limits, terms prompts and late callbacks.

Expected synthetic listener/internal-failure warnings remain part of deliberate
failure tests. The final suite has no restricted-native-access or uninitialized
toolkit warnings. [Final clean verification log](phase7-evidence/clean-verify.txt)
records the complete run; the original is also in `phase7-build.log`.

## Actual Windows verification

### Prolonged production run and launcher

The existing `scripts/start-netpulse.ps1` launched the production entry point with
current Windows PATH, from a parent that still had the older inherited PATH. Its
actual JavaFX window ran for **658.47 seconds (10 minutes 58.47 seconds)**, with
continuous Intel Wi-Fi/OSHI monitoring, rolling chart updates and session totals.
The window was responding at each resource capture and accepted later UI actions.

The real benchmark below used default PATH discovery of the Winget CLI; no browse
or path override was needed. Process evidence confirmed the official executable
and `--format=json` as a direct child of NetPulse's Java PID 16096:
[observed process](phase7-evidence/real-cli-process.json).

After more than ten minutes, a deliberately invalid two-byte test executable was
selected through the existing file chooser. Windows rejected its launch; the
dashboard displayed the error while preserving the real successful benchmark.
No second real benchmark was run. The expired benchmark shading had left the
60-second chart while its successful results remained visible.

Normal window closure exited the application and Maven launcher with code 0,
restored the caller's original PATH and left no owned CLI process:
[shutdown evidence](phase7-evidence/production-shutdown.json).
A fresh launch of the final clean build was also opened and closed normally:
[final-build window](phase7-evidence/final-build-window.png) and
[launch/close evidence](phase7-evidence/final-build-launch.json).

### Real Ookla benchmark

Exactly one real benchmark completed in Phase 7 using the existing Winget-installed
Ookla 1.2.0.84. Values below are **as displayed by the GUI**, rounded for presentation;
no raw byte-rate precision is claimed from the screenshot.

| Field | Observed result |
| --- | --- |
| Download | 33.77 Mbps |
| Upload | 2.84 Mbps |
| Idle ping | 28.18 ms |
| Jitter | 21.15 ms |
| Packet loss | 0% |
| Server | V FastNet Pvt Ltd · Bengaluru · India |
| ISP | Airtel |
| Completed | 18 Nov 2026, 02:47:39 IST |

Monitoring continued throughout: the actual OSHI graph showed the traffic during
the benchmark and exact-counter session totals continued increasing. Official
benchmark values remained in the separate result panel. No license flags were
passed and no agreements were accepted by NetPulse.

- [Real benchmark and live OSHI graph](phase7-evidence/production-benchmark.png).
- [Same successful result retained after a later failed launch](phase7-evidence/retained-real-result-after-failure.png).

### Resizing, rendering and failure paths

The existing opt-in Windows harness loaded the real controller/FXML at native
**125%** and forced JavaFX **100%** output. Both runs passed:

- Wide two-column layout, independent gauges, minimum 640 × 520 layout, scrolling
  to a readable graph and retained benchmark metadata.
- Deterministic full-window renderer with 57 available points in four paths,
  including a real zero baseline, unavailable gaps and an adapter switch. Pixel
  checks verified no transfer stroke crossed the unavailable interval.
- Start reservation, progress/cancel states, active and completed shading,
  subsequent invalid output, cancellation and result retention.
- Counter/history updates while a controlled CLI child was running; unavailable,
  failed and blocked/stale monitor reads followed by recovery.
- Completed-period and point expiration without new samples.

Fresh run logs: [125%](phase7-evidence/ui-125.txt),
[100% rendering](phase7-evidence/ui-100.txt).

Physical network disconnection was not induced. Disconnect/reconnect, adapter
switching and resets used controlled providers and the accelerated test; native
monitoring was observed on Wi-Fi. The desktop setting stayed at 125%, with 100%
tested through the supported application rendering override.

## Resources and lifecycle

Measurements from the production JVM, including its real benchmark:

| Capture | JVM elapsed | Working set | Private memory | OS threads | Heap used after explicit GC |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | about 36 s | 368.14 MiB | 410.61 MiB | 72 | 14.78 MiB |
| Midpoint | 435.26 s | 391.64 MiB | 431.09 MiB | 66 | 15.84 MiB |
| Final resource sample | 563.17 s | 355.07 MiB | 395.36 MiB | 65 | 18.40 MiB |

Committed Java heap stayed at **54 MiB**. Java heap reservation is virtual address
space and is not reported as consumed RAM. OS memory figures were captured before
the explicit GC; heap samples were captured after it. Initialization, Jackson
loading, caches and allocations between diagnostic calls affect the samples.
No unbounded memory/thread growth was observed over this limited run. Native/JVM
overhead remains material: the total process working set was about 355–392 MiB.

At the final resource sample, cumulative CPU was 110.72 seconds over 563.17 wall
seconds on 16 logical processors, about **1.23% of total machine capacity averaged
over the run**. This includes startup and benchmark processing and is not a
dedicated performance benchmark. No UI freeze was observed.

Resource evidence:

- [Baseline](phase7-evidence/resource-baseline.json),
  [midpoint](phase7-evidence/resource-midpoint.json),
  [final sample](phase7-evidence/resource-final.json).
- [Baseline heap](phase7-evidence/heap-baseline.txt),
  [midpoint heap](phase7-evidence/heap-midpoint.txt),
  [final heap](phase7-evidence/heap-final.txt).
- [Final thread dump](phase7-evidence/threads-final.txt): one monitor worker and
  the expected reusable idle orchestration/two-reader pool after the benchmark,
  rather than one new pool per test.

The Windows harness now checks shutdown **before calling Platform.exit()**. It
waits for all `NetPulse-network-monitor` and `NetPulse-speedtest*` threads to end,
checks no owned child is alive or period active, and asserts the dashboard timer
and both gauge timelines are stopped. Both rendering runs reported:

```text
OWNED WORKERS TERMINATED; UI TIMELINES STOPPED BEFORE PLATFORM EXIT
```

Existing scheduler tests verify cancellation/shutdown and discarding late reads.
The chart owns no timer; immutable publications and all history/period/frame
collections retain their existing hard memory caps. No recurring speed-test task,
server or persistent data store exists.

## Files changed

- `src/main/java/com/netpulse/ui/FxDashboardController.java`: partial-startup cleanup.
- `pom.xml`: native access for the test JVM; dependency/plugin versions unchanged.
- New `src/test/java/com/netpulse/ui/FxDashboardControllerTest.java`.
- Extended `NetworkMonitorServiceTest` and `SpeedTestServiceTest` reliability cases.
- Extended `speedtest/DashboardUiCheck.java` with pre-exit worker/timer assertions.
- README, this report and `docs/phase7-evidence/`.

## Commands

```powershell
.\scripts\start-netpulse.ps1
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=FxDashboardControllerTest' test
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
.\scripts\verify-dashboard.ps1
.\scripts\verify-dashboard.ps1 -UiScale 1.0
```

`jcmd <pid> GC.run`, `GC.heap_info` and `Thread.print` captured the resource
measurements. Windows process inspection and native UI interactions were
verification tools, not additions to application monitoring code.

## Known limitations and readiness assessment

**Ready for the intended college-project use on the verified Windows 11/Java 25
configuration, with no known remaining functional blocker in the exercised scope.**
This is a roughly eleven-minute native run plus accelerated deterministic and
process/UI checks, not an overnight or multi-day production certification.
Physical Ethernet switching, cable removal, Wi-Fi toggling, suspend/resume and
other network drivers/hardware were not exercised live. A native OSHI call that
ignores interruption can finish after close; its daemon worker cannot keep the
JVM alive and late results are rejected. OSHI counters include all adapter traffic,
and CLI shading cannot attribute individual bytes or precise transfer phases.

The separately installed CLI and its terms remain required. Direct launches from
a stale IDE need that IDE restarted or the existing PATH-refreshing launcher.
Gauge/chart maxima remain intentionally stable for the session; a past large
spike may leave later small readings near the baseline. No installer, packaging,
distribution, new speed-test provider or additional phase has been started.

**Stopped after Phase 7. Further work requires explicit approval.**
