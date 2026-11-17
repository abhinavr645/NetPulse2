# NetPulse

A fresh JavaFX desktop application for a college project. This implementation
currently contains **Phase 1 only**: the Maven foundation, dependency verification,
and a minimal dashboard window. No previous NetPulse source was inspected or reused.

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

The application owns no background executors, network readers, or child processes
in Phase 1. There is therefore no additional shutdown resource to dispose of yet.
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

OSHI, Jackson, and logging are foundation dependencies for later phases. The
application does not read network interfaces, calculate rates, execute Ookla, or
parse speed-test results. No Ookla executable is required, downloaded, or bundled.

## Project layout

```text
pom.xml
mvnw / mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
src/main/java/com/netpulse/
  core/                     Reserved domain calculations
  monitor/                  Reserved OSHI monitoring
  speedtest/                Reserved official CLI integration
  model/                    Reserved immutable models
  ui/
    NetPulseApplication.java
    FxDashboardController.java
  util/                     Reserved formatting utilities
src/main/resources/
  fxml/FxDashboardView.fxml
  css/styles.css
src/test/java/com/netpulse/
  DependencyCompatibilityTest.java
  ui/DashboardResourcesTest.java
```

Reserved packages contain only `package-info.java` documentation. They contain no
future-phase behavior. The application is a single Maven module and does not yet
need a JPMS module descriptor or a dependency-injection framework.

## Tests

`DashboardResourcesTest` verifies that Maven packages the FXML and CSS, that the
FXML declares the existing controller, and that its relative stylesheet reference
resolves correctly. `DependencyCompatibilityTest` checks that Jackson, the JDK14
logging provider, and OSHI can initialize together without sampling any interfaces.
Neither test claims to test unimplemented monitoring functionality.

The resource test is not a JavaFX rendering test. GUI startup, visual layout, and
normal window shutdown were separately verified on an actual Windows desktop.

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
build removes them. No installer, bundled runtime, network functionality, or Ookla
integration has been implemented. Phase 2 requires separate approval.
