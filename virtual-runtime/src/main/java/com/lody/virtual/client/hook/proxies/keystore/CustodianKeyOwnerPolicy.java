package com.lody.virtual.client.hook.proxies.keystore;

import java.util.UUID;

/** Resolves the stable Group identity used by the external LINE key custodian. */
public final class CustodianKeyOwnerPolicy {
    static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String GROUP_PREFIX = "AppTwin:group:";

    private CustodianKeyOwnerPolicy() {
    }

    public static boolean requiresCustodian(String packageName) {
        return LINE_PACKAGE.equals(packageName);
    }

    /**
     * Returns a canonical UUID only when the virtual user is an AppTwin Group. Unknown ownership
     * must fail closed rather than falling back to the reusable numeric virtual-user id.
     */
    public static String stableOwnerId(String packageName, String virtualUserName) {
        if (!requiresCustodian(packageName) || virtualUserName == null
                || !virtualUserName.startsWith(GROUP_PREFIX)) {
            return null;
        }
        int start = GROUP_PREFIX.length();
        int separator = virtualUserName.indexOf('|', start);
        String candidate = separator >= 0
                ? virtualUserName.substring(start, separator)
                : virtualUserName.substring(start);
        try {
            String canonical = UUID.fromString(candidate).toString();
            return canonical.equals(candidate) ? canonical : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
