# NetPulse

A fresh JavaFX desktop application for a college project. This implementation
currently contains **Phases 1–3**: the Maven/JavaFX foundation, an independent OSHI
monitoring engine, exact session totals, and rolling traffic data. No previous
NetPulse source was inspected or reused.

## Requirements

- Windows 11, x64, with an interactive desktop for the GUI.
- JDK 25; set `JAVA_HOME` to the JDK directory and put its `bin` directory on `PATH`.
- Internet access on the first build for Maven and dependency downloads.

A system Maven installation is unnecessary after checkout. The checked-in scripts
use Maven Wrapper **3.3.4** to download and run Apache Maven **3.9.16**. These are
different version numbers: 3.9.16 identifies the Maven distribution, not the wrapper
script implementation.

## Build and launch from PowerShell

Run these commands from the project directory:

```powershell
java -version
javac -version
.\mvnw.cmd -version
.\mvnw.cmd clean verify
.\mvnw.cmd javafx:run
```

The application opens a window titled **NetPulse** with a dark dashboard, an
**Application ready** status, and a monitoring placeholder. Resize the window
normally. Close it with the title-bar close button; JavaFX and the Maven launch
process then exit naturally. The status describes application startup, not network
connectivity.

The application now starts one background network-monitor worker after displaying
the window. No speeds or totals are displayed yet: the approved dashboard shell
is preserved while the engine maintains the data. Closing the window calls the monitor's nonblocking `stop()` method.
Startup failures propagate to the launcher instead of being hidden.

`target/netpulse-1.0.0-SNAPSHOT.jar` is the ordinary Maven application JAR, not a
self-contained executable or installer. Use `javafx:run` to launch with the required
JavaFX module path and native-access option configured in the POM.

## Dependencies

All requested versions are retained and pinned in `pom.xml`:

| Component | Version |
| --- | --- |
| Java compiler release | 25 |
| JavaFX controls and FXML | 25.0.1 |
| OSHI core | 7.8.0 |
| Jackson BOM and databind | 2.23.0 |
| SLF4J JDK14 provider | 2.0.20 |
| JUnit Jupiter, test scope | 5.13.4 |
| Maven Compiler Plugin | 3.14.1 |
| Maven Surefire Plugin | 3.5.4 |
| JavaFX Maven Plugin | 0.0.8 |

Jackson's BOM manages its components. It correctly selects `jackson-core` 2.23.0
and `jackson-annotations` **2.23**; individual Jackson component versions are not
forced. OSHI brings in `oshi-common` 7.8.0, `jna-jpms` and `jna-platform-jpms` 5.19.1,
and `slf4j-api` 2.0.20. There is one matching SLF4J provider and no duplicate
non-JPMS JNA dependency. JavaFX resolves the Windows-specific native JARs.

OSHI supplies the Phase 2 network counters. Jackson remains a foundation dependency
for a later phase. The application does not execute Ookla or parse speed-test
results. No Ookla executable is required, downloaded, or bundled.

## Project layout

```text
pom.xml
mvnw / mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
src/main/java/com/netpulse/
  core/
    AdapterSelector.java
    NetworkSpeedCalculator.java
    SessionTrafficTracker.java
    RollingTrafficWindow.java
  monitor/
    NetworkStatsProvider.java
    OshiNetworkStatsProvider.java
    NetworkMonitorService.java
  speedtest/                Reserved official CLI integration
  model/                    Immutable metadata, measurements, totals, and history
  ui/
    NetPulseApplication.java
    FxDashboardController.java
  util/                     Reserved formatting utilities
src/main/resources/
  fxml/FxDashboardView.fxml
  css/styles.css
src/test/java/com/netpulse/
  DependencyCompatibilityTest.java
  core/                     Synthetic calculation, selection, totals, and window tests
  monitor/                  Synthetic service tests and manual OSHI check
  support/                  Synthetic adapter fixtures
  ui/DashboardResourcesTest.java
```

The `speedtest` and `util` packages still contain only `package-info.java`
documentation. The application is a single Maven module and does not need a JPMS
module descriptor or a dependency-injection framework.

## Phase 2 monitoring engine

`NetworkStatsProvider.read()` returns immutable `AdapterSample` values containing
detached `AdapterInfo` metadata and either a timestamped `CounterReading` or an
explicit read error. OSHI objects and Windows metadata normalization stay inside
`OshiNetworkStatsProvider`. Fresh Windows OSHI enumeration initializes counters;
the provider checks its initialization timestamp and avoids a redundant immediate
`updateAttributes()` call. Routing is read first so a slow route lookup does not
delay already captured counter values. Routing failures do not stop monitoring.

`AdapterSelector` operates only on metadata. Candidates must be up according to
both OSHI and Java, have a usable unicast MAC and non-loopback address, and have
Wi-Fi/Ethernet type or medium evidence. Known loopback, tunnel, point-to-point,
virtual, VM-MAC, VPN-driver, WAN-miniport, and Windows filter interfaces are
excluded. Software-driver signatures use specific descriptions, not loose words
such as "VPN" in a user-facing name.

The selector preserves an eligible current identity (index, name, and MAC).
Otherwise it ranks physical evidence, connector evidence, default-route evidence,
then index/name/MAC for a deterministic fallback. An Ethernet/Wi-Fi candidate with
no physical-medium or connector evidence is exposed as `AMBIGUOUS`; positive
evidence is labeled `LIKELY_PHYSICAL`, never guaranteed physical. A VPN route does
not automatically displace the underlying physical adapter. Windows driver
metadata, known VM MAC prefixes, and driver-description heuristics are imperfect.

`NetworkSpeedCalculator` owns a single baseline. Rates use byte differences divided
by actual `System.nanoTime()` intervals. Mbps is bytes/second divided by 1,000,000
and multiplied by 8; MB/s is bytes/second divided by 1,000,000. Valid unchanged
counters produce zero. The first sample, adapter changes, counter decreases, and
gaps **greater than** three seconds establish a new baseline without a speed.
Invalid counters and nonpositive intervals clear it entirely. A read failure or
loss of an eligible adapter also clears the baseline, so recovery starts fresh.

`NetworkMonitorService` uses one scheduled worker with a one-second fixed delay
after each completed sample. There are no overlaps or catch-up bursts. It exposes
`latestSnapshot()` and an optional callback on the worker thread. Callbacks must
be brief; future UI consumers must dispatch presentation work onto JavaFX themselves.
Models, selection, calculation, and the monitoring service import no JavaFX classes.

Unavailable speeds are represented by an empty `Optional`, with a reason such as
`BASELINE`, `COUNTER_RESET`, `READ_FAILED`, `SAMPLING_GAP`, or `NO_ADAPTER`. A failed
counter read can coexist with a known connected link; a whole-provider failure
makes link state unknown. `latestSnapshot()` returns a `STALE` unavailable view
after three seconds without a fresh snapshot, and the engine rejects counters
already stale when a provider returns. Link status does not verify internet access.

Repeated `start()` calls while running are harmless. `stop()`/`close()` are
idempotent and terminal; create another service to begin another lifecycle.
Stopping cancels the task, interrupts the worker, shuts down its executor, exposes
a stopped snapshot, and discards results from in-flight reads. It does not wait
on a native OSHI call or subscriber on the JavaFX thread. An already entered
callback may finish, and a native call that ignores interruption may finish later;
the worker is a daemon and cannot keep the application alive.

Phase 3 extends this engine with session totals and rolling data as described
below. Gauges, charts, Ookla processes, result parsers, persistent traffic history,
databases, and servers remain unimplemented.

The Windows metadata values follow Microsoft's
[MIB_IF_ROW2 documentation](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/ns-netioapi-mib_if_row2).

## Phase 3 traffic data

`NetworkSpeedCalculator` now includes an immutable `TrafficDelta` alongside each
available speed. It contains exact downloaded/uploaded byte differences and the
monotonic start/end of the measured interval. `SpeedMeasurement.available` requires
both the speed and this delta; unavailable measurements contain neither. Totals
are never reconstructed from rates, elapsed display time, or rounded Mbps.

`SessionTrafficTracker` adds only these valid deltas. `SessionTrafficTotals` exposes
downloaded/uploaded bytes as `BigInteger`, preserving exact values even beyond
`long` capacity. Valid idle measurements add zero. Baselines, resets, switches,
disconnections, invalid data, and failed reads add nothing. Valid intervals on a
replacement adapter continue the same session; missed intervals remain uncounted.
Each new monitor/application instance starts a new zero-total session. Stopping a
monitor does not erase its already accepted data.

`RollingTrafficWindow` stores immutable `TrafficHistorySample` values, using the
counter interval's actual end timestamp for valid speeds and the actual observation
time for unavailable gaps. Each point also retains a wall-clock observation time,
sequence, adapter identity, measurement status, and adapter-boundary flag. Zero is
a valid speed; unavailable data stays absent. Identity changes and reconnections
mark boundaries so a future renderer can break its paths. No missing points are
interpolated or synthesized.

Samples exactly 60 seconds old remain; older samples expire. Both updates and
read-only views apply monotonic expiry, including when the worker is blocked or
stopped. An additional **128-sample** cap bounds internal memory during unusually
fast or same-timestamp bursts. At the normal approximately one-second cadence,
the complete 60-second window fits. A burst can evict older points still inside
60 seconds; it never produces replacement or averaged samples. Existing immutable
snapshots held by a caller remain unchanged.

Each accepted monitoring poll receives a positive, increasing `MonitoringSnapshot`
sequence **after** the cancellation and monotonic-order guards. Sequence zero is
reserved for unaccepted startup/shutdown views. Only the sampling worker updates
the tracker and window, once per accepted poll, inside the short existing lifecycle
publication guard. That bounded in-memory step contains no I/O, OSHI calls, user
callbacks, or reader-held locks. Cancelled late reads cannot change totals or
history. Source counters older than the preceding accepted observation are gaps,
not a way to reconstruct previously missed traffic.

The standalone tracker/window acceptors reject duplicate or lower sequences and
older timestamps. The tracker additionally rejects replayed/overlapping already
counted intervals, even if assigned a new sequence. They are single-writer
components with thread-safe immutable reads, matching the monitor's existing
single-worker architecture; concurrent external writers are not supported.

`NetworkMonitorService` exposes:

- `dataSnapshot()`: one coherent `MonitoringDataSnapshot` containing the last
  accepted poll, totals, and current immutable history.
- `sessionTotals()`: current exact immutable totals.
- `trafficHistory()`: the immutable, currently retained points.
- `latestSnapshot()`: the existing live-status/freshness view from Phase 2.

Use `dataSnapshot()` when the three data values must correspond to the same
accepted poll. Independent getter calls can span different publications. Repeated
reads never accumulate bytes or append history. `latestAccepted` is empty before
the first accepted poll and remains the last factual observation after stop or
expiry. Derived `STALE` views and lifecycle messages do not become new historical
samples. Future live UI code should use the freshness-aware status as appropriate.

New production files are `SessionTrafficTracker`, `RollingTrafficWindow`,
`TrafficDelta`, `SessionTrafficTotals`, `TrafficHistorySample`, and
`MonitoringDataSnapshot`. Existing calculator/measurement/snapshot/service files
were extended; OSHI access, adapter selection, Maven dependencies, the JavaFX
controller, FXML, CSS, and application lifecycle were preserved.

## Tests

`DashboardResourcesTest` verifies that Maven packages the FXML and CSS, that the
FXML declares the existing controller, and that its relative stylesheet reference
resolves correctly. `DependencyCompatibilityTest` checks that Jackson, the JDK14
logging provider, and OSHI can initialize together without sampling any interfaces.
These original tests remain in place alongside the new engine tests.

The calculation, selection, service, and software-interface tests use synthetic
metadata and providers. Service tests inject a manual scheduler and clocks, plus
one short real-scheduler check for periodic execution, no overlap, and fixed-delay
spacing. Shutdown during an unfinished read, read failures, reconnection, stale
data, and subscriber exceptions are covered. The subscriber-exception test
deliberately logs warnings; those expected messages are not test failures.

The resource test is not a JavaFX rendering test. GUI startup, visual layout, and
normal window shutdown were separately verified on an actual Windows desktop.

Phase 3 adds synthetic totals/window tests, exact-delta calculator checks, and
service integration tests. Coverage includes values beyond `long` capacity,
rounded-to-zero display rates, duplicates, replayed intervals, stale order,
immutable snapshots, expiry, clock wraparound, adapter boundaries, cancellation,
and coherent reads while another thread publishes 1,000 synthetic samples. No
automated test depends on real network traffic.

## Phase 1 verification

Environment inspected on **18 November 2026**:

- Workspace was empty before project creation.
- Eclipse Temurin JDK and `javac` **25.0.5**, runtime build **25.0.5+8-LTS**.
- Windows 11 x64, OS build **10.0.26300**.
- System Maven **3.10.1**; wrapper Maven **3.9.16** downloaded and executed.

Maven resolved the exact requested libraries and plugins. Compilation and tests
passed with release 25. The JavaFX Maven plugin launched the real application;
the visible, responding window titled `NetPulse` was inspected at its initial
size and minimum size at 125% display scaling. The status and CSS rendered
correctly. Closing the window terminated the application and Maven launcher with
the launch command returning exit code 0. The launch log contained no application,
FXML, or CSS errors.

The resolved OSHI 7.8.0 bytecode was inspected using `javap`. Confirmed future APIs:

- `HardwareAbstractionLayer.getNetworkIFs()` and `getNetworkIFs(boolean)`.
- `NetworkIF.getBytesRecv()`, `getBytesSent()`, `updateAttributes()`, and `getTimeStamp()`.
- `getIfOperStatus()`, `getIfType()`, `getNdisPhysicalMediumType()`, and `isConnectorPresent()`.
- `queryNetworkInterface()`, `getIndex()`, `getMacaddr()`, and `isKnownVmMacAddr()`.
- `NetworkParams.getRoutes()` and the IPv4/IPv6 default-gateway getters.
- `NetworkParams.IPRoute` interface-index, interface-name, prefix, gateway, and metric getters.

This verifies API availability, not network-monitoring correctness, which belongs
to a later phase.

The exact successful Maven verification commands included:

```powershell
mvn --batch-mode --no-transfer-progress org.apache.maven.plugins:maven-dependency-plugin:3.8.1:resolve
mvn --batch-mode --no-transfer-progress -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper '-Dmaven=3.9.16' '-Dtype=only-script'
.\mvnw.cmd -version
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime'
.\mvnw.cmd --batch-mode --no-transfer-progress javafx:run *> target/javafx-launch.log
```

Quote Maven `-D` arguments containing dotted values in PowerShell, as shown above.
The final compatibility-test run was:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify *> phase1-build.log
```

It completed with **2 tests, 0 failures, 0 errors, and 0 skipped tests**. The
compatibility smoke test only serializes a fixed string; it does not parse any
Speedtest JSON or implement result handling.

Build reports and screenshots are temporary files under ignored `target/`; a clean
build removes them. This section records the completed Phase 1 checks; Phase 2
was subsequently approved and is recorded below.

## Phase 2 verification

Verified on Windows 11 x64 with the same JDK, Maven, and dependency versions on
**18 November 2026**:

- `clean verify` passed: **76 tests, 0 failures, 0 errors, 0 skipped**.
- Actual OSHI 7.8.0 reads observed cumulative byte counters and the Intel(R) Wi-Fi 6
  AX201 160MHz adapter. A five-snapshot service check produced one baseline and
  **four valid throughput measurements**, approximately 1.04 seconds apart.
- One observed interval measured **0.140197 Mbps download** and **0.105894 Mbps
  upload**. These are all-adapter traffic measurements, not a benchmark. An earlier
  live check also observed a genuine zero-download interval.
- Windows logical filter interfaces were found with duplicated counters. Their
  driver descriptions are excluded, and connector evidence is preferred even
  without route information. The final live check still selected the physical
  Intel adapter.
- JavaFX launched visibly and remained responsive with the original heading,
  ready status, dark CSS, and placeholder. A Java thread dump showed the separate
  `NetPulse-network-monitor` daemon and JavaFX Application Thread.
- Normal window closure exited the application and Maven launcher with code 0.
  The GUI launch log contained no warnings, exceptions, FXML errors, or CSS errors.

Build and regression commands:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-DincludeScope=runtime' *> phase2-build.log
.\mvnw.cmd --batch-mode --no-transfer-progress javafx:run *> phase2-javafx-launch.log
```

The manual integration check is a test-source `main` method, not an automatically
executed unit test. It observes five samples without generating network traffic:

```powershell
$runtimeClasspath = 'target\classes;target\test-classes;' + (Get-Content -LiteralPath 'target\runtime-classpath.txt' -Raw).Trim()
java --enable-native-access=ALL-UNNAMED -cp $runtimeClasspath com.netpulse.monitor.OshiIntegrationCheck *> phase2-oshi-integration.log
```

Only Wi-Fi was observed live. Ethernet, cable removal, Wi-Fi toggling, VPN switching,
sleep/resume, and ambiguous drivers have synthetic coverage or documented behavior,
but were not physically exercised. No network connection was disrupted for these
checks. Physical identification remains heuristic; counters include all traffic on
the selected adapter. Phase 3 was subsequently approved and is recorded below.

## Phase 3 verification

Verified on **18 November 2026**, using the same Windows 11 x64, JDK 25, pinned
Maven wrapper, and dependency versions:

- Compilation, tests, packaging, and `clean verify` passed: **117 tests, 0 failures,
  0 errors, 0 skipped**. All Phase 1/2 test scenarios remain included.
- Synthetic integration checks verify exactly-once totals/history updates, idle
  zeros, read-failure and reset gaps, adapter switches without spikes, late-result
  discard after stop, and immutable coherent data publication under concurrent reads.
- A real NetPulse JavaFX window opened, rendered the unchanged dashboard correctly,
  and remained responsive with the separate `NetPulse-network-monitor` worker.
- Normal window closure exited the application and Maven launcher with code 0.
  The launch log contained no warnings, exceptions, FXML errors, or CSS errors.

Commands used:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify *> phase3-build.log
.\mvnw.cmd --batch-mode --no-transfer-progress javafx:run *> phase3-javafx-launch.log
```

The bounded-memory and concurrent-reader checks are unit/integration tests with
synthetic inputs, not an extended live-traffic soak test. The Phase 2 physical
adapter-identification and native-call interruption limitations remain. No new
dependencies, graph UI, gauges, layout changes, Ookla integration, test intervals,
database, export, or persistence were added. No known Phase 3 blockers remain.
Phase 4 has not started and requires separate approval.
