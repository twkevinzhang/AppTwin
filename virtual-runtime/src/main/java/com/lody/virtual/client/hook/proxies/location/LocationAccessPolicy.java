package com.lody.virtual.client.hook.proxies.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import com.lody.virtual.client.core.VirtualCore;

/**
 * Keeps denied guest location requests inside the virtual runtime.
 *
 * <p>Passing a guest package name to Android's real location service fails app-op ownership
 * validation because every guest runs under the AppTwin UID. Repeated callers such as Maps
 * then retry the rejected registration indefinitely. When the host UID has neither coarse nor
 * fine location access, expose location as unavailable and avoid the host Binder call entirely.
 */
public final class LocationAccessPolicy {
    private static final long CACHE_DURATION_MS = 1_000L;

    private static volatile long sCheckedAtMs = Long.MIN_VALUE;
    private static volatile boolean sHasLocationPermission;

    private LocationAccessPolicy() {
    }

    public static boolean hasLocationPermission() {
        long now = SystemClock.elapsedRealtime();
        long checkedAtMs = sCheckedAtMs;
        if (checkedAtMs != Long.MIN_VALUE && now - checkedAtMs <= CACHE_DURATION_MS) {
            return sHasLocationPermission;
        }

        Context context = VirtualCore.get().getContext();
        int fine = checkPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = checkPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean granted = hasAnyLocationPermission(fine, coarse);
        sHasLocationPermission = granted;
        sCheckedAtMs = now;
        return granted;
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
