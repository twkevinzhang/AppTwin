package com.lody.virtual.client.hook.proxies.location;

import java.lang.reflect.Constructor;
import java.util.Locale;

/** Bridges the identity nested in Android's immutable geocoder requests. */
final class GeocodeRequestIdentity {
    private GeocodeRequestIdentity() { }

    static Object rebuild(Object request, String method, String guestPackage, int guestUid,
            String hostPackage, int realUid, String hostAttribution) {
        boolean reverse = "reverseGeocode".equals(method);
        if (!reverse && !"forwardGeocode".equals(method)) {
            throw unsupported(null);
        }
        String name = "android.location.provider."
                + (reverse ? "ReverseGeocodeRequest" : "ForwardGeocodeRequest");
        try {
            Class<?> type = Class.forName(name);
            if (request == null || request.getClass() != type) throw unsupported(null);
            String callingPackage = (String) get(request, "getCallingPackage");
            int callingUid = (Integer) get(request, "getCallingUid");
            boolean ownGuest = guestPackage != null && guestPackage.equals(callingPackage)
                    && (callingUid == realUid || callingUid == guestUid);
            boolean ownHost = hostPackage != null && hostPackage.equals(callingPackage)
                    && callingUid == realUid;
            if (hostPackage == null || hostPackage.isEmpty() || realUid < 0
                    || (!ownGuest && !ownHost)) {
                throw new SecurityException("Geocoder request identity does not belong to this process");
            }
            Class<?> builderType = Class.forName(name + "$Builder");
            Object builder;
            if (reverse) {
                Constructor<?> constructor = builderType.getConstructor(double.class,
                        double.class, int.class, Locale.class, int.class, String.class);
                builder = constructor.newInstance(get(request, "getLatitude"),
                        get(request, "getLongitude"), get(request, "getMaxResults"),
                        get(request, "getLocale"), realUid, hostPackage);
            } else {
                Constructor<?> constructor = builderType.getConstructor(String.class,
                        double.class, double.class, double.class, double.class, int.class,
                        Locale.class, int.class, String.class);
                builder = constructor.newInstance(get(request, "getLocationName"),
                        get(request, "getLowerLeftLatitude"), get(request, "getLowerLeftLongitude"),
                        get(request, "getUpperRightLatitude"), get(request, "getUpperRightLongitude"),
                        get(request, "getMaxResults"), get(request, "getLocale"), realUid, hostPackage);
            }
            builderType.getMethod("setCallingAttributionTag", String.class)
                    .invoke(builder, hostAttribution);
            return builderType.getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            // Never forward an unadapted identity or silently turn a service failure into success.
            throw unsupported(exception);
        }
    }

    private static Object get(Object request, String name) throws ReflectiveOperationException {
        return request.getClass().getMethod(name).invoke(request);
    }

    private static UnsupportedOperationException unsupported(Throwable cause) {
        return new UnsupportedOperationException("Unsupported geocoder request schema", cause);
    }
}
