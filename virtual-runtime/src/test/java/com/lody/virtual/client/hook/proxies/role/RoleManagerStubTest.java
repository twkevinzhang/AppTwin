package com.lody.virtual.client.hook.proxies.role;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class RoleManagerStubTest {
    @Test
    public void reportsThatGuestCannotHoldPhysicalAndroidRole() throws Throwable {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new RoleManagerStub(invocationStub);

        MethodProxy hook = invocationStub.getMethodProxy("isRoleHeldAsUser");
        assertNotNull(hook);
        assertEquals(false, hook.call(null, sampleMethod(),
                "android.app.role.BROWSER", "org.mozilla.firefox", 0));
        assertNull(invocationStub.getMethodProxy("isRoleAvailable"));
    }

    private static Method sampleMethod() throws NoSuchMethodException {
        return RoleManagerStubTest.class.getDeclaredMethod("sampleMethod");
    }

    /** Avoids Android framework service lookup in local JVM tests. */
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
