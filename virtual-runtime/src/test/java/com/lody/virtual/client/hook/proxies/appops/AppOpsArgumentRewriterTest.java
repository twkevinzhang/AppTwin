package com.lody.virtual.client.hook.proxies.appops;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppOpsArgumentRewriterTest {
    @Test
    public void rewritesAndroid17CheckOperationForDeviceIdentity() {
        Object[] args = {42, 10005, "com.shopee.tw", "network", 0};

        AppOpsArgumentRewriter.rewrite(args, 1, 2, 11062, "org.maskaccounts");

        assertEquals(11062, args[1]);
        assertEquals("org.maskaccounts", args[2]);
        assertEquals("network", args[3]);
        assertEquals(0, args[4]);
    }

    @Test
    public void rewritesAndroid17StartOperationForDeviceIdentity() {
        Object[] args = {new Object(), 42, 10005, "com.shopee.tw", "camera", 0};

        AppOpsArgumentRewriter.rewrite(args, 2, 3, 11062, "org.maskaccounts");

        assertEquals(11062, args[2]);
        assertEquals("org.maskaccounts", args[3]);
        assertEquals("camera", args[4]);
        assertEquals(0, args[5]);
    }

    @Test
    public void ignoresMissingOrUnexpectedIdentityArguments() {
        Object[] args = {42, "not-a-uid"};

        AppOpsArgumentRewriter.rewrite(args, 1, 2, 11062, "org.maskaccounts");

        assertEquals("not-a-uid", args[1]);
    }
}
