package com.netpulse.model;

import java.math.BigInteger;
import java.util.Objects;

/** Exact session totals without long-overflow, saturation, or display rounding. */
public record SessionTrafficTotals(BigInteger downloadedBytes, BigInteger uploadedBytes) {
    public static final SessionTrafficTotals ZERO = new SessionTrafficTotals(BigInteger.ZERO, BigInteger.ZERO);

    public SessionTrafficTotals {
        Objects.requireNonNull(downloadedBytes);
        Objects.requireNonNull(uploadedBytes);
        if (downloadedBytes.signum() < 0 || uploadedBytes.signum() < 0) {
            throw new IllegalArgumentException("Session totals cannot be negative");
        }
    }
}
