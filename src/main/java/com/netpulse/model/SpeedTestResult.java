package com.netpulse.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** An Ookla benchmark result, separate from OSHI adapter measurements. */
public record SpeedTestResult(double downloadBytesPerSecond, double uploadBytesPerSecond,
        double idleLatencyMillis, double jitterMillis, OptionalDouble packetLossPercent,
        Optional<ServerDetails> server, Optional<String> isp, Optional<URI> resultUrl,
        Optional<LoadedLatency> downloadLatency, Optional<LoadedLatency> uploadLatency,
        Instant completedAt) {
    public SpeedTestResult {
        nonnegative(downloadBytesPerSecond);
        nonnegative(uploadBytesPerSecond);
        nonnegative(idleLatencyMillis);
        nonnegative(jitterMillis);
        Objects.requireNonNull(packetLossPercent).ifPresent(value -> {
            nonnegative(value);
            if (value > 100) { throw new IllegalArgumentException("Packet loss exceeds 100 percent"); }
        });
        Objects.requireNonNull(server);
        Objects.requireNonNull(isp);
        Objects.requireNonNull(resultUrl);
        Objects.requireNonNull(downloadLatency);
        Objects.requireNonNull(uploadLatency);
        Objects.requireNonNull(completedAt);
    }

    public double downloadMbps() { return downloadBytesPerSecond / 125_000.0; }
    public double uploadMbps() { return uploadBytesPerSecond / 125_000.0; }
    public double downloadMBps() { return downloadBytesPerSecond / 1_000_000.0; }
    public double uploadMBps() { return uploadBytesPerSecond / 1_000_000.0; }

    private static void nonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Result numbers must be finite and nonnegative");
        }
    }

    public record ServerDetails(OptionalLong id, Optional<String> name, Optional<String> location,
            Optional<String> country, Optional<String> host, OptionalInt port) {
        public ServerDetails {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(location);
            Objects.requireNonNull(country);
            Objects.requireNonNull(host);
            Objects.requireNonNull(port);
            if ((id.isPresent() && id.getAsLong() < 0)
                    || (port.isPresent() && (port.getAsInt() < 0 || port.getAsInt() > 65535))) {
                throw new IllegalArgumentException("Invalid server identifier or port");
            }
        }
    }

    /** Loaded latency's IQM is not the idle ping. Missing values remain absent. */
    public record LoadedLatency(OptionalDouble iqmMillis, OptionalDouble lowMillis,
            OptionalDouble highMillis, OptionalDouble jitterMillis) {
        public LoadedLatency {
            Objects.requireNonNull(iqmMillis).ifPresent(SpeedTestResult::nonnegative);
            Objects.requireNonNull(lowMillis).ifPresent(SpeedTestResult::nonnegative);
            Objects.requireNonNull(highMillis).ifPresent(SpeedTestResult::nonnegative);
            Objects.requireNonNull(jitterMillis).ifPresent(SpeedTestResult::nonnegative);
        }
    }
}
