package com.lody.virtual.client.hook.proxies.appops;

/** Stable framework AppOps IDs; kept separate so unknown operations never become grants. */
public final class SpaceAppOpPermission {
    private SpaceAppOpPermission() {}
    public static String permissionFor(int op) {
        return permissionFor(op, android.os.Build.VERSION.SDK_INT);
    }
    public static String permissionFor(int op, int sdk) {
        switch (op) {
            case 0: return "android.permission.ACCESS_COARSE_LOCATION";
            case 1: case 2: case 41: case 42: return "android.permission.ACCESS_FINE_LOCATION";
            case 4: return "android.permission.READ_CONTACTS";
            case 5: return "android.permission.WRITE_CONTACTS";
            case 26: return "android.permission.CAMERA";
            case 27: return "android.permission.RECORD_AUDIO";
            case 59: return "android.permission.READ_EXTERNAL_STORAGE";
            case 85: return sdk >= 33 ? "android.permission.READ_MEDIA_IMAGES" : "android.permission.READ_EXTERNAL_STORAGE";
            case 83: return sdk >= 33 ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";
            default: return null;
        }
    }
}
