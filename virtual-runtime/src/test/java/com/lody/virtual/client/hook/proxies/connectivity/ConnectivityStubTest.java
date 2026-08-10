package com.lody.virtual.client.hook.proxies.connectivity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ConnectivityStubTest {

    @Test
    public void bindsConnectivityMethodsThatCarryCallingPackageIdentity() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new ConnectivityStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("getDefaultNetworkCapabilitiesForUser"));
        assertNotNull(invocationStub.getMethodProxy("getNetworkCapabilities"));
        assertNotNull(invocationStub.getMethodProxy("requestNetwork"));
        assertNotNull(invocationStub.getMethodProxy("pendingRequestForNetwork"));
        assertNotNull(invocationStub.getMethodProxy("listenForNetwork"));
        assertNotNull(invocationStub.getMethodProxy("pendingListenForNetwork"));
        assertNull(invocationStub.getMethodProxy("getActiveNetwork"));
    }

    @Test
    public void rewritesGuestPackageWithoutChangingAttributionTag() {
        Object network = new Object();
        Object[] args = {network, "com.google.android.apps.docs", "drive-attribution"};

        int replaced = ConnectivityStub.ReplaceCallingPackageMethodProxy.replaceGuestPackage(
                args, "com.google.android.apps.docs", "org.apptwin");

        assertEquals(1, replaced);
        assertArrayEquals(new Object[]{network, "org.apptwin", "drive-attribution"}, args);
    }

    @Test
    public void leavesUnrelatedStringArgumentsUntouched() {
        Object[] args = {new Object(), "other-package", "drive-attribution"};

        assertEquals(0, ConnectivityStub.ReplaceCallingPackageMethodProxy.replaceGuestPackage(
                args, "com.google.android.apps.docs", "org.apptwin"));
        assertEquals("other-package", args[1]);
        assertEquals("drive-attribution", args[2]);
    }

    /** Avoids Android framework utility stubs in local JVM tests. */
    private static final class TestBinderInvocationStub extends BinderInvocationStub {
        private Map<String, MethodProxy> hooks;

        TestBinderInvocationStub() {
            super(new IInterface() {
                @Override
                public IBinder asBinder() {
                    return null;
                }
            });
            hooks = new HashMap<>();
        }

        @Override
        public MethodProxy addMethodProxy(MethodProxy methodProxy) {
            if (hooks != null) {
                hooks.put(methodProxy.getMethodName(), methodProxy);
            }
            return methodProxy;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <H extends MethodProxy> H getMethodProxy(String name) {
            return (H) hooks.get(name);
        }
    }
}
