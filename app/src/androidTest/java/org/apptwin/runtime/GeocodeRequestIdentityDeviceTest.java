package org.apptwin.runtime;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Opt-in, local framework-object compatibility proof. No geocoder service, network,
 * location lookup, permissions, guest launch, or persisted data is used. This is
 * separate from the cloned LINE UI acceptance test.
 */
@RunWith(AndroidJUnit4.class)
public class GeocodeRequestIdentityDeviceTest {
    private static final String GUEST = "jp.naver.line.android";
    private static final int GUEST_UID = 910001;
    private static final String GUEST_TAG = "fixture-guest-tag";
    private static final Locale LOCALE = new Locale("zh", "TW", "fixture");

    @Before
    public void requireExplicitOptInAndSupportedFramework() throws Exception {
        assumeTrue("Pass -e geocodeIdentityE2e 1 for the dedicated object fixture",
                "1".equals(InstrumentationRegistry.getArguments().getString("geocodeIdentityE2e")));
        // An explicitly requested compatibility run must fail, never silently skip,
        // if the target framework does not expose the required request schema.
        assertTrue("New geocoder request API required", Build.VERSION.SDK_INT >= 36);
        Class.forName("android.location.provider.ReverseGeocodeRequest");
        Class.forName("android.location.provider.ForwardGeocodeRequest");
    }

    @Test
    public void reverseFrameworkRequestPreservesQueryAndOriginalAcrossIdentityRebuild()
            throws Exception {
        for (int uid : new int[]{GUEST_UID, Process.myUid()}) {
            Object builder = Class.forName("android.location.provider.ReverseGeocodeRequest$Builder")
                    .getConstructor(double.class, double.class, int.class, Locale.class,
                            int.class, String.class)
                    .newInstance(25.0, 121.0, 7, LOCALE, uid, GUEST);
            Object original = build(builder);
            assertRebuilt(original, "reverseGeocode", uid,
                    new String[]{"getLatitude", "getLongitude", "getMaxResults", "getLocale"});
        }
    }

    @Test
    public void forwardFrameworkRequestPreservesNameBoundsAndOriginalAcrossIdentityRebuild()
            throws Exception {
        for (int uid : new int[]{GUEST_UID, Process.myUid()}) {
            Object builder = Class.forName("android.location.provider.ForwardGeocodeRequest$Builder")
                    .getConstructor(String.class, double.class, double.class, double.class,
                            double.class, int.class, Locale.class, int.class, String.class)
                    .newInstance("Taipei 101", 24.0, 120.0, 26.0, 122.0, 9,
                            LOCALE, uid, GUEST);
            Object original = build(builder);
            assertRebuilt(original, "forwardGeocode", uid, new String[]{
                    "getLocationName", "getLowerLeftLatitude", "getLowerLeftLongitude",
                    "getUpperRightLatitude", "getUpperRightLongitude", "getMaxResults", "getLocale"});
        }
    }

    private static Object build(Object builder) throws Exception {
        builder.getClass().getMethod("setCallingAttributionTag", String.class)
                .invoke(builder, GUEST_TAG);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static void assertRebuilt(Object original, String method, int originalUid,
            String[] queryGetters) throws Exception {
        byte[] before = parcelBytes(original);
        String host = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        // Both clearing a guest tag and replacing it with host metadata must survive
        // the real framework Builder; no tag is submitted to a system service.
        for (String hostTag : new String[]{null, "fixture-host-tag"}) {
            Object copy = rebuild(original, method, host, hostTag);
            assertNotSame(original, copy);
            assertEquals(original.getClass(), copy.getClass());
            assertEquals(host, get(copy, "getCallingPackage"));
            assertEquals(Process.myUid(), ((Number) get(copy, "getCallingUid")).intValue());
            assertEquals(hostTag, get(copy, "getCallingAttributionTag"));
            for (String getter : queryGetters) {
                assertEquals("Query field must survive: " + getter,
                        get(original, getter), get(copy, getter));
            }
            assertEquals(GUEST, get(original, "getCallingPackage"));
            assertEquals(originalUid, ((Number) get(original, "getCallingUid")).intValue());
            assertEquals(GUEST_TAG, get(original, "getCallingAttributionTag"));
            assertArrayEquals("Original Parcelable must not mutate", before, parcelBytes(original));
        }
    }

    private static Object rebuild(Object original, String method, String host, String hostTag)
            throws Exception {
        Method rebuild = Class.forName(
                "com.lody.virtual.client.hook.proxies.location.GeocodeRequestIdentity")
                .getDeclaredMethod("rebuild", Object.class, String.class, String.class,
                        int.class, String.class, int.class, String.class);
        rebuild.setAccessible(true);
        return rebuild.invoke(null, original, method, GUEST, GUEST_UID,
                host, Process.myUid(), hostTag);
    }

    private static Object get(Object request, String name) throws Exception {
        return request.getClass().getMethod(name).invoke(request);
    }

    private static byte[] parcelBytes(Object request) {
        Parcel parcel = Parcel.obtain();
        try {
            ((Parcelable) request).writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }
}
