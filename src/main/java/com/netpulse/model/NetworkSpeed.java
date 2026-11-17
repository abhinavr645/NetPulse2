package com.netpulse.model;

/** Throughput in decimal units, independent of UI formatting. */
public record NetworkSpeed(double downloadBytesPerSecond, double uploadBytesPerSecond) {
    public NetworkSpeed {
        if (!Double.isFinite(downloadBytesPerSecond) || downloadBytesPerSecond < 0
                || !Double.isFinite(uploadBytesPerSecond) || uploadBytesPerSecond < 0) {
            throw new IllegalArgumentException("Speeds must be finite and nonnegative");
        }
    }

    public double downloadMbps() { return downloadBytesPerSecond / 1_000_000.0 * 8; }
    public double uploadMbps() { return uploadBytesPerSecond / 1_000_000.0 * 8; }
    public double downloadMBps() { return downloadBytesPerSecond / 1_000_000.0; }
    public double uploadMBps() { return uploadBytesPerSecond / 1_000_000.0; }
}
