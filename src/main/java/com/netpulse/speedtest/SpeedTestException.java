package com.netpulse.speedtest;

import com.netpulse.model.SpeedTestState.ErrorCode;

/** A user-actionable CLI or output failure, independent of JavaFX. */
public final class SpeedTestException extends Exception {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;

    public SpeedTestException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SpeedTestException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() { return code; }
}
