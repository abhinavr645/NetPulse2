package com.netpulse.monitor;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterInfo.Kind;
import com.netpulse.model.AdapterInfo.LinkStatus;
import com.netpulse.model.AdapterInfo.Medium;
import com.netpulse.model.AdapterSample;
import com.netpulse.model.CounterReading;
import oshi.SystemInfo;
import oshi.hardware.NetworkIF;

/** Windows metadata normalization and counter access are confined to this class. */
public final class OshiNetworkStatsProvider implements NetworkStatsProvider {
    private static final System.Logger LOG = System.getLogger(OshiNetworkStatsProvider.class.getName());
    private final SystemInfo systemInfo = new SystemInfo();

    @Override
    public List<AdapterSample> read() throws IOException {
        try {
            Set<Integer> defaultRoutes = defaultRouteIndices();
            // OSHI 7.8.0 creates fresh Windows NetworkIF objects and initializes
            // their counters here. Do not immediately poll those objects again.
            List<NetworkIF> interfaces = systemInfo.getHardware().getNetworkIFs(true);
            long sampledAt = System.nanoTime();
            List<AdapterSample> samples = new ArrayList<>(interfaces.size());
            for (NetworkIF network : interfaces) {
                samples.add(detach(network, sampledAt, defaultRoutes));
            }
            return List.copyOf(samples);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("OSHI could not read network interfaces", failure);
        }
    }

    private AdapterSample detach(NetworkIF network, long sampledAt, Set<Integer> defaultRoutes) {
        NetworkInterface javaInterface = network.queryNetworkInterface();
        boolean up = false;
        boolean loopback = false;
        boolean pointToPoint = false;
        boolean hasAddress = false;
        String metadataError = "";
        try {
            up = javaInterface.isUp();
            loopback = javaInterface.isLoopback();
            pointToPoint = javaInterface.isPointToPoint();
            hasAddress = javaInterface.inetAddresses().anyMatch(address ->
                    !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress());
        } catch (SocketException failure) {
            metadataError = "Java interface metadata is unavailable: " + failure.getMessage();
        }

        LinkStatus status = switch (network.getIfOperStatus()) {
            case UP -> up ? LinkStatus.UP : LinkStatus.DOWN;
            case UNKNOWN -> LinkStatus.UNKNOWN;
            default -> LinkStatus.DOWN;
        };
        if (!metadataError.isEmpty()) {
            status = LinkStatus.UNKNOWN;
        }

        AdapterInfo info = new AdapterInfo(
                new AdapterInfo.Id(network.getIndex(), network.getName(), network.getMacaddr()),
                network.getDisplayName(), status, kind(network.getIfType()),
                medium(network.getNdisPhysicalMediumType()), up, loopback,
                javaInterface.isVirtual() || knownSoftwareDriver(network.getDisplayName()),
                pointToPoint, network.isKnownVmMacAddr(), network.isConnectorPresent(),
                hasAddress, defaultRoutes.contains(network.getIndex()));
        if (!metadataError.isEmpty()) {
            return AdapterSample.unavailable(info, metadataError);
        }
        if (network.getTimeStamp() <= 0) {
            return AdapterSample.unavailable(info, "OSHI did not initialize fresh interface counters");
        }
        long received = network.getBytesRecv();
        long transmitted = network.getBytesSent();
        if (received < 0 || transmitted < 0) {
            return AdapterSample.unavailable(info, "OSHI returned invalid negative counters");
        }
        return AdapterSample.available(info, new CounterReading(received, transmitted, sampledAt));
    }

    private Set<Integer> defaultRouteIndices() {
        try {
            return systemInfo.getOperatingSystem().getNetworkParams().getRoutes().stream()
                    .filter(route -> route.getPrefixLength() == 0 && route.getInterfaceIndex() > 0)
                    .map(route -> route.getInterfaceIndex()).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException | LinkageError failure) {
            // Routing is supporting evidence; a failed route lookup must not stop sampling.
            LOG.log(System.Logger.Level.DEBUG, "Routing metadata unavailable", failure);
            return Set.of();
        }
    }

    private static Kind kind(int type) {
        return switch (type) {
            case 6 -> Kind.ETHERNET;
            case 71 -> Kind.WIFI;
            case 24 -> Kind.LOOPBACK;
            case 131 -> Kind.TUNNEL;
            default -> Kind.OTHER;
        };
    }

    private static Medium medium(int physicalMedium) {
        return switch (physicalMedium) {
            case 0 -> Medium.UNKNOWN;
            case 1, 9 -> Medium.WIFI;
            case 14 -> Medium.ETHERNET;
            default -> Medium.OTHER;
        };
    }

    static boolean knownSoftwareDriver(String displayName) {
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        return name.contains("wi-fi direct virtual adapter") || name.contains("hyper-v virtual")
                || name.contains("vmware virtual") || name.contains("virtualbox")
                || name.contains("tap-windows") || name.contains("wintun")
                || name.contains("wireguard tunnel") || name.contains("openvpn data channel")
                || name.startsWith("wan miniport (")
                || name.contains("microsoft kernel debug network adapter")
                || name.contains("-wfp native mac layer lightweight filter-")
                || name.contains("-wfp 802.3 mac layer lightweight filter-")
                || name.contains("-qos packet scheduler-")
                || name.contains("-virtual wifi filter driver-")
                || name.contains("-native wifi filter driver-");
    }
}
