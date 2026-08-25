package com.lody.virtual.helper.utils;

/** Preserves the package boundary of a broadcast after its Android package is redirected. */
public final class BroadcastPackageScope {

    public static final String EXTRA_TARGET_PACKAGE = "_VA_|_privilege_pkg_";
    public static final String EXTRA_VIRTUAL_SENDER_PACKAGE = "_VA_|_sender_pkg_";
    public static final String EXTRA_VIRTUAL_SENDER_VUID = "_VA_|_sender_vuid_";
    public static final String EXTRA_VIRTUAL_SENDER_USER_ID = "_VA_|_sender_user_id_";
    public static final String EXTRA_LINE_PUSH_ATTESTATION = "_VA_|_line_push_attestation_";

    private BroadcastPackageScope() {
    }

    public static boolean accepts(String targetPackage, String receiverPackage) {
        return targetPackage == null || targetPackage.equals(receiverPackage);
    }
}
