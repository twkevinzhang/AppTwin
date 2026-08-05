package com.lody.virtual.client.hook.proxies.am;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
}
