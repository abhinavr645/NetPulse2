# Phase 5 — JavaFX dashboard

The dashboard integrates the approved Phase 1–4 services. No monitoring,
counter-calculation, session-tracking, history, CLI execution, or JSON-parsing
service was rewritten. No dependency versions changed.

## Components and files

Production additions:

- `src/main/java/com/netpulse/ui/SpeedometerView.java`: native JavaFX arcs and
  labels, one reusable easing timeline per gauge, accessible reading text.
- `src/main/java/com/netpulse/ui/GaugeScale.java`: independent, grow-only 1/2/5
  scale, starting at 100 Mbps for each gauge.
- `src/main/java/com/netpulse/ui/DashboardPresentation.java`: JavaFX-independent
  adapter/status presentation and benchmark lifecycle/result retention.
- `src/main/java/com/netpulse/util/SpeedFormatter.java`: decimal Mbps, MB/s,
  exact-BigInteger-to-MB/GB formatting, and explicit unavailable formatting.

Modified production files:

- `ui/NetPulseApplication.java`: constructs services/controller, injects the
  controller through FXMLLoader, starts monitoring, owns clean startup/shutdown.
- `ui/FxDashboardController.java`: binds published state to dashboard controls,
  handles Start/Cancel/Choose CLI, and releases UI/service resources.
- `resources/fxml/FxDashboardView.fxml`: header, independent download/upload
  cards, session totals, adapter details, benchmark controls/results, and the
  **Traffic History / Coming in Phase 6** placeholder.
- `resources/css/styles.css`: dark theme, teal/blue direction accents, readable
  hierarchy, keyboard focus, status/error colors, and responsive cards.
- `util/package-info.java`: updated package description.

Verification/documentation additions: `DashboardPresentationTest`, `GaugeScaleTest`,
`SpeedFormatterTest`, `speedtest/DashboardUiCheck`, `scripts/verify-dashboard.ps1`,
this report and screenshots. The README now describes the functional dashboard.

## Data flow and unavailable readings

The monitor continues sampling on its scheduled worker approximately every second.
One JavaFX timeline polls its immutable published snapshots every 200 ms. These
reads do not enumerate adapters, invoke OSHI, run processes, or parse JSON. There
is no per-sample runLater queue: multiple publications naturally coalesce into
the newest visible state. The freshness-aware `latestSnapshot()` also exposes
stalled monitoring as unavailable even if its worker is blocked.

The two gauges display the actual unrounded measurement through formatting;
only their arc fraction is interpolated over 200 ms with EASE_BOTH. They reuse
their timelines and skip unchanged values. Scales expand independently to a
readable 1/2/5 maximum and do not shrink during ordinary fluctuations.

Valid unchanged counters display **0 Mbps / 0 MB/s**. Absent measurements display
**—**, immediately clear the arc, and give a meaningful baseline/reset/failure/
disconnection/stale reason. Tiny positive traffic displays **<0.01**, preserving
the distinction from true idle. Adapter information is replaced on each refresh;
unavailable identities do not retain the previous interface or MAC address.
Physical certainty is explicitly qualified, and link status is separate from
verified internet connectivity.

## Session totals

The controller reads `MonitoringDataSnapshot.sessionTotals()` from the existing
session tracker. Its validated BigInteger byte totals are formatted with decimal
MB/GB using BigDecimal. The UI does not accumulate rates or rounded values.
Refresh, adapter changes and unavailable samples cannot reset or corrupt totals.
Totals reset with the application's monitoring session.

## Ookla integration

Start/Cancel signal the existing asynchronous service. Start and executable
selection disable immediately on reservation; Cancel becomes available, with
an indeterminate indicator. There is no invented percentage. Start stays disabled
through cancellation/process cleanup. Missing CLI, required terms and other
service errors are visible and actionable. Choosing an executable replaces the
idle service and validates the selected file on its worker at the next request.
The executable choice is in memory only.

The latest successful result remains visible when a subsequent test fails,
times out, is cancelled, or a different executable is selected. Results show
benchmark Mbps, **idle** ping, jitter, optional loss/server/ISP and local completion
time. Missing optional fields remain **—**. Loaded latency is not substituted for
idle ping. Presentation rejects old test IDs, backwards phases and terminal-state
resurrection. Selecting a new service resets the test-ID namespace while retaining
the successful result. Production UI reads current service state rather than
attaching asynchronous completion callbacks to controls.

The CLI remains separately installed. No license flags, automatic acceptance,
CLI downloads, redistribution, persistence or recurring tests were introduced.

## Threading and shutdown

Controller/control mutations, the polling timeline and both arc timelines run
only on JavaFX. Monitoring, CLI discovery, executable validation, ProcessBuilder,
pipe reads, parsing, cancellation and reaping stay on the existing service workers.
Constructor injection uses the FXMLLoader controller factory without a framework.

Window closure stops the polling/arc animations and closes both services.
Closing is idempotent and does not wait on the JavaFX thread. The existing active
CLI orchestration worker keeps cleanup alive until owned processes are reaped.
FXML startup failure also closes the constructed services.

## Automated and desktop verification

`clean verify`: **194 tests, 0 failures, 0 errors, 0 skipped**. All 171 existing
tests remain, with 23 new tests covering decimal units, idle/unavailable values,
BigInteger totals beyond long range, scale stability/independence, adapter changes,
missing MAC/uncertain identification, all unavailable reasons, request reservations,
cancel/cleanup button state, retained success, missing CLI/terms messages and stale
test callbacks. Existing OSHI/core/CLI tests provide Phase 1–4 regressions.

The separate opt-in desktop harness loads the real FXML/controller and opens an
actual Windows JavaFX window. Its default mode uses controlled byte counters and
real synthetic child processes, without network speed tests. It checks rendered
gauge accessibility values and controls for:

- Live values, valid idle zero, no eligible adapter and preserved exact totals.
- Adapter change, failed reads, blocked-read staleness and recovery.
- Successful benchmark Mbps, idle latency rather than loaded latency, jitter,
  optional metadata and completion time.
- Subsequent invalid output and cancellation with last-success retention.
- Immediate Start disable, active Cancel, two-column wide layout, stacked narrow
  layout and scrolling at the 640 × 520 minimum.
- Closing with an active controlled CLI; no owned child survives cleanup.

Windows 11 native **125%** output and forced JavaFX **100%** rendering were checked.
The desktop's global display setting was left at 125%; a separate physical
100%-configured monitor was not available. Screenshot inspection caught fractional
pixel rounding in FlowPane widths; widths now leave rounding slack at both levels.

The production entry point was also launched with `javafx:run`, independently of
the harness. Its native window was visible and responding at 125% scaling. A
thread dump confirmed separate JavaFX and `NetPulse-network-monitor` threads and
no idle speed-test worker. Clicking Start with no CLI on PATH displayed the
actionable missing-installation message. The native **Choose CLI…** dialog opened,
accepted the existing official executable and updated the selected-file label;
this selection did not start another benchmark. Closing the normal window exited
both the application and Maven launch command with code 0, without runtime/CSS
errors in the launch log.

One real benchmark was run through this dashboard using the existing official
Ookla CLI (1.2.0.84), with real OSHI Wi-Fi sampling continuing independently:

| Field | Actual value |
| --- | --- |
| Download | 28.842368 Mbps |
| Upload | 0.605488 Mbps |
| Idle ping | 74.905 ms |
| Jitter | 11.858 ms |
| Packet loss | Absent, displayed as — |
| Server | I-ON · Bengaluru · India |
| ISP | Airtel |

This is a point-in-time internet benchmark, not a claim about connection capacity.
No terms were accepted by NetPulse. Physical network disconnection was not induced;
unavailable/idle/failure/stale scenarios used controlled fixtures. GUI smoke checks
remain opt-in so normal JUnit runs do not require a desktop or internet benchmark.

## Commands and screenshots

From Windows PowerShell in the project directory:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
.\mvnw.cmd --batch-mode --no-transfer-progress javafx:run
.\scripts\verify-dashboard.ps1
.\scripts\verify-dashboard.ps1 -UiScale 1.0
```

The real-CLI desktop check explicitly starts **one real benchmark**; provide the
path to your separately installed official executable:

```powershell
.\scripts\verify-dashboard.ps1 -RealCli 'C:\Users\abhin\Downloads\ookla-speedtest-1.2.0-win64\speedtest.exe'
```

The harness writes scene captures of its actually rendered Windows UI to
`target/dashboard-screenshots/`. Representative captures are preserved below;
synthetic values are labelled as such:

- [Real benchmark result, native 125% rendering](screenshots/phase5-real-result-125.png).
- [Production window with title bar, native desktop capture](screenshots/phase5-production-window-125.png).
- [Missing CLI message](screenshots/phase5-missing-cli-125.png) and
  [successful native file-dialog selection](screenshots/phase5-cli-selected-125.png).
- [Real benchmark running](screenshots/phase5-real-running-125.png).
- [Controlled idle, native 125% rendering](screenshots/phase5-idle-125.png).
- [Controlled unavailable adapter](screenshots/phase5-unavailable-125.png).
- [Controlled result and cancellation](screenshots/phase5-cancelled-125.png).
- [Minimum window, top](screenshots/phase5-minimum-125.png) and
  [bottom after scrolling](screenshots/phase5-minimum-bottom-125.png).
- [Controlled dashboard, forced 100% rendering](screenshots/phase5-idle-100.png).

## Remaining scope

No known Phase 5 functional issue remains. CLI installation/terms remain the
user's responsibility; selections and successful results are intentionally not
persisted. The 60-second chart, series and test-period shading are not implemented.
**Stopped after Phase 5. Phase 6 requires explicit approval.**
