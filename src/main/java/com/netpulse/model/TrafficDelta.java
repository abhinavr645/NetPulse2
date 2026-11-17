package com.netpulse.model;

/** Exact counter differences for one valid monotonic measurement interval. */
public record TrafficDelta(long downloadedBytes, long uploadedBytes,
        long startedAtNanos, long endedAtNanos) {
    public TrafficDelta {
        if (downloadedBytes < 0 || uploadedBytes < 0 || endedAtNanos - startedAtNanos <= 0) {
            throw new IllegalArgumentException("Traffic requires nonnegative bytes and a positive interval");
        }
    }
}
