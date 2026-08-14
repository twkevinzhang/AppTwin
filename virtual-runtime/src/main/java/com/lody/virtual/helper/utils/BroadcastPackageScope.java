package com.lody.virtual.helper.utils;

/** Preserves the package boundary of a broadcast after its Android package is redirected. */
public final class BroadcastPackageScope {

    public static final String EXTRA_TARGET_PACKAGE = "_VA_|_privilege_pkg_";

    private BroadcastPackageScope() {
    }

    public static boolean accepts(String targetPackage, String receiverPackage) {
        return targetPackage == null || targetPackage.equals(receiverPackage);
    }
}
