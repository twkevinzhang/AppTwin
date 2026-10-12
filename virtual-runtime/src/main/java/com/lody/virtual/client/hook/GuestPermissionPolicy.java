package com.lody.virtual.client.hook;

import android.content.pm.PackageManager;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.os.VUserHandle;

/** Inner capability: never substitute the host UID's grant for a guest decision. */
public final class GuestPermissionPolicy {
    private GuestPermissionPolicy() {}
    public static boolean isControlled(String permission) {
        return "android.permission.CAMERA".equals(permission)
                || "android.permission.RECORD_AUDIO".equals(permission)
                || "android.permission.ACCESS_FINE_LOCATION".equals(permission)
                || "android.permission.ACCESS_COARSE_LOCATION".equals(permission)
                || "android.permission.ACCESS_BACKGROUND_LOCATION".equals(permission)
                || "android.permission.READ_CONTACTS".equals(permission)
                || "android.permission.WRITE_CONTACTS".equals(permission)
                || "android.permission.READ_EXTERNAL_STORAGE".equals(permission)
                || "android.permission.READ_MEDIA_IMAGES".equals(permission)
                || "android.permission.READ_MEDIA_VIDEO".equals(permission);
    }
    public static boolean isGranted(String permission) {
        try {
            String pkg = VClientImpl.get().getCurrentPackage();
            return pkg != null && VPackageManager.get().checkPermission(permission, pkg,
                    VUserHandle.myUserId()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable unavailable) {
            return false;
        }
    }
    public static boolean canReadMedia(boolean images, boolean videos) {
        return com.lody.virtual.helper.compat.PlatformPermissionCompatibility.canReadMedia(
                android.os.Build.VERSION.SDK_INT,
                isGranted("android.permission.READ_EXTERNAL_STORAGE"),
                isGranted("android.permission.READ_MEDIA_IMAGES"),
                isGranted("android.permission.READ_MEDIA_VIDEO"), images, videos);
    }
    public static void require(String permission) {
        if (!isGranted(permission)) throw new SecurityException("Space permission denied: " + permission);
    }
}
