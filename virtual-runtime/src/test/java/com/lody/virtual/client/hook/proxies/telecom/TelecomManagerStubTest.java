package com.lody.virtual.client.hook.proxies.telecom;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TelecomManagerStubTest {
    @Test
    public void hooksLinePhoneAccountRegistrationAndIncomingCallEntryPoints() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();

        new TelecomManagerStub(invocationStub);

        assertRewriteProxy(invocationStub, "registerPhoneAccount");
        assertRewriteProxy(invocationStub, "unregisterPhoneAccount");
        assertRewriteProxy(invocationStub, "addNewIncomingCall");
        assertRewriteProxy(invocationStub, "addNewUnknownCall");
        assertEquals(TelecomManagerStub.GetCallStateUsingPackage.class,
                invocationStub.getMethodProxy("getCallStateUsingPackage").getClass());
    }

    @Test
    public void callStateRewritesOnlyCallerPackageAndReturnsRealState() throws Throwable {
        TelecomManagerStub.GetCallStateUsingPackage proxy =
                new TelecomManagerStub.GetCallStateUsingPackage("org.apptwin");
        CallStateService service = new CallStateService();
        Method method = CallStateService.class.getMethod(
                "getCallStateUsingPackage", String.class, String.class);
        // A tag equal to the guest package must still remain an attribution tag.
        Object[] args = {"jp.naver.line.android", "jp.naver.line.android"};

        assertTrue(proxy.beforeCall(service, method, args));
        Object result = proxy.call(service, method, args);

        assertEquals("org.apptwin", service.callerPackage);
        assertEquals("jp.naver.line.android", service.attributionTag);
        assertEquals(2, proxy.afterCall(service, method, args, result));
    }

    @Test
    public void callStatePreservesNullAttributionAndPlatformDenial() throws Throwable {
        TelecomManagerStub.GetCallStateUsingPackage proxy =
                new TelecomManagerStub.GetCallStateUsingPackage("org.apptwin");
        CallStateService service = new CallStateService();
        service.failure = new SecurityException("platform permission denied");
        Method method = CallStateService.class.getMethod(
                "getCallStateUsingPackage", String.class, String.class);
        Object[] args = {"jp.naver.line.android", null};

        assertTrue(proxy.beforeCall(service, method, args));
        try {
            proxy.call(service, method, args);
            fail("Platform denial must propagate through reflection");
        } catch (InvocationTargetException expected) {
            assertSame(service.failure, expected.getCause());
        }
        assertEquals("org.apptwin", service.callerPackage);
        assertEquals(null, service.attributionTag);
    }

    public static final class CallStateService {
        String callerPackage;
        String attributionTag;
        SecurityException failure;

        public int getCallStateUsingPackage(String callerPackage, String attributionTag) {
            this.callerPackage = callerPackage;
            this.attributionTag = attributionTag;
            if (failure != null) throw failure;
            return 2;
        }
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
