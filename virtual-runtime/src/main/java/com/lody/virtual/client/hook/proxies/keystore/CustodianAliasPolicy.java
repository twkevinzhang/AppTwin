package com.lody.virtual.client.hook.proxies.keystore;

import java.util.ArrayList;
import java.util.List;

/** Namespaces Custodian-owned keys by stable Group and guest package identity. */
public final class CustodianAliasPolicy {
    private static final String PREFIX = "apptwin:c:v1:";

    private CustodianAliasPolicy() {
    }

    public static String toPhysicalAlias(String ownerId, String packageName, String guestAlias) {
        if (guestAlias == null) return null;
        String ownerPrefix = ownerPrefix(ownerId, packageName);
        if (guestAlias.startsWith(ownerPrefix)) return guestAlias;
        return ownerPrefix + guestAlias;
    }

    public static String toGuestAlias(String ownerId, String packageName, String physicalAlias) {
        if (physicalAlias == null) return null;
        String ownerPrefix = ownerPrefix(ownerId, packageName);
        if (!physicalAlias.startsWith(ownerPrefix)) return null;
        return physicalAlias.substring(ownerPrefix.length());
    }

    public static boolean isOwnedBy(String ownerId, String packageName, String physicalAlias) {
        return toGuestAlias(ownerId, packageName, physicalAlias) != null;
    }

    public static List<String> ownedAliasesForKeyspace(
            String ownerId, Iterable<String> physicalAliases) {
        String keyspacePrefix = PREFIX + "g" + requireOwner(ownerId) + ":p";
        List<String> owned = new ArrayList<>();
        if (physicalAliases == null) return owned;
        for (String alias : physicalAliases) {
            if (alias != null && alias.startsWith(keyspacePrefix)) owned.add(alias);
        }
        return owned;
    }

    private static String ownerPrefix(String ownerId, String packageName) {
        requireOwner(ownerId);
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("Guest package is required");
        }
        return PREFIX + "g" + ownerId + ":p" + packageName.length() + ":" + packageName + ":";
    }

    private static String requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isEmpty()) {
            throw new IllegalArgumentException("Stable owner is required");
        }
        return ownerId;
    }
}
