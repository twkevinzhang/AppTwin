package com.lody.virtual.client.hook.proxies.deviceidle;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.lang.reflect.Method;

public class SelfBatteryWhitelistQueryTest {
    private static final String GUEST = "jp.naver.line.android";
    private static final String HOST = "org.apptwin";
    private static final String[] METHODS = {
            "isPowerSaveWhitelistApp", "isPowerSaveWhitelistExceptIdleApp"
    };

    public static class Service {
        String queriedPackage;
        boolean whitelisted;

        public boolean isPowerSaveWhitelistApp(String packageName) {
            queriedPackage = packageName;
            return whitelisted;
        }

        public boolean isPowerSaveWhitelistExceptIdleApp(String packageName) {
            return isPowerSaveWhitelistApp(packageName);
        }
    }

    @Test
    public void selfQueryUsesHostAndPreservesBothRealStates() throws Throwable {
        for (String name : METHODS) {
            for (boolean state : new boolean[] {false, true}) {
                assertQuery(name, GUEST, GUEST, HOST, HOST, state);
            }
        }
    }

    @Test
    public void unrelatedHostAndNullQueriesAreUnchanged() throws Throwable {
        for (String name : METHODS) {
            for (String target : new String[] {HOST, "com.example.other", null}) {
                for (boolean state : new boolean[] {false, true}) {
                    assertQuery(name, target, GUEST, HOST, target, state);
                }
            }
        }
    }

    @Test
    public void missingIdentityDoesNotRewrite() throws Throwable {
        for (String name : METHODS) {
            assertQuery(name, GUEST, null, HOST, GUEST, false);
            assertQuery(name, GUEST, GUEST, null, GUEST, false);
        }
    }

    private void assertQuery(String name, String target, final String guest, final String host,
                             String expectedTarget, boolean state) throws Throwable {
        SelfBatteryWhitelistQuery proxy = new SelfBatteryWhitelistQuery(name) {
            @Override protected String currentPackage() { return guest; }
            @Override protected String hostPackage() { return host; }
        };
        Service service = new Service();
        service.whitelisted = state;
        Method method = Service.class.getMethod(name, String.class);
        assertEquals(name, proxy.getMethodName());
        assertEquals(state, proxy.call(service, method, new Object[] {target}));
        assertEquals(expectedTarget, service.queriedPackage);
    }
}
