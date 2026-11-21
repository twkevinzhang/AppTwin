package com.lody.virtual.client.hook.proxies.location;

import static org.junit.Assert.*;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.ReverseGeocodeRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import org.junit.Test;

public class GeocodeRequestIdentityTest {
    private static final String GUEST = "jp.naver.line.android";
    private static final String HOST = "org.apptwin";
    private static final int REAL_UID = 10423;
    private static final int GUEST_UID = 10041;

    private ReverseGeocodeRequest reverse(String pkg, int uid) {
        return new ReverseGeocodeRequest.Builder(25.1, 121.2, 7, Locale.TAIWAN, uid, pkg)
                .setCallingAttributionTag("guest-tag").build();
    }
    private Object rewrite(Object request, String method) {
        return GeocodeRequestIdentity.rebuild(request, method, GUEST, GUEST_UID,
                HOST, REAL_UID, "host-tag");
    }
    private MethodProxies.ReverseGeocode reverseHook() {
        return new MethodProxies.ReverseGeocode() {
            @Override protected Object rewriteRequest(Object request) {
                return rewrite(request, getMethodName());
            }
        };
    }
    private MethodProxies.ForwardGeocode forwardHook() {
        return new MethodProxies.ForwardGeocode() {
            @Override protected Object rewriteRequest(Object request) {
                return rewrite(request, getMethodName());
            }
        };
    }
    public static class Service {
        Object request;
        Object callback;
        int calls;
        boolean fail;
        public void geocode(Object request, Object callback) {
            calls++;
            this.request = request;
            this.callback = callback;
            if (fail) throw new SecurityException("service failure");
        }
    }
    private Method serviceMethod() throws Exception {
        return Service.class.getMethod("geocode", Object.class, Object.class);
    }

    @Test public void reverseHookRebuildsIdentityAndRetainsQueryAndCallback() throws Throwable {
        ReverseGeocodeRequest original = reverse(GUEST, REAL_UID);
        Object callback = new Object();
        Object[] args = {original, callback};
        Service service = new Service();
        assertNull(reverseHook().call(service, serviceMethod(), args));
        ReverseGeocodeRequest result = (ReverseGeocodeRequest) service.request;
        assertNotSame(original, result);
        assertEquals(HOST, result.getCallingPackage());
        assertEquals(REAL_UID, result.getCallingUid());
        assertEquals("host-tag", result.getCallingAttributionTag());
        assertEquals(original.getLatitude(), result.getLatitude(), 0);
        assertEquals(original.getLongitude(), result.getLongitude(), 0);
        assertEquals(original.getMaxResults(), result.getMaxResults());
        assertSame(original.getLocale(), result.getLocale());
        assertSame(callback, service.callback);
        assertSame(original, args[0]);
        assertEquals(GUEST, original.getCallingPackage());
        assertEquals("guest-tag", original.getCallingAttributionTag());
    }

    @Test public void forwardHookPreservesEveryQueryFieldAndCallback() throws Throwable {
        ForwardGeocodeRequest original = new ForwardGeocodeRequest.Builder("public landmark",
                -20.5, -30.5, 40.5, 50.5, 9, Locale.JAPAN, GUEST_UID, GUEST)
                .setCallingAttributionTag("guest-tag").build();
        Service service = new Service();
        Object callback = new Object();
        forwardHook().call(service, serviceMethod(), original, callback);
        ForwardGeocodeRequest result = (ForwardGeocodeRequest) service.request;
        assertEquals(HOST, result.getCallingPackage());
        assertEquals(REAL_UID, result.getCallingUid());
        assertEquals("host-tag", result.getCallingAttributionTag());
        assertEquals(original.getLocationName(), result.getLocationName());
        assertEquals(original.getLowerLeftLatitude(), result.getLowerLeftLatitude(), 0);
        assertEquals(original.getLowerLeftLongitude(), result.getLowerLeftLongitude(), 0);
        assertEquals(original.getUpperRightLatitude(), result.getUpperRightLatitude(), 0);
        assertEquals(original.getUpperRightLongitude(), result.getUpperRightLongitude(), 0);
        assertEquals(original.getMaxResults(), result.getMaxResults());
        assertSame(original.getLocale(), result.getLocale());
        assertSame(callback, service.callback);
        assertEquals(GUEST_UID, original.getCallingUid());
    }

    @Test public void foreignPackageOrUidNeverReachesService() throws Throwable {
        for (ReverseGeocodeRequest request : new ReverseGeocodeRequest[] {
                reverse("foreign.app", REAL_UID), reverse(GUEST, 99999),
                reverse(HOST, GUEST_UID)}) {
            Service service = new Service();
            try {
                reverseHook().call(service, serviceMethod(), request, new Object());
                fail("identity must be rejected");
            } catch (SecurityException expected) { }
            assertEquals(0, service.calls);
        }
    }

    @Test public void hostAndGuestVirtualUidAreSupportedAndNullAttributionIsExplicit() {
        for (ReverseGeocodeRequest request : new ReverseGeocodeRequest[] {
                reverse(HOST, REAL_UID), reverse(GUEST, GUEST_UID)}) {
            ReverseGeocodeRequest result = (ReverseGeocodeRequest) GeocodeRequestIdentity.rebuild(
                    request, "reverseGeocode", GUEST, GUEST_UID, HOST, REAL_UID, null);
            assertEquals(HOST, result.getCallingPackage());
            assertEquals(REAL_UID, result.getCallingUid());
            assertNull(result.getCallingAttributionTag());
        }
    }

    @Test public void nullUnknownOrMismatchedSchemaNeverReachesService() throws Throwable {
        ForwardGeocodeRequest forward = new ForwardGeocodeRequest.Builder("query", 0, 0,
                1, 1, 1, Locale.US, REAL_UID, GUEST).build();
        for (Object request : new Object[] {null, new Object(), forward}) {
            Service service = new Service();
            try {
                reverseHook().call(service, serviceMethod(), request, new Object());
                fail("schema must be rejected");
            } catch (UnsupportedOperationException expected) { }
            assertEquals(0, service.calls);
        }
    }

    @Test public void unsupportedSignatureAndMethodFailClosed() throws Throwable {
        Service service = new Service();
        try {
            reverseHook().call(service, serviceMethod(), reverse(GUEST, REAL_UID));
            fail("signature must be rejected");
        } catch (UnsupportedOperationException expected) { }
        try {
            rewrite(reverse(GUEST, REAL_UID), "otherMethod");
            fail("method must be rejected");
        } catch (UnsupportedOperationException expected) { }
        assertEquals(0, service.calls);
    }

    @Test public void serviceFailuresAreNotSwallowedAndCallbackIdentityIsPreserved() throws Throwable {
        Service service = new Service();
        service.fail = true;
        Object callback = new Object();
        try {
            reverseHook().call(service, serviceMethod(), reverse(GUEST, REAL_UID), callback);
            fail("service failure must propagate");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof SecurityException);
            assertEquals("service failure", expected.getCause().getMessage());
        }
        assertSame(callback, service.callback);
        assertEquals(1, service.calls);
    }

    @Test public void repeatedCallsBuildIndependentRequestsAndHooksAreDiscoverable() throws Exception {
        ReverseGeocodeRequest original = reverse(GUEST, REAL_UID);
        assertNotSame(rewrite(original, "reverseGeocode"), rewrite(original, "reverseGeocode"));
        assertEquals("reverseGeocode", reverseHook().getMethodName());
        assertEquals("forwardGeocode", forwardHook().getMethodName());
        for (Class<?> type : new Class<?>[] {MethodProxies.ReverseGeocode.class,
                MethodProxies.ForwardGeocode.class}) {
            assertNotNull(type.getDeclaredConstructor().newInstance());
            assertNull(type.getAnnotation(com.lody.virtual.client.hook.base.SkipInject.class));
        }
    }
}
