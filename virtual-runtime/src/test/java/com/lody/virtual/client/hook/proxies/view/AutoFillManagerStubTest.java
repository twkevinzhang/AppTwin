package com.lody.virtual.client.hook.proxies.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class AutoFillManagerStubTest {

    @Test
    public void bindsAutofillHooksBeforeCachedRebindAndKeepsThemWhenRebindFails() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new AutoFillManagerStub(invocationStub);

        assertAutofillHooksPresent(invocationStub);

        // A manager without mService models Android's early cached-instance rebind failure.
        // Since onBindMethods() ran during construction, the Binder hooks remain registered.
        assertThrows(NoSuchFieldException.class,
                () -> AutoFillManagerStub.rebindCachedService(new Object(), new Object()));

        assertAutofillHooksPresent(invocationStub);
    }

    @Test
    public void rebindsCachedServiceWhenFieldIsAvailable() throws Exception {
        FakeAutoFillManager manager = new FakeAutoFillManager();
        Object proxy = new Object();

        AutoFillManagerStub.rebindCachedService(manager, proxy);

        assertEquals(proxy, manager.mService);
    }

    @Test
    public void hostComponentIdentityKeepsOriginalClassName() {
        AutoFillManagerStub.ComponentIdentity identity =
                AutoFillManagerStub.componentIdentityForHost(
                        "org.maskaccounts",
                        "jp.naver.line.android.activity.login.SecondaryDeviceLoginActivity");

        assertEquals("org.maskaccounts", identity.packageName);
        assertEquals("jp.naver.line.android.activity.login.SecondaryDeviceLoginActivity",
                identity.className);
    }

    private static void assertAutofillHooksPresent(BinderInvocationStub invocationStub) {
        assertNotNull(invocationStub.getMethodProxy("startSession"));
        assertNotNull(invocationStub.getMethodProxy("updateOrRestartSession"));
        assertNotNull(invocationStub.getMethodProxy("isServiceEnabled"));
        assertNull(invocationStub.getMethodProxy("unrelatedMethod"));
    }

    private static final class FakeAutoFillManager {
        private Object mService;
    }

    /** Avoids calling Android framework utility stubs from local JVM tests. */
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
            // BinderInvocationStub adds asBinder while this subclass is still being constructed.
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
