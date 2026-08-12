package com.lody.virtual.client.hook.proxies.nsd;

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

public class NsdManagerStubTest {

    @Test
    public void bindsConnectCallingPackageIdentityHook() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new NsdManagerStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("connect"));
    }

    @Test
    public void connectRewritesGuestPackageWithoutChangingAttributionTag() {
        Object callback = new Object();
        Object[] args = {callback, true, "com.xiaomi.smarthome", "mi-home-discovery"};

        int replaced = NsdManagerStub.ReplaceCallingPackageMethodProxy.replaceGuestPackage(
                args, "com.xiaomi.smarthome", "org.apptwin");

        assertEquals(1, replaced);
        assertArrayEquals(
                new Object[]{callback, true, "org.apptwin", "mi-home-discovery"}, args);
    }

    @Test
    public void connectLeavesUnrelatedStringsUntouched() {
        Object[] args = {new Object(), true, "other-package", "mi-home-discovery"};

        assertEquals(0, NsdManagerStub.ReplaceCallingPackageMethodProxy.replaceGuestPackage(
                args, "com.xiaomi.smarthome", "org.apptwin"));
        assertEquals("other-package", args[2]);
        assertEquals("mi-home-discovery", args[3]);
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
