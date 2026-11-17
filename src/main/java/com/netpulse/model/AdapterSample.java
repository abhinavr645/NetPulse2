package com.netpulse.model;

import java.util.Objects;
import java.util.Optional;

/** Fresh metadata with either fresh counters or an explicit read problem. */
public record AdapterSample(AdapterInfo adapter, Optional<CounterReading> counters, String error) {
    public AdapterSample {
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(counters);
        error = Objects.requireNonNullElse(error, "");
        if (counters.isPresent() && !error.isEmpty()) {
            throw new IllegalArgumentException("A failed sample cannot also contain counters");
        }
    }

    public static AdapterSample available(AdapterInfo adapter, CounterReading counters) {
        return new AdapterSample(adapter, Optional.of(counters), "");
    }

    public static AdapterSample unavailable(AdapterInfo adapter, String error) {
        return new AdapterSample(adapter, Optional.empty(), error);
    }
}
