# Dashboard layout correction

Verified on Windows 11 on 19 November 2026 with Java 25 and JavaFX 25.0.1.

The reported excessive scrolling and oversized benchmark card were reproduced
in the existing desktop verification harness. Before the fix, the new layout
assertion failed because the benchmark card stretched beyond its preferred height.

The nested gauge FlowPane estimated a wrapped second row when computing preferred
height. Replacing it with an HBox keeps the two gauges in one row. Both main
columns now use their preferred content heights and align at the top. The outer
FlowPane still stacks the columns at narrow window widths. Monitoring, charting,
CLI execution, result handling and dashboard styling are unchanged.

## Verification

Commands run from the project root:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
.\scripts\verify-dashboard.ps1
.\scripts\verify-dashboard.ps1 -UiScale 1.0
.\scripts\verify-dashboard.ps1 -LiveMonitor
```

- Maven verification passed: 227 tests, zero failures, errors or skips.
- Actual JavaFX windows passed desktop checks at the host's 125% display scale
  and forced 100% scale. Screenshots were visually inspected.
- Layout regression checks cover content-sized cards, no reserved empty rows,
  result display, stacked columns at the 640 x 520 minimum window size, and
  returning to a wider window.
- Existing desktop checks passed for idle/unavailable/stale measurements,
  successful/failed/cancelled controlled CLI tests, retained results, graph
  updates and shutdown during an active child process.
- Live OSHI sampling and graph updates passed at 125% scale, including continued
  monitoring during a controlled benchmark. No real internet benchmark was run.
- The harness confirmed its worker threads, child processes and UI timers stopped.

Screenshots at 100% scale:

- [Dashboard top](ui-layout-evidence/dashboard-top-100.png)
- [Dashboard bottom](ui-layout-evidence/dashboard-bottom-100.png)
- [Minimum window](ui-layout-evidence/minimum-window-100.png)

The source application and Maven JAR contain the fix. Existing MSI, application
image and portable ZIP artifacts have not been rebuilt for this UI correction.
Restart through `.\scripts\start-netpulse.ps1` to use the updated dashboard.
