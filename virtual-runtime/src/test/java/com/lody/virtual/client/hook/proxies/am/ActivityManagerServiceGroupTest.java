package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ActivityManagerServiceGroupTest {
    @Test
    public void virtualServiceGroupUpdateIsHandledWithoutCallingPhysicalAms() throws Throwable {
        ActivityManagerStub.IgnoreVirtualServiceGroupUpdate proxy =
                new ActivityManagerStub.IgnoreVirtualServiceGroupUpdate();

        assertEquals("updateServiceGroup", proxy.getMethodName());
        assertNull(proxy.call(null, null, new Object(), 1, 2));
    }
}
