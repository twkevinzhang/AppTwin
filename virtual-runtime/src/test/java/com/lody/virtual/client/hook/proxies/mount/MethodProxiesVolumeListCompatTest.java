package com.lody.virtual.client.hook.proxies.mount;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MethodProxiesVolumeListCompatTest {
    private static final int HOST_UID = 11060;

    @Test
    public void rewritesLegacyUidDescriptorToHostUid() {
        assertEquals(HOST_UID,
                MethodProxies.volumeListCallerIdForSdk(HOST_UID, 32));
    }

    @Test
    public void derivesOperatingSystemUserIdStartingWithAndroid13() {
        assertEquals(0,
                MethodProxies.volumeListCallerIdForSdk(HOST_UID, 33));
        assertEquals(0,
                MethodProxies.volumeListCallerIdForSdk(HOST_UID, 37));
        assertEquals(1,
                MethodProxies.volumeListCallerIdForSdk(111060, 37));
    }
}
