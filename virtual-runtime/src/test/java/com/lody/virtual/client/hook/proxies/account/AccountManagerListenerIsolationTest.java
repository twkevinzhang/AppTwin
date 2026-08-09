package com.lody.virtual.client.hook.proxies.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.lang.reflect.Method;

public class AccountManagerListenerIsolationTest {

    @Test
    public void registerListenerNeverCallsPhysicalAccountManager() throws Throwable {
        PhysicalAccountManager physical = new PhysicalAccountManager();
        Method method = PhysicalAccountManager.class.getMethod(
                "registerAccountListener", String[].class, String.class);
        AccountManagerStub.RegisterAccountListener proxy =
                new AccountManagerStub.RegisterAccountListener();

        assertEquals("registerAccountListener", proxy.getMethodName());
        assertNull(proxy.call(physical, method, null, "com.google.android.apps.maps"));
        assertNull(proxy.call(physical, method, new String[0], "com.google.android.apps.maps"));
        assertNull(proxy.call(physical, method,
                new String[]{"com.google", "com.example"},
                "com.google.android.apps.maps"));
        assertEquals(0, physical.registerCalls);
    }

    @Test
    public void unregisterListenerNeverCallsPhysicalAccountManager() throws Throwable {
        PhysicalAccountManager physical = new PhysicalAccountManager();
        Method method = PhysicalAccountManager.class.getMethod(
                "unregisterAccountListener", String[].class, String.class);
        AccountManagerStub.UnregisterAccountListener proxy =
                new AccountManagerStub.UnregisterAccountListener();

        assertEquals("unregisterAccountListener", proxy.getMethodName());
        assertNull(proxy.call(physical, method, null, "com.google.android.apps.maps"));
        assertNull(proxy.call(physical, method, new String[0], "com.google.android.apps.maps"));
        assertNull(proxy.call(physical, method,
                new String[]{"com.google", "com.example"},
                "com.google.android.apps.maps"));
        assertEquals(0, physical.unregisterCalls);
    }

    public static final class PhysicalAccountManager {
        int registerCalls;
        int unregisterCalls;

        public void registerAccountListener(String[] accountTypes, String opPackageName) {
            registerCalls++;
        }

        public void unregisterAccountListener(String[] accountTypes, String opPackageName) {
            unregisterCalls++;
        }
    }
}
