package com.lody.virtual.client.hook.proxies.isub;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EmptyActiveSubIdListMethodProxyTest {
    @Test
    public void returnsNoPhysicalSubscriptions() {
        EmptyActiveSubIdListMethodProxy proxy = new EmptyActiveSubIdListMethodProxy();
        assertEquals("getActiveSubIdList", proxy.getMethodName());
        assertArrayEquals(new int[0], (int[]) proxy.call(null, null));
    }
}
