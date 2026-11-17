package com.netpulse.monitor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OshiNetworkStatsProviderTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Intel(R) Wi-Fi 6 AX201 160MHz-WFP Native MAC Layer LightWeight Filter-0000",
            "Intel(R) Wi-Fi 6 AX201 160MHz-Virtual WiFi Filter Driver-0000",
            "Intel(R) Wi-Fi 6 AX201 160MHz-Native WiFi Filter Driver-0000",
            "Realtek PCIe GbE Family Controller-QoS Packet Scheduler-0000",
            "Realtek PCIe GbE Family Controller-WFP 802.3 MAC Layer LightWeight Filter-0000",
            "WAN Miniport (IP)", "Microsoft Wi-Fi Direct Virtual Adapter #2",
            "Hyper-V Virtual Ethernet Adapter", "TAP-Windows Adapter V9", "WireGuard Tunnel"
    })
    void recognizesKnownWindowsSoftwareLayersWithoutNetworkAccess(String displayName) {
        assertTrue(OshiNetworkStatsProvider.knownSoftwareDriver(displayName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Intel(R) Wi-Fi 6 AX201 160MHz", "Realtek PCIe GbE Family Controller",
            "VPN Office Ethernet", "Ethernet", "Wi-Fi"})
    void genericNamesAndPhysicalDriversAreNotExcludedByLooseKeywords(String displayName) {
        assertFalse(OshiNetworkStatsProvider.knownSoftwareDriver(displayName));
    }
}
