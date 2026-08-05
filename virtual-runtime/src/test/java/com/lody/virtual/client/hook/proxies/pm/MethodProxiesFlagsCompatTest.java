package com.lody.virtual.client.hook.proxies.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class MethodProxiesFlagsCompatTest {
    @Test
    public void acceptsLegacyIntegerAndModernLongFlags() {
        assertEquals(0x12345678, MethodProxies.packageManagerFlagsToInt(0x12345678));
        assertEquals(0x12345678, MethodProxies.packageManagerFlagsToInt(0x12345678L));
        assertEquals(Integer.MIN_VALUE,
                MethodProxies.packageManagerFlagsToInt(0x80000000L));
        assertEquals(-1, MethodProxies.packageManagerFlagsToInt(-1L));
    }

    @Test
    public void ignoresModernFlagsOutsideLegacyIntRange() {
        assertEquals(0, MethodProxies.packageManagerFlagsToInt(0x1_0000_0000L));
        assertEquals(0x04000000,
                MethodProxies.packageManagerFlagsToInt(0x1_0400_0000L));
        assertEquals(Integer.MAX_VALUE,
                MethodProxies.packageManagerFlagsToInt(-0x8000_0001L));
    }

    @Test
    public void rejectsNonIntegralArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> MethodProxies.packageManagerFlagsToInt(1.0d));
        assertThrows(IllegalArgumentException.class,
                () -> MethodProxies.packageManagerFlagsToInt(null));
    }
}
