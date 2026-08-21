package com.lody.virtual.client.hook.proxies.media.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.IInterface;

import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class MediaRouterServiceStubTest {

    private static final String HOST_PACKAGE = "org.apptwin";

    @Test
    public void bindsOnlySupportedMediaRouterRegistrationHooks() {
        TestBinderInvocationStub invocationStub = new TestBinderInvocationStub();
        new MediaRouterServiceStub(invocationStub);

        assertNotNull(invocationStub.getMethodProxy("registerClientAsUser"));
        assertNotNull(invocationStub.getMethodProxy("registerRouter2"));
        assertNotNull(invocationStub.getMethodProxy("registerManager"));
        assertNotNull(invocationStub.getMethodProxy("getSystemRoutes"));
        assertNull(invocationStub.getMethodProxy("unregisterRouter2"));
        assertNull(invocationStub.getMethodProxy("setDiscoveryRequestWithRouter2"));
        assertNull(invocationStub.getMethodProxy("unrelatedMethod"));
    }

    @Test
    public void registerRouter2RewritesOnlyPackageArgument() throws Exception {
        MediaRouterServiceStub.ReplaceRegistrationPackageMethodProxy proxy =
                new MediaRouterServiceStub.ReplaceRegistrationPackageMethodProxy(
                        "registerRouter2", () -> HOST_PACKAGE);
        Method method = FakeMediaRouterService.class.getMethod("registerRouter2",
                Object.class, String.class, String.class, Object.class, int.class);
        Object router = new Object();
        String attributionTag = "cast-discovery";
        Object options = new Object();
        Object[] args = {
                router, "com.google.android.youtube", attributionTag, options, 7
        };

        assertTrue(proxy.beforeCall(null, method, args));

        assertSame(router, args[0]);
        assertEquals(HOST_PACKAGE, args[1]);
        assertSame(attributionTag, args[2]);
        assertSame(options, args[3]);
        assertEquals(7, args[4]);
    }

    @Test
    public void getSystemRoutesRewritesOnlyCallerPackageArgument() throws Exception {
        MediaRouterServiceStub.ReplaceSystemRoutesCallerPackageMethodProxy proxy =
                new MediaRouterServiceStub.ReplaceSystemRoutesCallerPackageMethodProxy(
                        () -> HOST_PACKAGE);
        Method method = FakeMediaRouterService.class.getMethod("getSystemRoutes",
                String.class, String.class, boolean.class);
        String attributionTag = "cast-discovery";
        Object[] args = {"com.google.android.youtube", attributionTag, true};

        assertTrue(proxy.beforeCall(null, method, args));

        assertEquals(HOST_PACKAGE, args[0]);
        assertSame(attributionTag, args[1]);
        assertEquals(true, args[2]);
    }

    @Test
    public void getSystemRoutesRewriteRejectsUnexpectedCallerPackage() throws Exception {
        MediaRouterServiceStub.ReplaceSystemRoutesCallerPackageMethodProxy proxy =
                new MediaRouterServiceStub.ReplaceSystemRoutesCallerPackageMethodProxy(
                        () -> HOST_PACKAGE);
        Method method = FakeMediaRouterService.class.getMethod("getSystemRoutes",
                String.class, String.class, boolean.class);
        Object[] args = {42, "cast-discovery", true};

        assertTrue(proxy.beforeCall(null, method, args));

        assertEquals(42, args[0]);
        assertEquals("cast-discovery", args[1]);
        assertEquals(true, args[2]);
    }

    @Test
    public void registrationRewriteRejectsUnexpectedSignatureWithoutChangingArguments() {
        Object router = new Object();
        Object[] missingPackage = {router};
        Object[] nonStringPackage = {router, 42, "attribution"};

        assertFalse(MediaRouterServiceStub.ReplaceRegistrationPackageMethodProxy
                .replacePackageArgument(missingPackage, 1, HOST_PACKAGE));
        assertSame(router, missingPackage[0]);

        assertFalse(MediaRouterServiceStub.ReplaceRegistrationPackageMethodProxy
                .replacePackageArgument(nonStringPackage, 1, HOST_PACKAGE));
        assertSame(router, nonStringPackage[0]);
        assertEquals(42, nonStringPackage[1]);
        assertEquals("attribution", nonStringPackage[2]);
    }

    public interface FakeMediaRouterService {
        void registerRouter2(Object router, String packageName, String attributionTag,
                Object options, int flags);

        void getSystemRoutes(String packageName, String attributionTag,
                boolean shouldIncludeNonSystemRoutes);
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
