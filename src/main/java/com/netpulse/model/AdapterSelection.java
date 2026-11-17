package com.netpulse.model;

import java.util.Objects;

public record AdapterSelection(AdapterInfo adapter, Identification identification) {
    public AdapterSelection {
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(identification);
    }

    /** Even positive driver evidence is not a guarantee of physical hardware. */
    public enum Identification { LIKELY_PHYSICAL, AMBIGUOUS }
}
