package com.lody.virtual.client.hook.providers;

/** ProviderInfo.authority may contain aliases, and acquired URIs may have a user prefix. */
final class ProviderAuthorityPolicy {
    private ProviderAuthorityPolicy() {}
    static String normalize(String authority) {
        if (authority == null) return null;
        int userSeparator = authority.indexOf('@');
        return userSeparator < 0 ? authority : authority.substring(userSeparator + 1);
    }
    static boolean contains(String authorities, String expected) {
        if (authorities == null || expected == null) return false;
        for (String authority : authorities.split(";")) {
            if (expected.equals(normalize(authority))) return true;
        }
        return false;
    }
    static boolean contacts(String authorities) {
        return contains(authorities, "com.android.contacts") || contains(authorities, "contacts");
    }
}
