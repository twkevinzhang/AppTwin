package com.lody.virtual.client.hook.providers;

/** Directional media access; read URI grants never confer mutation capability. */
final class MediaProviderAccessPolicy {
    private MediaProviderAccessPolicy() { }

    static boolean usesLegacyWriteCapability(int sdk) {
        return sdk <= 32;
    }

    static boolean requiresWrite(String method, String mode) {
        if ("openFile".equals(method) || "openAssetFile".equals(method)) {
            // Unknown modes fail closed rather than treating them as read-only.
            return mode == null || !"r".equals(mode);
        }
        // New/unknown provider entry points cannot implicitly inherit read-only authority.
        return !isKnownReadOnlyMethod(method);
    }

    private static boolean isKnownReadOnlyMethod(String method) {
        return "query".equals(method) || "getType".equals(method)
                || "getTypeAsync".equals(method) || "getTypeAnonymous".equals(method)
                || "getTypeAnonymousAsync".equals(method) || "getStreamTypes".equals(method)
                || "canonicalize".equals(method) || "canonicalizeAsync".equals(method)
                || "uncanonicalize".equals(method) || "uncanonicalizeAsync".equals(method)
                || "openTypedAssetFile".equals(method) || "checkUriPermission".equals(method)
                || "createCancellationSignal".equals(method);
    }

    static boolean requiresRead(String method, String mode) {
        if ("openFile".equals(method) || "openAssetFile".equals(method)) {
            return mode == null || mode.contains("r") || mode.contains("+");
        }
        return !"insert".equals(method) && !"bulkInsert".equals(method)
                && !"update".equals(method) && !"delete".equals(method);
    }

    static boolean allows(String method, String mode, boolean readGranted,
                          boolean writeGranted, boolean selectedReadGranted) {
        return (!requiresWrite(method, mode) || writeGranted)
                && (!requiresRead(method, mode) || readGranted || selectedReadGranted);
    }
}
