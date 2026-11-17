package com.netpulse.model;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** A detached snapshot of adapter metadata; contains no live OSHI objects. */
public record AdapterInfo(Id id, String displayName, LinkStatus linkStatus,
        Kind kind, Medium medium, boolean javaUp, boolean loopback,
        boolean virtual, boolean pointToPoint, boolean knownVirtualMac,
        boolean connectorPresent, boolean hasAddress, boolean defaultRoute) {

    public AdapterInfo {
        Objects.requireNonNull(id);
        displayName = Objects.requireNonNullElse(displayName, id.name());
        Objects.requireNonNull(linkStatus);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(medium);
    }

    /** Includes metadata that can change when an interface index is reused. */
    public record Id(int index, String name, String macAddress) {
        public Id {
            Objects.requireNonNull(name);
            macAddress = Objects.requireNonNullElse(macAddress, "")
                    .replace('-', ':').toLowerCase(Locale.ROOT);
        }

        public boolean hasUsableMac() {
            try {
                byte[] bytes = HexFormat.ofDelimiter(":").parseHex(macAddress);
                if (bytes.length != 6 || (bytes[0] & 1) != 0) {
                    return false;
                }
                for (byte value : bytes) {
                    if (value != 0) {
                        return true;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown or malformed hardware addresses are not physical evidence.
            }
            return false;
        }
    }

    public enum LinkStatus { UP, DOWN, UNKNOWN }
    public enum Kind { WIFI, ETHERNET, LOOPBACK, TUNNEL, OTHER }
    public enum Medium { WIFI, ETHERNET, UNKNOWN, OTHER }
}
