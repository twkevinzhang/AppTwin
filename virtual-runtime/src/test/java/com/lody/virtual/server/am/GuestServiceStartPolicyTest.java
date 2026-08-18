package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ServiceInfo;

import org.junit.Test;

public class GuestServiceStartPolicyTest {
    @Test
    public void serializesFirefoxGeckoContentAndGpuBindings() {
        assertTrue(GuestServiceStartPolicy.shouldSerializeBinding(service(
                "org.mozilla.firefox",
                "org.mozilla.gecko.process.GeckoChildProcessServices$tab21")));
        assertTrue(GuestServiceStartPolicy.shouldSerializeBinding(service(
                "org.mozilla.firefox",
                "org.mozilla.gecko.process.GeckoChildProcessServices$gpu")));
    }

    @Test
    public void preservesFailFastBehaviorForUnrelatedGuestServices() {
        assertFalse(GuestServiceStartPolicy.shouldSerializeBinding(service(
                "org.mozilla.firefox", "org.mozilla.fenix.telemetry.TelemetryService")));
        assertFalse(GuestServiceStartPolicy.shouldSerializeBinding(service(
                "com.example", "org.mozilla.gecko.process.GeckoChildProcessServices$gpu")));
        assertFalse(GuestServiceStartPolicy.shouldSerializeBinding(null));
    }

    private static ServiceInfo service(String packageName, String name) {
        ServiceInfo info = new ServiceInfo();
        info.packageName = packageName;
        info.name = name;
        return info;
    }
}
