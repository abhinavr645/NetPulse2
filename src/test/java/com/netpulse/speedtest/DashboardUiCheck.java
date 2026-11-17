package com.netpulse.speedtest;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

import com.netpulse.model.AdapterSample;
import com.netpulse.model.SpeedTestState.Phase;
import com.netpulse.monitor.NetworkMonitorService;
import com.netpulse.monitor.OshiNetworkStatsProvider;
import com.netpulse.support.AdapterFixture;
import com.netpulse.ui.FxDashboardController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

/** Opt-in interactive Windows verification, deliberately excluded from headless JUnit runs.
 * Default: synthetic counters and real controlled child processes, no network use.
 * --real-cli=PATH: real OSHI and one explicitly requested official Ookla benchmark.
 */
public final class DashboardUiCheck {
    private static volatile Throwable failure;
    private DashboardUiCheck() { }

    public static void main(String[] args) {
        Application.launch(DesktopCheck.class, args);
        if (failure != null) { throw new AssertionError("Dashboard UI check failed", failure); }
    }

    public static final class DesktopCheck extends Application {
        private final AtomicReference<String> mode = new AtomicReference<>("success");
        private final AtomicInteger networkMode = new AtomicInteger();
        private final AtomicLong received = new AtomicLong();
        private final List<Process> children = new CopyOnWriteArrayList<>();
        private NetworkMonitorService monitor;
        private SpeedTestService tests;
        private FxDashboardController controller;
        private Parent root;
        private Stage stage;

        @Override public void start(Stage stage) throws Exception {
            Platform.setImplicitExit(false);
            this.stage = stage;
            String realCli = getParameters().getNamed().get("real-cli");
            if (realCli != null) {
                monitor = new NetworkMonitorService(new OshiNetworkStatsProvider());
                tests = new SpeedTestService(Path.of(realCli));
            } else {
                monitor = new NetworkMonitorService(() -> {
                    check(!Platform.isFxApplicationThread(), "Network read must be off the FX thread");
                    int current = networkMode.get();
                    if (current == 2) { return List.of(); }
                    if (current == 3) { throw new java.io.IOException("Synthetic failed read"); }
                    if (current == 4) {
                        try { Thread.sleep(Duration.ofSeconds(4)); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("Synthetic read interrupted", interrupted);
                        }
                    }
                    AdapterFixture adapter = new AdapterFixture(current == 5 ? 2 : 1);
                    long bytes = current == 1 ? received.get() : received.addAndGet(12_500_000);
                    return List.of(adapter.reading(bytes, bytes / 2, System.nanoTime()));
                });
                Path executable = Path.of("target", "dashboard-fixture", "speedtest.exe").toAbsolutePath();
                Files.createDirectories(executable.getParent());
                Files.write(executable, new byte[] {'M', 'Z'});
                Path classes = Path.of(ControlledCliFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                tests = new SpeedTestService(new SpeedTestExecutableLocator(Optional.of(executable)), command -> {
                    check(!Platform.isFxApplicationThread(), "Process launch must be off the FX thread");
                    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                            "-cp", classes.toString(), ControlledCliFixture.class.getName(), mode.get(), command.getLast()).start();
                    children.add(child);
                    return child;
                }, _ -> { }, Duration.ofSeconds(12), Duration.ofMillis(100), Clock.systemUTC());
            }
            controller = new FxDashboardController(monitor, tests);
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/FxDashboardView.fxml"));
            loader.setControllerFactory(_ -> controller);
            root = loader.load();
            stage.setTitle(realCli == null ? "NetPulse — controlled UI verification" : "NetPulse — real CLI verification");
            stage.setScene(new Scene(root, 1120, 784));
            stage.setMinWidth(640);
            stage.setMinHeight(520);
            stage.show();
            monitor.start();
            Thread.ofPlatform().name("NetPulse-desktop-check").daemon().start(() -> {
                try {
                    if (realCli == null) { syntheticChecks(); } else { realChecks(); }
                    System.out.println("DASHBOARD UI CHECK PASSED");
                } catch (Throwable problem) {
                    failure = problem;
                    problem.printStackTrace();
                } finally {
                    try {
                        fx(() -> { controller.close(); stage.close(); return null; });
                        for (Process child : children) {
                            check(child.waitFor(3, TimeUnit.SECONDS), "Owned CLI child survived shutdown");
                        }
                        check(!monitor.isRunning(), "Monitor must stop on shutdown");
                    } catch (Throwable problem) { failure = problem; problem.printStackTrace(); }
                    Platform.exit();
                }
            });
        }

        private void syntheticChecks() throws Exception {
            await(() -> monitor.latestSnapshot().measurement().speed().isPresent(), 6);
            awaitFx(() -> label("monitoringStatus").contains("Live adapter"), 3);
            fx(() -> {
                check(root.lookup("#downloadCard").getBoundsInParent().getMinY()
                        == root.lookup("#uploadCard").getBoundsInParent().getMinY(), "Gauges should share a row");
                check(root.lookup("#benchmarkColumn").getBoundsInParent().getMinY()
                        == root.lookup("#monitoringColumn").getBoundsInParent().getMinY(), "Wide window should show both columns side by side");
                check(!button("startTest").isDisabled() && button("cancelTest").isDisabled(), "Idle controls");
                return null;
            });
            networkMode.set(1);
            awaitFx(() -> root.lookup("#downloadGauge").getAccessibleText().startsWith("0 Mbps"), 5);
            screenshot("phase5-idle");
            String total = fx(() -> label("sessionDownload"));
            networkMode.set(2);
            awaitFx(() -> label("adapterName").equals("No eligible adapter"), 5);
            fx(() -> {
                check(root.lookup("#downloadGauge").getAccessibleText().equals("Speed unavailable"), "Unavailable download");
                check(root.lookup("#uploadGauge").getAccessibleText().equals("Speed unavailable"), "Unavailable upload");
                check(label("sessionDownload").equals(total), "Unavailable samples must preserve session totals");
                return null;
            });
            screenshot("phase5-unavailable");
            networkMode.set(5);
            awaitFx(() -> label("interfaceName").equals("adapter-2"), 5);
            networkMode.set(3);
            awaitFx(() -> label("monitoringStatus").contains("could not be read"), 5);
            networkMode.set(0);
            awaitFx(() -> label("monitoringStatus").contains("Live adapter"), 5);
            networkMode.set(4);
            awaitFx(() -> label("monitoringStatus").contains("overdue"), 6);
            fx(() -> { check(root.lookup("#downloadGauge").getAccessibleText().equals("Speed unavailable"), "Stale gauge cleared"); return null; });
            networkMode.set(0);
            awaitFx(() -> label("monitoringStatus").contains("Live adapter"), 8);

            fireStart();
            awaitFx(() -> !label("benchmarkDownload").equals("—"), 5);
            fx(() -> {
                check(label("benchmarkDownload").equals("100.00"), "Benchmark Mbps");
                check(label("idlePing").equals("12.50 ms"), "Idle ping must not use loaded latency");
                check(label("jitter").equals("1.25 ms"), "Jitter");
                check(label("packetLoss").equals("0.50%"), "Packet loss");
                check(label("server").contains("Synthetic"), "Server");
                check(!label("completedAt").equals("—"), "Completion time");
                return null;
            });
            screenshot("phase5-synthetic-result");
            mode.set("malformed");
            fireStart();
            await(() -> tests.latestState().phase() == Phase.FAILED, 5);
            awaitFx(() -> !button("startTest").isDisabled(), 3);
            fx(() -> { check(label("benchmarkDownload").equals("100.00"), "Retain result after failure"); return null; });
            mode.set("hang");
            fireStart();
            await(() -> tests.latestState().phase() == Phase.RUNNING, 5);
            awaitFx(() -> !button("cancelTest").isDisabled(), 3);
            fx(() -> { button("cancelTest").fire(); return null; });
            awaitFx(() -> label("testStatus").contains("cancelled"), 5);
            fx(() -> { check(label("benchmarkDownload").equals("100.00"), "Retain result after cancel"); return null; });
            screenshot("phase5-cancelled");

            fx(() -> { stage.setWidth(640); stage.setHeight(520); return null; });
            Thread.sleep(500);
            fx(() -> { ((ScrollPane) root.lookup("#dashboardScroll")).setVvalue(0); return null; });
            screenshot("phase5-minimum");
            fx(() -> {
                ScrollPane scroll = (ScrollPane) root.lookup("#dashboardScroll");
                check(root.lookup("#benchmarkColumn").getBoundsInParent().getMinY()
                        > root.lookup("#monitoringColumn").getBoundsInParent().getMinY(), "Narrow window stacks columns");
                scroll.setVvalue(1);
                return null;
            });
            Thread.sleep(400);
            screenshot("phase5-minimum-bottom");
            fx(() -> { stage.setWidth(1136); stage.setHeight(800); ((ScrollPane) root.lookup("#dashboardScroll")).setVvalue(0); return null; });
            // Close the window while a real controlled child is active to verify controller cleanup.
            fireStart();
            await(() -> tests.latestState().phase() == Phase.RUNNING, 5);
        }

        private void realChecks() throws Exception {
            await(() -> monitor.latestSnapshot().measurement().speed().isPresent(), 10);
            fireStart();
            await(() -> tests.latestState().phase() == Phase.RUNNING || !tests.isRunning(), 10);
            screenshot("phase5-real-running");
            await(() -> !tests.isRunning(), 125);
            awaitFx(() -> !button("startTest").isDisabled(), 3);
            check(tests.latestState().phase() == Phase.COMPLETED, tests.latestState().message());
            System.out.println("REAL RESULT: " + tests.latestState().result().orElseThrow());
            screenshot("phase5-real-result");
            Thread.sleep(3_000);
        }

        private void fireStart() throws Exception {
            fx(() -> {
                check(!button("startTest").isDisabled(), "Start enabled before request");
                button("startTest").fire();
                check(button("startTest").isDisabled(), "Start disabled immediately");
                return null;
            });
        }

        private Button button(String id) { return (Button) root.lookup("#" + id); }
        private String label(String id) { return ((Label) root.lookup("#" + id)).getText(); }
        private void screenshot(String name) throws Exception {
            Thread.sleep(250); // Allow the presentation-only gauge easing to finish.
            WritableImage image = fx(() -> root.snapshot(null, null));
            BufferedImage bitmap = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) { bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y)); }
            }
            Path folder = Path.of("target", "dashboard-screenshots");
            Files.createDirectories(folder);
            ImageIO.write(bitmap, "png", folder.resolve(name + ".png").toFile());
            System.out.println("SCREENSHOT " + name + " " + fx(stage::getOutputScaleX) + "x output scale");
        }

        private static void awaitFx(BooleanSupplier condition, int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!fx(condition::getAsBoolean)) {
                if (System.nanoTime() > deadline) { throw new AssertionError("Timed out waiting for a UI state"); }
                Thread.sleep(50);
            }
        }
        private static void await(BooleanSupplier condition, int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) { throw new AssertionError("Timed out waiting for a service state"); }
                Thread.sleep(50);
            }
        }
        private static <T> T fx(Callable<T> task) throws Exception {
            FutureTask<T> future = new FutureTask<>(task);
            Platform.runLater(future);
            return future.get(5, TimeUnit.SECONDS);
        }
        private static void check(boolean condition, String message) {
            if (!condition) { throw new AssertionError(message); }
        }
        @Override public void stop() { if (controller != null) { controller.close(); } }
    }
}
