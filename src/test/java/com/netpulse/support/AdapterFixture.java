package com.netpulse.support;

import com.netpulse.model.AdapterInfo;
import com.netpulse.model.AdapterInfo.Kind;
import com.netpulse.model.AdapterInfo.LinkStatus;
import com.netpulse.model.AdapterInfo.Medium;
import com.netpulse.model.AdapterSample;
import com.netpulse.model.CounterReading;

/** Synthetic metadata only; never queries the host's network interfaces. */
public final class AdapterFixture {
    public int index;
    public String name;
    public String mac = "02:12:34:56:78:90";
    public LinkStatus link = LinkStatus.UP;
    public Kind kind = Kind.ETHERNET;
    public Medium medium = Medium.ETHERNET;
    public boolean javaUp = true;
    public boolean loopback;
    public boolean virtual;
    public boolean pointToPoint;
    public boolean knownVirtualMac;
    public boolean connector = true;
    public boolean hasAddress = true;
    public boolean defaultRoute;

    public AdapterFixture(int index) {
        this.index = index;
        name = "adapter-" + index;
    }

    public AdapterInfo build() {
        return new AdapterInfo(new AdapterInfo.Id(index, name, mac), "Test " + name,
                link, kind, medium, javaUp, loopback, virtual, pointToPoint,
                knownVirtualMac, connector, hasAddress, defaultRoute);
    }

    public AdapterSample reading(long received, long transmitted, long nanos) {
        return AdapterSample.available(build(), new CounterReading(received, transmitted, nanos));
    }
}
