package com.netpulse.speedtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.netpulse.model.SpeedTestResult;
import com.netpulse.model.SpeedTestState;
import com.netpulse.model.SpeedTestState.ErrorCode;
import com.netpulse.model.SpeedTestState.Phase;

/** Direct CLI integration, independent of JavaFX/OSHI. All process, file, and
 * listener operations run on owned workers. Callbacks must be brief. Use cancel()
 * to cancel execution, rather than cancelling the returned completion stage. */
public final class SpeedTestService implements AutoCloseable {
    static final int STDOUT_LIMIT = 1_048_576;
    static final int STDERR_LIMIT = 65_536;
    private static final System.Logger LOG = System.getLogger(SpeedTestService.class.getName());
    private final Object lifecycle = new Object();
    private final SpeedTestExecutableLocator locator;
    private final SpeedTestJsonParser parser = new SpeedTestJsonParser();
    private final ProcessLauncher launcher;
    private final Consumer<SpeedTestState> listener;
    private final Duration timeout;
    private final Duration grace;
    private final Clock clock;
    // Keep an active cleanup alive when the last JavaFX window closes. close()
    // shuts this executor down, and its thread exits after the child is reaped.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(threads("NetPulse-speedtest", false));
    private final ExecutorService readers = Executors.newFixedThreadPool(2, threads("NetPulse-speedtest-output"));
    private final ExecutorService stopper = Executors.newSingleThreadExecutor(threads("NetPulse-speedtest-stop"));
    private volatile SpeedTestState state = SpeedTestState.idle();
    private volatile Run active;
    private boolean closed;
    private long nextId;

    public SpeedTestService() { this(Optional.empty(), ignored -> { }); }
    public SpeedTestService(Path override) { this(Optional.of(Objects.requireNonNull(override)), ignored -> { }); }

    public SpeedTestService(Optional<Path> override, Consumer<SpeedTestState> listener) {
        this(new SpeedTestExecutableLocator(override),
                command -> new ProcessBuilder(command).redirectErrorStream(false).start(),
                listener, Duration.ofSeconds(120), Duration.ofMillis(500), Clock.systemUTC());
    }

    // Controlled fixtures exercise the same pipe, timeout, cancellation, and cleanup code.
    SpeedTestService(SpeedTestExecutableLocator locator, ProcessLauncher launcher,
            Consumer<SpeedTestState> listener, Duration timeout, Duration grace, Clock clock) {
        this.locator = Objects.requireNonNull(locator);
        this.launcher = Objects.requireNonNull(launcher);
        this.listener = Objects.requireNonNull(listener);
        this.timeout = positive(timeout);
        this.grace = positive(grace);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns immediately; the exclusive job includes discovery and cleanup. */
    public CompletionStage<SpeedTestState> start() {
        synchronized (lifecycle) {
            if (closed) { throw new IllegalStateException("The speed-test service is closed"); }
            if (active != null) { throw new IllegalStateException("A speed test is already active"); }
            Run run = new Run(++nextId, System.nanoTime() + timeout.toNanos());
            active = run;
            try { worker.execute(() -> execute(run)); }
            catch (RejectedExecutionException failure) { active = null; throw failure; }
            return run.completion.minimalCompletionStage();
        }
    }

    public SpeedTestState latestState() { return state; }
    public boolean isRunning() { return active != null; }

    /** Signals cancellation without waiting or touching the process on the caller's thread. */
    public boolean cancel() {
        synchronized (lifecycle) {
            if (active == null || active.cancelled.get()) { return false; }
            active.cancelled.set(true);
            requestStop(active);
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) { return; }
            closed = true;
            worker.shutdown();
            if (active == null) {
                readers.shutdownNow();
                stopper.shutdownNow();
            } else {
                active.cancelled.set(true);
                requestStop(active);
            }
        }
    }

    private void execute(Run run) {
        SpeedTestState terminal;
        try {
            transition(run, Phase.PREPARING, "Locating and verifying the official Ookla CLI");
            checkControl(run);
            Path executable = locator.locate();
            ProcessOutput version = runProcess(run, List.of(executable.toString(), "--version"), false);
            String identity = (version.stdout() + "\n" + version.stderr()).toLowerCase(Locale.ROOT);
            if (version.exitCode() != 0 || !identity.contains("speedtest by ookla")
                    || !identity.contains("official command line client")) {
                throw new SpeedTestException(ErrorCode.INVALID_EXECUTABLE,
                        "The executable does not identify itself as the official Ookla Speedtest CLI");
            }
            checkControl(run);
            ProcessOutput output = runProcess(run, List.of(executable.toString(), "--format=json"), true);
            if (output.exitCode() != 0) {
                throw new SpeedTestException(ErrorCode.NONZERO_EXIT,
                        "Ookla CLI exited with code " + output.exitCode() + diagnostic(output.stderr()));
            }
            var completedAt = clock.instant();
            transition(run, Phase.PROCESSING, "Processing the Ookla benchmark result");
            checkControl(run);
            SpeedTestResult result = parser.parse(output.stdout(), completedAt);
            terminal = new SpeedTestState(run.id, Phase.COMPLETED, "Ookla speed test completed",
                    Optional.empty(), Optional.of(result));
        } catch (SpeedTestException failure) {
            terminal = failed(run, failure.code(), failure.getMessage());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            terminal = failed(run, run.cancelled.get() ? ErrorCode.CANCELLED : ErrorCode.INTERNAL_ERROR,
                    "Speed-test worker was interrupted");
        } catch (RuntimeException | LinkageError failure) {
            LOG.log(System.Logger.Level.WARNING, "Unexpected speed-test failure", failure);
            terminal = failed(run, ErrorCode.INTERNAL_ERROR, "Unexpected internal speed-test error");
        }
        synchronized (lifecycle) {
            if (active != run) { return; }
            // Cancellation and terminal publication share the same lock: an
            // accepted cancellation cannot race with a completed-result commit.
            if (run.cancelled.get()) { terminal = failed(run, ErrorCode.CANCELLED, "Speed test cancelled"); }
            if (run.cleanupFailed.get()) {
                terminal = failed(run, ErrorCode.INTERNAL_ERROR,
                        "The CLI process could not be terminated; this service cannot start another test");
            }
            state = terminal;
            active = null; // Released only after all process/pipe cleanup paths have run.
            if (run.cleanupFailed.get()) { closed = true; }
            if (closed) {
                worker.shutdown();
                readers.shutdownNow();
                stopper.shutdown();
            }
        }
        notifyListener(run, terminal);
        run.completion.complete(terminal);
    }

    private ProcessOutput runProcess(Run run, List<String> command, boolean benchmark)
            throws SpeedTestException, InterruptedException {
        checkControl(run);
        Process process;
        try { process = launcher.start(List.copyOf(command)); }
        catch (IOException failure) {
            throw new SpeedTestException(ErrorCode.STARTUP_FAILED, "Cannot start Ookla CLI: " + failure.getMessage(), failure);
        }
        run.process.set(process);
        Future<?> stdoutTask = null;
        Future<?> stderrTask = null;
        Capture stdout = new Capture(STDOUT_LIMIT, true);
        Capture stderr = new Capture(STDERR_LIMIT, false);
        try {
            process.getOutputStream().close();
            stdoutTask = readers.submit(() -> drain(run, process.getInputStream(), stdout));
            stderrTask = readers.submit(() -> drain(run, process.getErrorStream(), stderr));
            if (benchmark) { transition(run, Phase.RUNNING, "Ookla CLI is running; progress is indeterminate"); }
            checkControl(run);
            long budget = Math.min(run.deadline - System.nanoTime(),
                    benchmark ? timeout.toNanos() : TimeUnit.SECONDS.toNanos(5));
            if (budget <= 0 || !process.waitFor(budget, TimeUnit.NANOSECONDS)) {
                throw new SpeedTestException(ErrorCode.TIMEOUT, "Ookla CLI exceeded its time limit");
            }
            awaitReader(stdoutTask);
            awaitReader(stderrTask);
            checkControl(run);
            if (run.termsRequired.get()) {
                throw new SpeedTestException(ErrorCode.TERMS_REQUIRED,
                        "Ookla requires license or privacy acceptance. Run the installed CLI yourself to review its terms; NetPulse will not accept them.");
            }
            if (run.outputExceeded.get()) {
                throw new SpeedTestException(ErrorCode.OUTPUT_LIMIT, "CLI output exceeded its bounded capture limit");
            }
            return new ProcessOutput(process.exitValue(), stdout.text(), stderr.text());
        } catch (IOException failure) {
            throw new SpeedTestException(ErrorCode.INTERNAL_ERROR, "Could not close the CLI input stream", failure);
        } finally {
            if (!terminate(process)) { run.cleanupFailed.set(true); }
            closeStreams(process);
            if (stdoutTask != null) { stdoutTask.cancel(true); }
            if (stderrTask != null) { stderrTask.cancel(true); }
            run.process.compareAndSet(process, null);
        }
    }

    private void drain(Run run, InputStream input, Capture capture) {
        byte[] buffer = new byte[8192];
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                capture.append(buffer, count);
                if (capture.exceeded && run.outputExceeded.compareAndSet(false, true)) { requestStop(run); }
                if (capture.termsPrompt() && run.termsRequired.compareAndSet(false, true)) { requestStop(run); }
            }
        } catch (IOException failure) {
            if (!run.cancelled.get() && !run.outputExceeded.get() && !run.termsRequired.get()) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
    }

    private static void awaitReader(Future<?> future) throws SpeedTestException, InterruptedException {
        try { future.get(2, TimeUnit.SECONDS); }
        catch (ExecutionException | TimeoutException failure) {
            throw new SpeedTestException(ErrorCode.INTERNAL_ERROR, "Could not finish reading CLI output", failure);
        }
    }

    private void requestStop(Run run) {
        try {
            stopper.execute(() -> {
                Process process = run.process.get();
                if (process != null && !terminate(process)) { run.cleanupFailed.set(true); }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown closes this executor only after active process cleanup.
        }
    }

    private boolean terminate(Process process) {
        boolean interrupted = false;
        try {
            if (!process.isAlive()) { return true; }
            process.destroy();
            try {
                if (process.waitFor(grace.toNanos(), TimeUnit.NANOSECONDS)) { return true; }
            } catch (InterruptedException failure) { interrupted = true; }
            process.destroyForcibly();
            try { return process.waitFor(grace.toNanos(), TimeUnit.NANOSECONDS); }
            catch (InterruptedException failure) { interrupted = true; return !process.isAlive(); }
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static void closeStreams(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private void transition(Run run, Phase phase, String message) {
        SpeedTestState update = new SpeedTestState(run.id, phase, message, Optional.empty(), Optional.empty());
        synchronized (lifecycle) {
            if (active != run || run.cancelled.get() || closed) { return; }
            state = update;
        }
        notifyListener(run, update);
    }

    private void notifyListener(Run run, SpeedTestState update) {
        synchronized (lifecycle) {
            if (state != update || (active != null && active != run)) { return; }
        }
        // Notifications are serialized on worker. A newer job cannot publish
        // PREPARING until an already entered older callback has returned.
        try { listener.accept(update); }
        catch (RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Speed-test listener failed", failure); }
    }

    private static SpeedTestState failed(Run run, ErrorCode error, String message) {
        Phase phase = error == ErrorCode.TIMEOUT ? Phase.TIMED_OUT
                : error == ErrorCode.CANCELLED ? Phase.CANCELLED : Phase.FAILED;
        return new SpeedTestState(run.id, phase, message, Optional.of(error), Optional.empty());
    }

    private static void checkControl(Run run) throws SpeedTestException {
        if (run.cancelled.get()) { throw new SpeedTestException(ErrorCode.CANCELLED, "Speed test cancelled"); }
        if (run.deadline - System.nanoTime() <= 0) {
            throw new SpeedTestException(ErrorCode.TIMEOUT, "Ookla CLI exceeded its time limit");
        }
    }

    private static String diagnostic(String stderr) {
        String text = stderr.strip();
        return text.isEmpty() ? "" : ": " + text.substring(0, Math.min(text.length(), 2048));
    }

    private static Duration positive(Duration duration) {
        if (duration.isNegative() || duration.isZero() || duration.toNanos() <= 0) {
            throw new IllegalArgumentException("Timeout and grace period must be positive");
        }
        return duration;
    }

    private static ThreadFactory threads(String prefix) {
        return threads(prefix, true);
    }

    private static ThreadFactory threads(String prefix, boolean daemon) {
        AtomicInteger number = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + number.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }

    @FunctionalInterface
    interface ProcessLauncher { Process start(List<String> command) throws IOException; }
    private record ProcessOutput(int exitCode, String stdout, String stderr) { }

    private static final class Run {
        final long id;
        final long deadline;
        final CompletableFuture<SpeedTestState> completion = new CompletableFuture<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean outputExceeded = new AtomicBoolean();
        final AtomicBoolean termsRequired = new AtomicBoolean();
        final AtomicBoolean cleanupFailed = new AtomicBoolean();
        final AtomicReference<Process> process = new AtomicReference<>();
        Run(long id, long deadline) { this.id = id; this.deadline = deadline; }
    }

    private static final class Capture {
        final int limit;
        final boolean strictUtf8;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ByteArrayOutputStream signalPrefix = new ByteArrayOutputStream();
        boolean exceeded;
        boolean prefixChanged;
        boolean prompt;
        Capture(int limit, boolean strictUtf8) { this.limit = limit; this.strictUtf8 = strictUtf8; }
        void append(byte[] buffer, int count) {
            int remaining = limit - bytes.size();
            bytes.write(buffer, 0, Math.min(count, remaining));
            exceeded |= count > remaining;
            int signalBytes = Math.min(count, 16_384 - signalPrefix.size());
            if (signalBytes > 0) {
                signalPrefix.write(buffer, 0, signalBytes);
                prefixChanged = true;
            }
        }
        String text() throws SpeedTestException {
            if (!strictUtf8) { return bytes.toString(StandardCharsets.UTF_8); }
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            } catch (CharacterCodingException failure) {
                throw new SpeedTestException(ErrorCode.INVALID_JSON, "CLI output is not valid UTF-8", failure);
            }
        }
        boolean termsPrompt() {
            if (!prefixChanged) { return prompt; }
            prefixChanged = false;
            String text = signalPrefix.toString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (strictUtf8 && java.util.regex.Pattern.compile("\"type\"\\s*:\\s*\"result\"")
                    .matcher(text).find()) { return false; }
            prompt = text.contains("--accept-license") || text.contains("--accept-gdpr")
                    || ((text.contains("license") || text.contains("privacy") || text.contains("terms"))
                        && (text.contains("must accept") || text.contains("do you accept")
                            || text.contains("to accept") || text.contains("must agree")));
            return prompt;
        }
    }
}
