package com.lody.virtual.helper.compat;

import android.content.pm.PackageManager;
import android.os.Build;

/** Only known future platform permissions are exempt; lookup failures remain fail closed. */
public final class PlatformPermissionCompatibility {
    private PlatformPermissionCompatibility() {}

    public static boolean isUnavailableOnPlatform(PackageManager pm, String permission) {
        return isUnavailableOnPlatform(permission, Build.VERSION.SDK_INT,
                () -> { pm.getPermissionInfo(permission, 0); });
    }

    interface PlatformLookup {
        void lookup() throws PackageManager.NameNotFoundException;
    }

    static boolean isUnavailableOnPlatform(String permission, int sdk, PlatformLookup lookup) {
        if (!isKnownFuturePermission(permission, sdk)) return false;
        try {
            // A successful lookup always proves existence, regardless of protection level.
            lookup.lookup();
            return false;
        } catch (PackageManager.NameNotFoundException absent) {
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static boolean isKnownFuturePermission(String permission, int sdk) {
        int introduced;
        if ("android.permission.READ_MEDIA_IMAGES".equals(permission)
                || "android.permission.READ_MEDIA_VIDEO".equals(permission)
                || "android.permission.READ_MEDIA_AUDIO".equals(permission)
                || "android.permission.POST_NOTIFICATIONS".equals(permission)) introduced = 33;
        else if ("android.permission.READ_MEDIA_VISUAL_USER_SELECTED".equals(permission)) introduced = 34;
        else if ("android.permission.ACCESS_BACKGROUND_LOCATION".equals(permission)) introduced = 29;
        else return false;
        return sdk < introduced;
    }

    /** Future permission grants never substitute for a supported media capability. */
    public static boolean canReadMedia(int sdk, boolean legacy, boolean images,
                                       boolean videos, boolean requireImages, boolean requireVideos) {
        if (legacy) return true;
        return sdk >= 33 && (!requireImages || images) && (!requireVideos || videos);
    }
}
