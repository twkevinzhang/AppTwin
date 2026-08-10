package com.lody.virtual.client.hook.proxies.content;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContentNotifyChangeCompatTest {

    @Test
    public void findsTargetSdkWhenCallingPackageTrailsAndroid12NotifyArguments() {
        Object[] arguments = new Object[]{
                "content://guest.provider/items",
                new Object(),
                false,
                0,
                2,
                31,
                "com.example.guest"
        };

        assertEquals(
                5,
                MethodProxies.NotifyChange.findTargetSdkArgumentIndex(arguments, 31));
    }

    @Test
    public void doesNotTreatAnotherIntegerAsTheTargetSdk() {
        Object[] arguments = new Object[]{"uri", 0, 2, "com.example.guest"};

        assertEquals(
                -1,
                MethodProxies.NotifyChange.findTargetSdkArgumentIndex(arguments, 31));
    }

    @Test
    public void findsTargetSdkInRegisterObserverArguments() {
        Object[] arguments = new Object[]{
                "content://guest.provider/items",
                true,
                new Object(),
                2,
                31,
                "com.example.guest"
        };

        assertEquals(4, MethodProxies.findTargetSdkArgumentIndex(arguments, 31));
    }
}
