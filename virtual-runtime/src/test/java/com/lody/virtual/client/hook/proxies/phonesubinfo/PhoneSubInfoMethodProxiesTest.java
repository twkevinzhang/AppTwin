package com.lody.virtual.client.hook.proxies.phonesubinfo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PhoneSubInfoMethodProxiesTest {
    @Test
    public void virtualizesModernSubscriberIdDescriptors() {
        assertEquals("getSubscriberId",
                new MethodProxies.GetSubscriberId().getMethodName());
        assertEquals("getSubscriberIdForSubscriber",
                new MethodProxies.GetSubscriberIdForSubscriber().getMethodName());
    }
}
