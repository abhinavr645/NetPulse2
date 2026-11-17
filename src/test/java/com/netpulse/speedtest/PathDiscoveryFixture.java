package com.netpulse.speedtest;

import java.util.Optional;

/** Separate JVM verifies the public locator's inherited PATH, without executing a CLI. */
public final class PathDiscoveryFixture {
    private PathDiscoveryFixture() { }

    public static void main(String[] args) throws Exception {
        System.out.println(new SpeedTestExecutableLocator(Optional.empty()).locate());
    }
}
