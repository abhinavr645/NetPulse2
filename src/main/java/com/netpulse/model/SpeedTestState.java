package com.netpulse.model;

import java.util.Objects;
import java.util.Optional;

/** Immutable lifecycle state. There is deliberately no percentage-progress field. */
public record SpeedTestState(long testId, Phase phase, String message,
        Optional<ErrorCode> error, Optional<SpeedTestResult> result) {
    public SpeedTestState {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(message);
        Objects.requireNonNull(error);
        Objects.requireNonNull(result);
        if (testId < 0 || (phase != Phase.IDLE && testId == 0)
                || (phase == Phase.COMPLETED) != result.isPresent()) {
            throw new IllegalArgumentException("Invalid speed-test state");
        }
        boolean failure = phase == Phase.FAILED || phase == Phase.TIMED_OUT || phase == Phase.CANCELLED;
        if (failure != error.isPresent()) {
            throw new IllegalArgumentException("Only failed, timed-out, or cancelled states contain an error");
        }
    }

    public static SpeedTestState idle() {
        return new SpeedTestState(0, Phase.IDLE, "Ready to run an Ookla speed test", Optional.empty(), Optional.empty());
    }

    public boolean active() {
        return phase == Phase.PREPARING || phase == Phase.RUNNING || phase == Phase.PROCESSING;
    }

    public enum Phase { IDLE, PREPARING, RUNNING, PROCESSING, COMPLETED, FAILED, TIMED_OUT, CANCELLED }
    public enum ErrorCode {
        NOT_FOUND, INVALID_EXECUTABLE, TERMS_REQUIRED, STARTUP_FAILED, TIMEOUT,
        CANCELLED, NONZERO_EXIT, INVALID_JSON, INCOMPLETE_JSON, OUTPUT_LIMIT, INTERNAL_ERROR
    }
}
