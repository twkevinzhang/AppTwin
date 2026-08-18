package com.lody.virtual.client.hook.proxies.am;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BindServiceInstanceRoutingTest {

    @Test
    public void android17EntryPointReusesVirtualBindRouting() {
        assertTrue(MethodProxies.BindService.class.isAssignableFrom(
                MethodProxies.BindServiceInstance.class));
        assertEquals("bindServiceInstance",
                new MethodProxies.BindServiceInstance().getMethodName());
    }

    @Test
    public void acceptsLegacyIntegerAndModernLongFlags() {
        assertEquals(4225, MethodProxies.BindService.serviceBindFlags(4225));
        assertEquals(4225, MethodProxies.BindService.serviceBindFlags(4225L));
    }

    @Test
    public void physicalFallbackUsesHostAsAndroid15CallingPackage() {
        Object[] args = {
                new Object(), new Object(), new Object(), "resolved/type", new Object(), 1L,
                "webview_instance", "com.google.android.gms", 0
        };

        assertEquals("com.google.android.gms",
                MethodProxies.BindService.replacePhysicalServiceCaller(args, "org.apptwin"));
        assertEquals("resolved/type", args[3]);
        assertEquals("webview_instance", args[6]);
        assertEquals("org.apptwin", args[7]);
    }

    @Test
    public void physicalFallbackWithoutPackageIsLeftUntouched() {
        Object[] args = {new Object(), 1L, 0};

        assertNull(MethodProxies.BindService.replacePhysicalServiceCaller(args, "org.apptwin"));
        assertEquals(3, args.length);
    }

    @Test
    public void isolatedBindExtractsFrameworkInstanceName() {
        Object[] args = {
                new Object(), new Object(), new Object(), "resolved/type", new Object(), 1L,
                "tab-instance-7", "org.mozilla.firefox", 0
        };

        assertEquals("tab-instance-7",
                MethodProxies.BindIsolatedService.instanceName(args));
    }

    @Test
    public void ordinaryOrEmptyIsolatedBindHasNoNamedInstance() {
        assertNull(MethodProxies.BindIsolatedService.instanceName(
                new Object[]{new Object(), new Object(), new Object(), null, new Object(), 1}));
        assertNull(MethodProxies.BindIsolatedService.instanceName(
                new Object[]{new Object(), new Object(), new Object(), null, new Object(), 1, ""}));
    }
}
