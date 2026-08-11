package com.lody.virtual.client.hook.proxies.keystore;

import java.util.ArrayList;
import java.util.List;

/** Produces a stable physical Android Keystore namespace for one guest package and user. */
public final class KeystoreAliasPolicy {
    private static final String PREFIX = "apptwin:v1:";

    private KeystoreAliasPolicy() {
    }

    public static String toPhysicalAlias(String packageName, int userId, String guestAlias) {
        if (guestAlias == null) return null;
        String ownerPrefix = ownerPrefix(packageName, userId);
        if (guestAlias.startsWith(ownerPrefix)) return guestAlias;
        return ownerPrefix + guestAlias;
    }

    public static String toGuestAlias(String packageName, int userId, String physicalAlias) {
        if (physicalAlias == null) return null;
        String ownerPrefix = ownerPrefix(packageName, userId);
        if (!physicalAlias.startsWith(ownerPrefix)) return null;
        return physicalAlias.substring(ownerPrefix.length());
    }

    public static boolean isOwnedBy(String packageName, int userId, String physicalAlias) {
        return toGuestAlias(packageName, userId, physicalAlias) != null;
    }

    public static boolean isOwnedByUser(int userId, String physicalAlias) {
        if (userId < 0 || physicalAlias == null) return false;
        return physicalAlias.startsWith(PREFIX + "u" + userId + ":p");
    }

    public static List<String> ownedAliases(
            String packageName, int userId, Iterable<String> physicalAliases) {
        List<String> owned = new ArrayList<>();
        if (physicalAliases == null) return owned;
        for (String alias : physicalAliases) {
            if (isOwnedBy(packageName, userId, alias)) owned.add(alias);
        }
        return owned;
    }

    public static List<String> ownedAliasesForUser(
            int userId, Iterable<String> physicalAliases) {
        List<String> owned = new ArrayList<>();
        if (physicalAliases == null) return owned;
        for (String alias : physicalAliases) {
            if (isOwnedByUser(userId, alias)) owned.add(alias);
        }
        return owned;
    }

    static String ownerPrefix(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("Guest package is required");
        }
        if (userId < 0) {
            throw new IllegalArgumentException("Guest user is required");
        }
        return PREFIX + "u" + userId + ":p" + packageName.length() + ":" + packageName + ":";
    }
}
