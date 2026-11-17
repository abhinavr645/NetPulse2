package com.netpulse.speedtest;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.netpulse.model.SpeedTestState.Phase;

/** A separate application JVM that returns from main immediately after close(). */
public final class SpeedTestShutdownFixture {
    private SpeedTestShutdownFixture() { }

    public static void main(String[] args) throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        String childClasses = Path.of(ControlledCliFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        SpeedTestService service = new SpeedTestService(
                new SpeedTestExecutableLocator(Optional.of(Path.of(args[0])), ""), command -> {
                    Process process = new ProcessBuilder(List.of(java, "-cp", childClasses,
                            ControlledCliFixture.class.getName(), "hang", command.getLast())).start();
                    System.out.println("CHILD_PID=" + process.pid());
                    System.out.flush();
                    return command.getLast().equals("--format=json") ? new SlowDestroy(process) : process;
                }, state -> { if (state.phase() == Phase.RUNNING) { running.countDown(); } },
                Duration.ofSeconds(8), Duration.ofMillis(100), Clock.systemUTC());
        service.start();
        if (!running.await(4, TimeUnit.SECONDS)) {
            service.close();
            throw new IllegalStateException("Controlled CLI did not start");
        }
        service.close();
        System.out.println("MAIN_RETURNED_AFTER_CLOSE");
        // No get(), join(), sleep(), or System.exit(): service ownership must keep
        // the JVM alive only as long as needed to finish active process cleanup.
    }

    private static final class SlowDestroy extends Process {
        private final Process delegate;
        SlowDestroy(Process delegate) { this.delegate = delegate; }
        @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
        @Override public InputStream getInputStream() { return delegate.getInputStream(); }
        @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public boolean waitFor(long value, TimeUnit unit) throws InterruptedException { return delegate.waitFor(value, unit); }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public void destroy() {
            try { Thread.sleep(200); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            delegate.destroy();
        }
        @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
    }
}
