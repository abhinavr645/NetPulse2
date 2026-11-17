# Phase 6 — Live traffic graph and CLI correlation

Phase 6 integrates the existing rolling history with a reusable JavaFX graph and
records the actual benchmark process lifetime. Monitoring, selection, byte-counter
calculations, exact session totals and benchmark parsing retain their established
implementations. Dependency versions and Maven configuration are unchanged.

## Classes and changed files

New production classes:

- `ui/TrafficChartView.java`: reusable JavaFX Canvas chart, two paths/point sets,
  time/Mbps axes, legend, empty state and execution-period shading.
- `ui/TrafficChartData.java`: JavaFX-free immutable frame projection from actual
  history samples and process periods; explicit segment boundaries and clipping.
- `model/SpeedTestPeriod.java`: immutable start/end monotonic and wall timestamps,
  correlation ID, service test ID, confirmed-termination flag and terminal outcome.
- `speedtest/SpeedTestPeriodTracker.java`: bounded immutable period publications,
  monotonic expiration, duplicate/obsolete token rejection and terminal closure.

Modified production files:

- `speedtest/SpeedTestService.java`: records successful benchmark process start,
  observed exit/reaping and terminal outcome. `periodSnapshot(now)` exposes the
  detached periods. `withExecutable(path)` preserves visible correlation history.
- `ui/FxDashboardController.java`: supplies monitoring history and period snapshots
  to the chart on the existing JavaFX refresh timeline, and closes the chart.
- `resources/fxml/FxDashboardView.fxml`: replaces the Phase 5 placeholder with the
  graph, positioned below the gauges so it is visible beside test controls.
- `resources/css/styles.css`: chart card style replaces the placeholder style.

New unit tests: `ui/TrafficChartDataTest` and `speedtest/SpeedTestPeriodTrackerTest`.
`SpeedTestServiceTest` additionally verifies real controlled process intervals and
benchmark-start failure after successful preflight. The opt-in `DashboardUiCheck`,
`ControlledCliFixture` and `scripts/verify-dashboard.ps1` now cover graph rendering
and correlation. README, this report and `docs/screenshots/phase6/` are updated.
All Java paths above are relative to `src/main/java/com/netpulse/` or, for tests,
`src/test/java/com/netpulse/`.

## Rendering, timestamps and gaps

Canvas was chosen to make path breaks explicit without chart interpolation or
animation creating misleading bridges. Download is teal/solid; upload is blue/
dashed. Both have actual-sample dots, including zero readings at the baseline.
There are no fabricated intermediate measurements, smoothed rates or extrapolated
values extending the last sample to the present.

`MonitoringDataSnapshot.history()` supplies the existing RollingTrafficWindow
samples. Horizontal position is based on each sample's `sampledAtNanos`, not its
list index or its later publication time. The plot covers now minus 60 seconds
through now. The horizontal axis displays local wall-clock times derived from
the current paired clock anchor; wall-clock changes cannot change sample ages,
process durations or ordering.

Unavailable samples break both paths and are not plotted as zeros. Explicit
adapter boundaries, changed adapter identity, missing sequence numbers,
non-increasing timestamps and sampling gaps over three seconds also break paths.
Empty history shows a waiting message. A blocked monitor leaves a blank trailing
region after its last valid point; no stale value is extended into that region.
Expiration continues when no new samples arrive.

## Y-axis behavior

Both download and upload determine a shared scale. The existing JavaFX-free
GaugeScale supplies grow-only 1/2/5 maxima, starting at 1 Mbps; the axis has four
equal divisions with exact readable tick labels. A higher value expands the scale
immediately. Ordinary fluctuations, unavailable data, expiration and idle periods
do not shrink or repeatedly resize it. The maximum is retained for this chart's
lifetime, so an old large spike can leave later small traffic near the baseline;
this is intentional scale stability rather than a misleading automatic zoom.

## Process intervals and shading

A period begins only after ProcessBuilder successfully returns the official
`--format=json` benchmark process. Its full runtime includes the CLI's internal
preparation, server selection, transfers and result production. The separate
`--version` validation and Java's post-exit JSON parsing are outside that process
lifetime. Neither a Start button click nor PREPARING state fabricates a process
start. Missing/invalid executables and failed process startup create no interval.

Successful `waitFor` records observed termination before stdout/stderr processing.
On cancellation, timeout or error, the cleanup path records exit after reaping.
The terminal outcome attaches independently, preserving the already observed
process end. Completed, failed, timed-out and cancelled processes all close their
intervals. In the existing exceptional cleanup-failure path, tracking still closes
and explicitly marks termination **unconfirmed**, rather than claiming an exit.

Each reservation receives a tracker token distinct from the service's test ID.
The tracker is shared when an idle dashboard changes executable, retaining visible
completed periods even when service test IDs restart. Obsolete tokens, duplicate
events and backwards monotonic endpoints cannot rewrite newer intervals.

Running periods are amber/translucent and labelled **SPEED TEST RUNNING**. They
extend from start to current time, clipped to the visible window. Closed periods
are muted blue-gray and end at the recorded process termination, remaining visible
while their endpoint intersects the window. A subpixel-duration interval has a
one-pixel marker. Shading is drawn behind grid and throughput lines. The legend
labels these lines **OSHI adapter traffic**, and the separate benchmark panel
retains official-result semantics; no benchmark numbers enter the graph.

## Thread safety, bounds and shutdown

The controller reuses its 200 ms JavaFX timer. It reads immutable in-memory history
and period snapshots, naturally coalescing faster publications; there is no queue
of per-sample UI callbacks. Chart calculations/rendering happen on JavaFX and
perform no network, file, process or JSON operations. Process start/end hooks run
on existing speed-test workers. Tracker synchronization covers short in-memory
updates only; no process operation runs under its lock.

History/frame input is capped at 128 samples and process history at 128 periods,
with time-based expiration. The view has one Canvas and no timer/listener of its
own. Closing the dashboard stops its timer and gauge animations, closes the chart,
stops monitoring and closes/reaps the active CLI through the existing service.

## Automated verification

`clean verify`: **217 tests, zero failures, zero errors, zero skipped**. All 194
Phase 1–5 tests remain. The 23 added tests cover actual timestamp positioning,
counter time versus publication time, valid zero, empty history, unavailable gaps,
adapter boundaries, missing samples, exact 60-second expiration, upload-aware scale
expansion/stability, immutable/bounded frames, active/completed interval clipping,
all terminal outcomes, startup failure, obsolete/duplicate callbacks, end-based
expiration, memory bounds and unconfirmed-termination fallback.

Existing service tests now also assert period creation/closure for successful
execution, invalid JSON, nonzero exit, terms prompts, output limits, timeout and
cancellation; preflight-only paths create no period. A new regression verifies
successful `--version` followed by failed benchmark startup creates no interval.
Executable replacement retains completed periods. Existing core, monitor, total,
parser, result-retention and process-shutdown tests continue to pass.

## Actual Windows verification

The opt-in desktop harness opens actual JavaFX windows and loads the production
FXML/controller. At native Windows **125%** and forced JavaFX **100%** rendering:

- A deterministic 60-second fixture passed through RollingTrafficWindow and
  rendered 57 actual available points in four separated segments, with real zero,
  explicit unavailable gaps and an adapter switch. Pixel checks verified no
  throughput stroke crosses an unavailable gap and idle zero draws at the baseline.
- Its before/during/after captures directly show unshaded, active amber and closed
  muted shading. Advancing time with unchanged input removed expired data/bands.
- The dashboard continued receiving samples during a controlled child process.
  Its active band closed on success, invalid JSON and cancellation. Latest results,
  gauges, button states and exact totals remained functional.
- The 640 × 520 minimum layout stacked columns and scrolled to a readable graph.
- Closing with an active controlled child stopped monitoring, reaped owned children
  and left no active period. Both harness invocations exited with code 0.

A separate `-LiveMonitor` run used actual OSHI Wi-Fi measurements with controlled
CLI processes. It verified live graph updates before/during/after the child process,
continued sampling, nondecreasing exact totals, separate synthetic benchmark
results, completed shading and cancellation. This run performed **no internet
speed benchmark**. Repeating a real Ookla benchmark was unnecessary; Phase 5's
real integration remains covered by the existing unchanged parser/CLI regressions.

The production `javafx:run` window was separately inspected with actual OSHI data
and closed normally. The global desktop remained at 125%; no physical 100% monitor
was available, so 100% verification used `glass.win.uiScale=1.0`.

## Commands and screenshots

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
.\mvnw.cmd --batch-mode --no-transfer-progress javafx:run
.\scripts\verify-dashboard.ps1
.\scripts\verify-dashboard.ps1 -UiScale 1.0
.\scripts\verify-dashboard.ps1 -LiveMonitor
```

The default and LiveMonitor checks use synthetic CLI children and never run an
internet speed test. The existing `-RealCli 'path\speedtest.exe'` option explicitly
requests one real benchmark and was not used in Phase 6.

Screenshots from actual rendered Windows JavaFX views (fixture values are synthetic):

- Full-window fixture: [before](screenshots/phase6/phase6-fixture-before-125.png),
  [during](screenshots/phase6/phase6-fixture-during-125.png),
  [after](screenshots/phase6/phase6-fixture-after-125.png).
- Dashboard with controlled monitoring/CLI: [before](screenshots/phase6/phase6-before-125.png),
  [during](screenshots/phase6/phase6-during-125.png),
  [after](screenshots/phase6/phase6-after-125.png).
- Terminal cases: [invalid output](screenshots/phase6/phase6-failed-125.png),
  [cancelled](screenshots/phase6/phase6-cancelled-125.png).
- Actual OSHI / controlled CLI: [before](screenshots/phase6/phase6-live-before-125.png),
  [during](screenshots/phase6/phase6-live-during-125.png),
  [after](screenshots/phase6/phase6-live-after-125.png).
- Minimum window graph: [125%](screenshots/phase6/phase6-minimum-graph-125.png),
  [100% rendering](screenshots/phase6/phase6-minimum-graph-100.png).
- [Full fixture at 100% rendering](screenshots/phase6/phase6-fixture-during-100.png).
- [Production window](screenshots/phase6/phase6-production-125.png).

## Remaining scope and limitations

No known Phase 6 functional issue remains. Process timestamps are observations
immediately after successful launch and confirmed wait/reaping, rather than native
kernel-event tracing. OSHI is adapter traffic, including other applications; a
shaded interval correlates that traffic with the entire CLI execution and cannot
attribute every byte to Ookla or identify precise transfer phases.

No persistence, export, recurring tests, additional monitoring or distribution
packaging was added. **Stopped after Phase 6; Phase 7 requires explicit approval.**
