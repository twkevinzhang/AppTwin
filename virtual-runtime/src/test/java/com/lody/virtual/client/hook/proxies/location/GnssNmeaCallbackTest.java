package com.lody.virtual.client.hook.proxies.location;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import com.lody.virtual.client.hook.base.Inject;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.base.SkipInject;
import org.junit.Test;

public class GnssNmeaCallbackTest {
    private static final String GUEST = "com.addcn.android.house591";
    private static final String HOST = "org.apptwin";

    public static class Service {
        int registrations;
        int removals;
        Object listener;
        String pkg;
        String tag;
        String id;
        public boolean register(Object listener, String pkg, String tag, String id) {
            registrations++;
            this.listener = listener;
            this.pkg = pkg;
            this.tag = tag;
            this.id = id;
            if (!HOST.equals(pkg)) throw new SecurityException("invalid guest package for host uid");
            return true;
        }
        public void registerVoid(Object listener, String pkg, String tag, String id) {
            register(listener, pkg, tag, id);
        }
        public void unregister(Object listener) {
            removals++;
            this.listener = listener;
        }
    }

    private MethodProxies.RegisterGnssNmeaCallback registration(boolean fine, boolean fake) {
        return new MethodProxies.RegisterGnssNmeaCallback() {
            @Override protected boolean hasFinePermission() { return fine; }
            @Override protected boolean usesFakeLocation() { return fake; }
            @Override protected void rewritePackage(Object[] args) {
                LocationPackageIdentity.replaceGnssNmeaPackage(args, GUEST, HOST);
            }
        };
    }

    @Test public void rewritesPackageAndPreservesListenerAttributionAndId() throws Throwable {
        Service service = new Service();
        Object listener = new Object();
        Method method = Service.class.getMethod("register", Object.class, String.class,
                String.class, String.class);
        assertEquals(true, registration(true, false).call(service, method,
                listener, GUEST, GUEST, GUEST));
        assertEquals(1, service.registrations);
        assertSame(listener, service.listener);
        assertEquals(HOST, service.pkg);
        assertEquals(GUEST, service.tag);
        assertEquals(GUEST, service.id);
    }

    @Test public void deniedFinePermissionAndFakeLocationNeverReachRealService() throws Throwable {
        Method method = Service.class.getMethod("register", Object.class, String.class,
                String.class, String.class);
        Service service = new Service();
        assertEquals(false, registration(false, false).call(service, method,
                new Object(), GUEST, null, "id"));
        assertEquals(false, registration(true, true).call(service, method,
                new Object(), GUEST, null, "id"));
        assertEquals(0, service.registrations);
    }

    @Test public void unregisterForwardsCleanupAndFakeModeIsVoidSafe() throws Throwable {
        Service service = new Service();
        Object listener = new Object();
        Method method = Service.class.getMethod("unregister", Object.class);
        MethodProxies.UnregisterGnssNmeaCallback cleanup = new MethodProxies.UnregisterGnssNmeaCallback() {
            @Override protected boolean usesFakeLocation() { return false; }
        };
        assertNull(cleanup.call(service, method, listener));
        assertEquals(1, service.removals);
        assertSame(listener, service.listener);
        MethodProxies.UnregisterGnssNmeaCallback fake = new MethodProxies.UnregisterGnssNmeaCallback() {
            @Override protected boolean usesFakeLocation() { return true; }
        };
        assertNull(fake.call(service, method, listener));
        assertEquals(1, service.removals);
    }

    @Test public void hooksHaveAndroid12BinderNames() {
        assertEquals("registerGnssNmeaCallback", registration(true, false).getMethodName());
        assertEquals("unregisterGnssNmeaCallback",
                new MethodProxies.UnregisterGnssNmeaCallback().getMethodName());
    }

    @Test public void android12VoidRegistrationIsSafeInAllModes() throws Throwable {
        Method method = Service.class.getMethod("registerVoid", Object.class, String.class,
                String.class, String.class);
        Service service = new Service();
        assertNull(registration(true, false).call(service, method,
                new Object(), GUEST, null, "id"));
        assertNull(registration(false, false).call(service, method,
                new Object(), GUEST, null, "id"));
        assertNull(registration(true, true).call(service, method,
                new Object(), GUEST, null, "id"));
        assertEquals(1, service.registrations);
        assertEquals(HOST, service.pkg);
    }

    @Test public void bothHooksAreDiscoveredAndConstructibleByInjector() throws Exception {
        Inject inject = LocationManagerStub.class.getAnnotation(Inject.class);
        assertNotNull(inject);
        assertEquals(MethodProxies.class, inject.value());
        int discovered = 0;
        for (Class<?> hook : inject.value().getDeclaredClasses()) {
            if (hook != MethodProxies.RegisterGnssNmeaCallback.class
                    && hook != MethodProxies.UnregisterGnssNmeaCallback.class) continue;
            assertFalse(Modifier.isAbstract(hook.getModifiers()));
            assertTrue(MethodProxy.class.isAssignableFrom(hook));
            assertNull(hook.getAnnotation(SkipInject.class));
            Constructor<?> constructor = hook.getDeclaredConstructors()[0];
            assertEquals(0, constructor.getParameterTypes().length);
            constructor.setAccessible(true);
            assertTrue(constructor.newInstance() instanceof MethodProxy);
            discovered++;
        }
        assertEquals(2, discovered);
    }

    @Test public void identityDoesNotRewriteUnrelatedOrMissingPackage() {
        Object[] args = {new Object(), "other.app", GUEST, GUEST};
        LocationPackageIdentity.replaceGnssNmeaPackage(args, GUEST, HOST);
        assertEquals("other.app", args[1]);
        assertEquals(GUEST, args[2]);
        assertEquals(GUEST, args[3]);
        LocationPackageIdentity.replaceGnssNmeaPackage(null, GUEST, HOST);
        LocationPackageIdentity.replaceGnssNmeaPackage(new Object[0], GUEST, HOST);
    }
}
