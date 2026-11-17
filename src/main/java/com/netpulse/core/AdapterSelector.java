package com.netpulse.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterInfo.Kind;
import com.netpulse.model.AdapterInfo.LinkStatus;
import com.netpulse.model.AdapterInfo.Medium;
import com.netpulse.model.AdapterSelection;
import com.netpulse.model.AdapterSelection.Identification;

/** Deterministic selection over detached metadata; performs no system calls. */
public final class AdapterSelector {

    public Optional<AdapterSelection> select(Collection<AdapterInfo> adapters, AdapterInfo.Id current) {
        Optional<AdapterInfo> preserved = adapters.stream()
                .filter(this::eligible).filter(adapter -> adapter.id().equals(current)).findFirst();
        Optional<AdapterInfo> chosen = preserved.or(() -> adapters.stream().filter(this::eligible)
                .min(Comparator.comparing((AdapterInfo adapter) -> ambiguous(adapter))
                        .thenComparing(adapter -> !adapter.connectorPresent())
                        .thenComparing(adapter -> !adapter.defaultRoute())
                        .thenComparingInt(adapter -> adapter.id().index())
                        .thenComparing(adapter -> adapter.id().name())
                        .thenComparing(adapter -> adapter.id().macAddress())));
        return chosen.map(adapter -> new AdapterSelection(adapter,
                ambiguous(adapter) ? Identification.AMBIGUOUS : Identification.LIKELY_PHYSICAL));
    }

    private boolean eligible(AdapterInfo adapter) {
        return adapter.linkStatus() == LinkStatus.UP && adapter.javaUp() && adapter.hasAddress()
                && !adapter.loopback() && !adapter.virtual() && !adapter.pointToPoint()
                && !adapter.knownVirtualMac() && adapter.id().hasUsableMac()
                && adapter.kind() != Kind.LOOPBACK && adapter.kind() != Kind.TUNNEL
                && adapter.medium() != Medium.OTHER
                && (adapter.kind() == Kind.WIFI || adapter.kind() == Kind.ETHERNET
                    || adapter.medium() == Medium.WIFI || adapter.medium() == Medium.ETHERNET);
    }

    private boolean ambiguous(AdapterInfo adapter) {
        return adapter.medium() == Medium.UNKNOWN && !adapter.connectorPresent();
    }
}
