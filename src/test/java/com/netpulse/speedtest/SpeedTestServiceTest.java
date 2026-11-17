package com.netpulse.speedtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.netpulse.model.SpeedTestState;
import com.netpulse.model.SpeedTestState.ErrorCode;
import com.netpulse.model.SpeedTestState.Phase;
import com.netpulse.monitor.NetworkMonitorService;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SpeedTestServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;

    @Test
    void successfulExecutionUsesOnlyDirectArgumentsAndPublishesBackgroundStates() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        try (Harness harness = new Harness("success", Duration.ofSeconds(8), state ->
                threads.add(Thread.currentThread().getName()))) {
            assertEquals(Phase.IDLE, harness.service.latestState().phase());
            var finalState = result(harness.service.start());
            assertEquals(Phase.COMPLETED, finalState.phase());
            assertEquals(100, finalState.result().orElseThrow().downloadMbps());
            assertEquals(CLOCK.instant(), finalState.result().orElseThrow().completedAt());
            assertEquals(List.of(Phase.PREPARING, Phase.RUNNING, Phase.PROCESSING, Phase.COMPLETED),
                    harness.events.stream().map(SpeedTestState::phase).toList());
            assertEquals(List.of("--version", "--format=json"),
                    harness.commands.stream().map(command -> command.getLast()).toList());
            assertTrue(harness.commands.stream().allMatch(command -> command.size() == 2));
            assertTrue(harness.commands.stream().flatMap(List::stream).noneMatch(value -> value.contains("accept-license")));
            assertTrue(threads.stream().allMatch(name -> name.startsWith("NetPulse-speedtest-")));
            assertFalse(harness.service.isRunning());
            assertFalse(harness.service.cancel());
        }
    }

    @ParameterizedTest
    @CsvSource({"nonzero,NONZERO_EXIT", "malformed,INVALID_JSON", "missing,INCOMPLETE_JSON",
            "wrong-version,INVALID_EXECUTABLE", "license,TERMS_REQUIRED", "json-license,TERMS_REQUIRED", "version-terms,TERMS_REQUIRED",
            "stdout-limit,OUTPUT_LIMIT", "stderr-limit,OUTPUT_LIMIT", "invalid-utf8,INVALID_JSON"})
    void processAndOutputFailuresAreDistinctAndDoNotInventResults(String mode, ErrorCode expected) throws Exception {
        try (Harness harness = new Harness(mode)) {
            SpeedTestState state = result(harness.service.start());
            assertEquals(Phase.FAILED, state.phase());
            assertEquals(expected, state.error().orElseThrow());
            assertTrue(state.result().isEmpty());
            assertFalse(harness.service.isRunning());
            if (mode.equals("nonzero")) {
                assertTrue(state.message().contains("code 7"));
                assertTrue(state.message().contains("Synthetic server selection failed"));
            }
        }
    }

    @Test
    void drainsBothPipesConcurrentlyAndKeepsStderrOutOfJson() throws Exception {
        try (Harness harness = new Harness("pipes")) {
            assertEquals(Phase.COMPLETED, result(harness.service.start()).phase());
        }
    }

    @Test
    void enforcesTimeoutAndReapsTheHungChild() throws Exception {
        try (Harness harness = new Harness("hang", Duration.ofSeconds(2), state -> { })) {
            var state = result(harness.service.start());
            assertEquals(Phase.TIMED_OUT, state.phase());
            assertEquals(ErrorCode.TIMEOUT, state.error().orElseThrow());
            assertTrue(state.result().isEmpty());
            assertTrue(harness.processes.stream().noneMatch(Process::isAlive));
        }
    }

    @Test
    void rejectsConcurrentStartsAndCancellationDoesNotBlockTheCaller() throws Exception {
        try (Harness harness = new Harness("hang")) {
            var completion = harness.service.start();
            assertTrue(harness.running.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, harness.service::start);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(harness.service.cancel()));
            var cancelled = result(completion);
            assertEquals(Phase.CANCELLED, cancelled.phase());
            assertEquals(ErrorCode.CANCELLED, cancelled.error().orElseThrow());
            harness.mode.set("success");
            var newer = result(harness.service.start());
            assertEquals(Phase.COMPLETED, newer.phase());
            assertEquals(2, newer.testId());
            assertEquals(newer, harness.service.latestState());
            assertOrderedTestIds(harness.events);
        }
    }

    @Test
    void escalatesFromDestroyToForcedTermination() throws Exception {
        AtomicBoolean graceful = new AtomicBoolean();
        AtomicBoolean forced = new AtomicBoolean();
        try (Harness harness = new Harness("hang")) {
            harness.resistDestroy = true;
            harness.graceful = graceful;
            harness.forced = forced;
            var completion = harness.service.start();
            assertTrue(harness.running.await(3, TimeUnit.SECONDS));
            harness.service.cancel();
            assertEquals(Phase.CANCELLED, result(completion).phase());
            assertTrue(graceful.get());
            assertTrue(forced.get());
            assertTrue(harness.processes.stream().noneMatch(Process::isAlive));
        }
    }

    @Test
    void cancellationDuringProcessingWinsOverAValidCompletedResult() throws Exception {
        AtomicReference<SpeedTestService> reference = new AtomicReference<>();
        try (Harness harness = new Harness("success", Duration.ofSeconds(8), state -> {
            if (state.phase() == Phase.PROCESSING) { assertTrue(reference.get().cancel()); }
        })) {
            reference.set(harness.service);
            SpeedTestState terminal = result(harness.service.start());
            assertEquals(Phase.CANCELLED, terminal.phase());
            assertTrue(terminal.result().isEmpty());
        }
    }

    @Test
    void closeCancelsAnActiveProcessAndPreventsFurtherStarts() throws Exception {
        try (Harness harness = new Harness("hang")) {
            var completion = harness.service.start();
            assertTrue(harness.running.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), harness.service::close);
            assertEquals(Phase.CANCELLED, result(completion).phase());
            assertThrows(IllegalStateException.class, harness.service::start);
            harness.service.close();
        }
    }

    @Test
    void cancellationDuringStartupReapsTheLateProcessBeforeAnotherTestCanStart() throws Exception {
        try (Harness harness = new Harness("success")) {
            harness.blockFirstLaunch = true;
            var completion = harness.service.start();
            try {
                assertTrue(harness.launchEntered.await(3, TimeUnit.SECONDS));
                harness.service.cancel();
                assertThrows(IllegalStateException.class, harness.service::start);
            } finally { harness.launchRelease.countDown(); }
            assertEquals(Phase.CANCELLED, result(completion).phase());
            var newer = result(harness.service.start());
            assertEquals(2, newer.testId());
            assertEquals(Phase.COMPLETED, newer.phase());
            assertEquals(newer, harness.service.latestState());
            assertOrderedTestIds(harness.events);
        }
    }

    @Test
    void anAlreadyEnteredOldCallbackCannotOverwriteNewerPublishedStates() throws Exception {
        CountDownLatch oldCallbackEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Harness harness = new Harness("success", Duration.ofSeconds(8), state -> {
            if (state.testId() == 1 && state.phase() == Phase.COMPLETED) {
                oldCallbackEntered.countDown();
                await(release);
            }
        })) {
            var first = harness.service.start();
            CompletionStage<SpeedTestState> second;
            try {
                assertTrue(oldCallbackEntered.await(3, TimeUnit.SECONDS));
                second = harness.service.start();
            } finally { release.countDown(); }
            assertEquals(Phase.COMPLETED, result(first).phase());
            SpeedTestState newest = result(second);
            assertEquals(2, newest.testId());
            assertEquals(newest, harness.service.latestState());
            assertOrderedTestIds(harness.events);
        }
    }

    @Test
    void missingInvalidStartupAndUnexpectedFailuresHaveSeparateCodes() throws Exception {
        var missing = new SpeedTestExecutableLocator(Optional.empty(), "");
        try (SpeedTestService service = service(missing, command -> { throw new AssertionError("Must not execute"); })) {
            assertEquals(ErrorCode.NOT_FOUND, result(service.start()).error().orElseThrow());
        }
        var invalid = new SpeedTestExecutableLocator(Optional.of(directory.resolve("missing.exe")), "");
        try (SpeedTestService service = service(invalid, command -> { throw new AssertionError("Must not execute"); })) {
            assertEquals(ErrorCode.INVALID_EXECUTABLE, result(service.start()).error().orElseThrow());
        }
        var valid = new SpeedTestExecutableLocator(Optional.of(pe()), "");
        try (SpeedTestService service = service(valid, command -> { throw new IOException("Synthetic startup failure"); })) {
            assertEquals(ErrorCode.STARTUP_FAILED, result(service.start()).error().orElseThrow());
        }
        try (SpeedTestService service = service(valid, command -> { throw new IllegalStateException("Synthetic internal failure"); })) {
            assertEquals(ErrorCode.INTERNAL_ERROR, result(service.start()).error().orElseThrow());
        }
    }

    @Test
    void applicationJvmWaitsForOwnedChildCleanupAfterMainReturns() throws Exception {
        Path output = directory.resolve("shutdown-fixture.log");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process parent = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classPath, SpeedTestShutdownFixture.class.getName(), pe().toString()))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS), "Parent JVM must finish bounded cleanup and exit");
            String text = Files.readString(output);
            assertEquals(0, parent.exitValue(), text);
            assertTrue(text.contains("MAIN_RETURNED_AFTER_CLOSE"), text);
            List<Long> childIds = text.lines().filter(line -> line.startsWith("CHILD_PID="))
                    .map(line -> Long.parseLong(line.substring("CHILD_PID=".length()))).toList();
            assertEquals(2, childIds.size(), text);
            for (long child : childIds) {
                assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false),
                        "Owned CLI survived application JVM shutdown: " + child);
            }
        } finally {
            if (parent.isAlive()) { parent.destroyForcibly(); parent.waitFor(3, TimeUnit.SECONDS); }
            if (Files.exists(output)) {
                Files.readAllLines(output).stream().filter(line -> line.startsWith("CHILD_PID="))
                        .map(line -> Long.parseLong(line.substring("CHILD_PID=".length())))
                        .map(ProcessHandle::of).flatMap(Optional::stream).filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void osHiKeepsSamplingDuringTheCliAndNeverConsumesItsBenchmarkResult() throws Exception {
        AdapterFixture adapter = new AdapterFixture(1);
        AtomicInteger samples = new AtomicInteger();
        CountDownLatch threeSamples = new CountDownLatch(3);
        try (Harness harness = new Harness("hang");
             NetworkMonitorService monitor = new NetworkMonitorService(() -> {
                 int number = samples.incrementAndGet();
                 return List.of(adapter.reading(number * 1000L, number * 500L, System.nanoTime()));
             }, snapshot -> threeSamples.countDown())) {
            var cli = harness.service.start();
            assertTrue(harness.running.await(3, TimeUnit.SECONDS));
            monitor.start();
            assertTrue(threeSamples.await(4, TimeUnit.SECONDS));
            assertTrue(harness.service.isRunning());
            assertTrue(monitor.isRunning());
            harness.service.cancel();
            assertEquals(Phase.CANCELLED, result(cli).phase());
            harness.mode.set("success");
            assertEquals(100, result(harness.service.start()).result().orElseThrow().downloadMbps());
            var data = monitor.dataSnapshot();
            long intervals = data.latestAccepted().orElseThrow().sequence() - 1;
            assertEquals(BigInteger.valueOf(intervals * 1000), data.sessionTotals().downloadedBytes());
            assertEquals(BigInteger.valueOf(intervals * 500), data.sessionTotals().uploadedBytes());
            assertTrue(monitor.isRunning());
        }
    }

    private SpeedTestService service(SpeedTestExecutableLocator locator, SpeedTestService.ProcessLauncher launcher) {
        return new SpeedTestService(locator, launcher, ignored -> { }, Duration.ofSeconds(8), Duration.ofMillis(100), CLOCK);
    }

    private Path pe() throws IOException {
        Path parent = Files.createDirectories(directory.resolve("CLI with spaces"));
        return Files.write(parent.resolve("speedtest.exe"), new byte[] {'M', 'Z', 0, 0});
    }

    private static SpeedTestState result(CompletionStage<SpeedTestState> completion) throws Exception {
        return completion.toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    private static void assertOrderedTestIds(List<SpeedTestState> events) {
        long previous = 0;
        for (var state : events) { assertTrue(state.testId() >= previous); previous = state.testId(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Fixture latch timed out"); } }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    private final class Harness implements AutoCloseable {
        final AtomicReference<String> mode;
        final List<Process> processes = new CopyOnWriteArrayList<>();
        final List<List<String>> commands = new CopyOnWriteArrayList<>();
        final List<SpeedTestState> events = new CopyOnWriteArrayList<>();
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch launchEntered = new CountDownLatch(1);
        final CountDownLatch launchRelease = new CountDownLatch(1);
        final AtomicBoolean firstLaunch = new AtomicBoolean(true);
        final SpeedTestService service;
        volatile boolean blockFirstLaunch;
        volatile boolean resistDestroy;
        AtomicBoolean graceful = new AtomicBoolean();
        AtomicBoolean forced = new AtomicBoolean();

        Harness(String mode) throws Exception { this(mode, Duration.ofSeconds(8), ignored -> { }); }
        Harness(String mode, Duration timeout, Consumer<SpeedTestState> listener) throws Exception {
            this.mode = new AtomicReference<>(mode);
            Path fixtureClassPath = Path.of(ControlledCliFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            Path executable = pe();
            service = new SpeedTestService(new SpeedTestExecutableLocator(Optional.empty(), executable.getParent().toString()), command -> {
                commands.add(command);
                if (firstLaunch.compareAndSet(true, false) && blockFirstLaunch) {
                    launchEntered.countDown();
                    await(launchRelease);
                }
                var childCommand = new ArrayList<>(List.of(java.toString(), "-cp", fixtureClassPath.toString(),
                        ControlledCliFixture.class.getName(), this.mode.get(), command.getLast()));
                Process child = new ProcessBuilder(childCommand).start();
                processes.add(child);
                return resistDestroy && command.getLast().equals("--format=json")
                        ? new ResistDestroy(child, graceful, forced) : child;
            }, state -> {
                events.add(state);
                if (state.phase() == Phase.RUNNING) { running.countDown(); }
                listener.accept(state);
            }, timeout, Duration.ofMillis(100), CLOCK);
        }

        @Override
        public void close() throws Exception {
            launchRelease.countDown();
            service.close();
            for (Process process : processes) {
                if (process.isAlive() && !process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    fail("An owned controlled CLI process survived cleanup");
                }
            }
        }
    }

    private static final class ResistDestroy extends Process {
        private final Process delegate;
        private final AtomicBoolean graceful;
        private final AtomicBoolean forced;
        ResistDestroy(Process delegate, AtomicBoolean graceful, AtomicBoolean forced) {
            this.delegate = delegate; this.graceful = graceful; this.forced = forced;
        }
        @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
        @Override public InputStream getInputStream() { return delegate.getInputStream(); }
        @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException { return delegate.waitFor(time, unit); }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public void destroy() { graceful.set(true); }
        @Override public Process destroyForcibly() { forced.set(true); delegate.destroyForcibly(); return this; }
    }
}
