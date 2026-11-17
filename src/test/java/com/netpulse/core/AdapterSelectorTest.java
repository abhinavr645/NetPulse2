package com.netpulse.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterInfo.Kind;
import com.netpulse.model.AdapterInfo.LinkStatus;
import com.netpulse.model.AdapterInfo.Medium;
import com.netpulse.model.AdapterSelection.Identification;
import com.netpulse.support.AdapterFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdapterSelectorTest {
    private final AdapterSelector selector = new AdapterSelector();

    @Test
    void strongerPhysicalEvidenceWinsEvenWhenAnAmbiguousAdapterHasTheDefaultRoute() {
        AdapterFixture uncertain = new AdapterFixture(1);
        uncertain.medium = Medium.UNKNOWN;
        uncertain.connector = false;
        uncertain.defaultRoute = true;
        AdapterInfo physical = new AdapterFixture(10).build();
        assertEquals(physical, selector.select(List.of(uncertain.build(), physical), null).orElseThrow().adapter());
    }

    @Test
    void connectorEvidenceOutranksRouteHintsWhenMediaTypesAreIdentical() {
        AdapterFixture proxy = new AdapterFixture(1);
        proxy.connector = false;
        proxy.defaultRoute = true;
        AdapterInfo physical = new AdapterFixture(10).build();
        assertEquals(physical, selector.select(List.of(proxy.build(), physical), null).orElseThrow().adapter());
    }

    @Test
    void activeWifiAndEthernetAreBothEligible() {
        AdapterFixture wifi = new AdapterFixture(2);
        wifi.kind = Kind.WIFI;
        wifi.medium = Medium.WIFI;
        assertEquals(Identification.LIKELY_PHYSICAL,
                selector.select(List.of(wifi.build()), null).orElseThrow().identification());
        assertTrue(selector.select(List.of(new AdapterFixture(1).build()), null).isPresent());
    }

    @ParameterizedTest
    @MethodSource("ineligibleMetadata")
    void excludesDisconnectedAndIdentifiableSoftwareInterfaces(Consumer<AdapterFixture> change) {
        AdapterFixture fixture = new AdapterFixture(1);
        change.accept(fixture);
        assertTrue(selector.select(List.of(fixture.build()), null).isEmpty());
    }

    static Stream<Consumer<AdapterFixture>> ineligibleMetadata() {
        return Stream.of(a -> a.loopback = true, a -> a.kind = Kind.LOOPBACK,
                a -> a.kind = Kind.TUNNEL, a -> a.virtual = true,
                a -> a.knownVirtualMac = true, a -> a.pointToPoint = true,
                a -> a.javaUp = false, a -> a.link = LinkStatus.DOWN,
                a -> a.link = LinkStatus.UNKNOWN, a -> a.hasAddress = false,
                a -> a.medium = Medium.OTHER, a -> { a.kind = Kind.OTHER; a.medium = Medium.UNKNOWN; });
    }

    @Test
    void aVpnDefaultRouteCannotDisplaceThePhysicalAdapter() {
        AdapterInfo physical = new AdapterFixture(8).build();
        AdapterFixture vpn = new AdapterFixture(1);
        vpn.defaultRoute = true;
        vpn.pointToPoint = true;
        assertEquals(physical, selector.select(List.of(vpn.build(), physical), physical.id()).orElseThrow().adapter());
    }

    @Test
    void defaultRouteIsSupportingEvidenceBetweenEquallyPhysicalCandidates() {
        AdapterInfo first = new AdapterFixture(1).build();
        AdapterFixture routed = new AdapterFixture(8);
        routed.defaultRoute = true;
        assertEquals(routed.build(), selector.select(List.of(first, routed.build()), null).orElseThrow().adapter());
    }

    @Test
    void fallbackIsDeterministicRegardlessOfEnumerationOrder() {
        AdapterInfo lower = new AdapterFixture(2).build();
        AdapterInfo higher = new AdapterFixture(9).build();
        assertEquals(lower, selector.select(List.of(lower, higher), null).orElseThrow().adapter());
        assertEquals(lower, selector.select(List.of(higher, lower), null).orElseThrow().adapter());
    }

    @Test
    void preservesAnEligibleSelectionEvenIfAnotherAdapterGainsARoute() {
        AdapterInfo selected = new AdapterFixture(9).build();
        AdapterFixture alternative = new AdapterFixture(1);
        alternative.defaultRoute = true;
        assertEquals(selected, selector.select(List.of(alternative.build(), selected), selected.id()).orElseThrow().adapter());
    }

    @Test
    void switchesWhenTheSelectedAdapterDisconnects() {
        AdapterFixture old = new AdapterFixture(1);
        AdapterInfo.Id oldId = old.build().id();
        old.link = LinkStatus.DOWN;
        AdapterInfo replacement = new AdapterFixture(2).build();
        assertEquals(replacement, selector.select(List.of(old.build(), replacement), oldId).orElseThrow().adapter());
    }

    @Test
    void exposesAmbiguityInsteadOfClaimingPhysicalIdentification() {
        AdapterFixture uncertain = new AdapterFixture(1);
        uncertain.medium = Medium.UNKNOWN;
        uncertain.connector = false;
        assertEquals(Identification.AMBIGUOUS,
                selector.select(List.of(uncertain.build()), null).orElseThrow().identification());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:12:34:56:78:90"})
    void unusableMacAddressesAreNotPhysicalEvidence(String mac) {
        AdapterFixture fixture = new AdapterFixture(1);
        fixture.mac = mac;
        assertTrue(selector.select(List.of(fixture.build()), null).isEmpty());
    }

    @Test
    void randomizedUnicastWifiMacsRemainEligible() {
        AdapterFixture fixture = new AdapterFixture(1);
        fixture.mac = "DA-12-34-56-78-90";
        fixture.kind = Kind.WIFI;
        fixture.medium = Medium.WIFI;
        assertTrue(selector.select(List.of(fixture.build()), null).isPresent());
    }

    @Test
    void physicalMediumCanResolveAnUnknownInterfaceType() {
        AdapterFixture fixture = new AdapterFixture(1);
        fixture.kind = Kind.OTHER;
        assertEquals(Identification.LIKELY_PHYSICAL,
                selector.select(List.of(fixture.build()), null).orElseThrow().identification());
    }
}
