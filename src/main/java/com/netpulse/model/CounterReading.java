package com.netpulse.model;

/** Raw cumulative counters. Invalid values are rejected by the calculator. */
public record CounterReading(long receivedBytes, long transmittedBytes, long sampledAtNanos) {
}
