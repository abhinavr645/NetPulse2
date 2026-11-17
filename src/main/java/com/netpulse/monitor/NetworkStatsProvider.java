package com.netpulse.monitor;

import java.io.IOException;
import java.util.List;

import com.netpulse.model.AdapterSample;

/** Blocking source of fresh, detached adapter samples. Call only from a worker. */
@FunctionalInterface
public interface NetworkStatsProvider {
    List<AdapterSample> read() throws IOException;
}
