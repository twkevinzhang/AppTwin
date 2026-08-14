package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MicrogServiceInfoBroadcastCompatTest {

    @Test
    public void android17ServiceInfoResponseIsMadeUnordered() {
        Object[] args = {new Object(), new Object(), null, new Object(), true, true, 0};

        assertTrue(MethodProxies.BroadcastIntent.makeMicrogServiceInfoExchangeUnordered(
                37, "org.microg.gms.gcm.SERVICE_INFO_RESPONSE", args, 1));
        assertFalse((Boolean) args[4]);
        assertTrue((Boolean) args[5]);
    }

    @Test
    public void unrelatedBroadcastKeepsOrderedFlag() {
        Object[] args = {new Object(), new Object(), true, false};

        assertFalse(MethodProxies.BroadcastIntent.makeMicrogServiceInfoExchangeUnordered(
                37, "com.google.android.c2dm.intent.RECEIVE", args, 1));
        assertTrue((Boolean) args[2]);
    }

    @Test
    public void olderAndroidKeepsServiceInfoResponseOrdered() {
        Object[] args = {new Object(), new Object(), true, false};

        assertFalse(MethodProxies.BroadcastIntent.makeMicrogServiceInfoExchangeUnordered(
                36, "org.microg.gms.gcm.SERVICE_INFO_RESPONSE", args, 1));
        assertTrue((Boolean) args[2]);
    }

}
