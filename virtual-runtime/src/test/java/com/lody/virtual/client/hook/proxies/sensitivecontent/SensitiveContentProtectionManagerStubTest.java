package com.lody.virtual.client.hook.proxies.sensitivecontent;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SensitiveContentProtectionManagerStubTest {

    @Test
    public void bindsSensitiveContentPackageIdentityHook() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();

        new SensitiveContentProtectionManagerStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("setSensitiveContentProtection"));
    }

    @Test
    public void rewritesOnlyGuestPackageInKnownBinderShape() {
        Object token = new Object();
        Object[] args = {token, "com.facebook.lite", true};

        assertTrue(SensitiveContentProtectionManagerStub.rewriteCallingPackage(
                args, "com.facebook.lite", "org.apptwin"));

        assertArrayEquals(new Object[]{token, "org.apptwin", true}, args);
    }

    @Test
    public void leavesUnknownPackageAndDescriptorUntouched() {
        Object[][] argsCases = {
                {new Object(), "another.package", true},
                {new Object(), "com.facebook.lite"},
                {new Object(), "com.facebook.lite", "true"}
        };

        for (Object[] args : argsCases) {
            Object[] original = args.clone();
            assertFalse(SensitiveContentProtectionManagerStub.rewriteCallingPackage(
                    args, "com.facebook.lite", "org.apptwin"));
            assertArrayEquals(original, args);
        }
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
