package com.lody.virtual.client.hook.proxies.telecom;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TelecomManagerStubTest {
    @Test
    public void hooksLinePhoneAccountRegistrationAndIncomingCallEntryPoints() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();

        new TelecomManagerStub(invocationStub);

        assertRewriteProxy(invocationStub, "registerPhoneAccount");
        assertRewriteProxy(invocationStub, "unregisterPhoneAccount");
        assertRewriteProxy(invocationStub, "addNewIncomingCall");
        assertRewriteProxy(invocationStub, "addNewUnknownCall");
    }

    private static void assertRewriteProxy(TestBinderInvocationStub stub, String methodName) {
        MethodProxy proxy = stub.getMethodProxy(methodName);
        assertNotNull(methodName, proxy);
        assertEquals("RewritePhoneAccountArgs", proxy.getClass().getSimpleName());
    }

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
            if (hooks == null) {
                hooks = new HashMap<>();
            }
            return hooks.put(methodProxy.getMethodName(), methodProxy);
        }

        @Override
        public MethodProxy getMethodProxy(String name) {
            return hooks == null ? null : hooks.get(name);
        }
    }
}
