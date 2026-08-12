package com.lody.virtual.client.hook.proxies.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class WifiManagerStubTest {

    @Test
    public void bindsDhcpInfoCallingPackageIdentityHook() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new WifiManagerStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("getDhcpInfo"));
    }

    @Test
    public void bindsConnectionInfoCallingPackageIdentityHook() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new WifiManagerStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("getConnectionInfo"));
    }

    @Test
    public void connectionInfoRewritesGuestPackageWithoutChangingAttributionTag() {
        Object[] args = {"com.xiaomi.smarthome", "mi-home-local-otu"};

        int replaced = WifiManagerStub.rewriteConnectionInfoPackage(
                args, "com.xiaomi.smarthome", "org.apptwin");

        assertEquals(1, replaced);
        assertArrayEquals(new Object[]{"org.apptwin", "mi-home-local-otu"}, args);
    }

    @Test
    public void connectionInfoLeavesUnrelatedStringsUntouched() {
        Object[] args = {"other-package", "mi-home-local-otu"};

        int replaced = WifiManagerStub.rewriteConnectionInfoPackage(
                args, "com.xiaomi.smarthome", "org.apptwin");

        assertEquals(0, replaced);
        assertArrayEquals(new Object[]{"other-package", "mi-home-local-otu"}, args);
    }

    /** Records hooks without requiring a live Android Binder. */
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
