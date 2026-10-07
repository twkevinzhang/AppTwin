package com.lody.virtual.client.hook.proxies.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;


import com.lody.virtual.client.core.VirtualCore;

/**
 * Keeps denied guest location requests inside the virtual runtime.
 *
 * <p>Passing a guest package name to Android's real location service fails app-op ownership
 * validation because every guest runs under the AppTwin UID. Repeated location callers
 * then retry the rejected registration indefinitely. When the host UID has neither coarse nor
 * fine location access, expose location as unavailable and avoid the host Binder call entirely.
 */
public final class LocationAccessPolicy {
    private LocationAccessPolicy() {
    }

    public static boolean hasLocationPermission() {
        return com.lody.virtual.client.hook.GuestPermissionPolicy.isGranted(
                Manifest.permission.ACCESS_FINE_LOCATION)
                || com.lody.virtual.client.hook.GuestPermissionPolicy.isGranted(
                Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    public static void requireLocationAccess(Object[] args) {
        if (!hasLocationPermission()) throw new SecurityException("Space location permission denied");
        if (!hasFineLocationPermission() && args != null) {
            for (Object value : args) if ("gps".equals(value)) {
                throw new SecurityException("GPS requires fine space permission");
            }
            CoarseLocationDelivery.wrapCallbacks(args);
        }
        if (!com.lody.virtual.client.ipc.VActivityManager.hasVisibleGuestActivity()) {
            com.lody.virtual.client.hook.GuestPermissionPolicy.require(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
    }

    /** NMEA contains precise coordinates; approximate location permission is insufficient. */
    public static boolean hasFineLocationPermission() {
        return com.lody.virtual.client.hook.GuestPermissionPolicy.isGranted(
                Manifest.permission.ACCESS_FINE_LOCATION);
    }

    static boolean hasAnyLocationPermission(int finePermission, int coarsePermission) {
        return finePermission == PackageManager.PERMISSION_GRANTED
                || coarsePermission == PackageManager.PERMISSION_GRANTED;
    }

    static Object deniedResult(Class<?> returnType) {
        if (returnType == null || returnType == Void.TYPE || !returnType.isPrimitive()) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        return null;
    }

    private static int checkPermission(Context context, String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(permission);
        }
        return context.getPackageManager().checkPermission(permission, context.getPackageName());
    }
}
