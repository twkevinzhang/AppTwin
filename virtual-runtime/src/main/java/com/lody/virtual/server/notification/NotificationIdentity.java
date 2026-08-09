package com.lody.virtual.server.notification;

/**
 * Stable host-side identity for a notification emitted by a virtual package/user pair.
 *
 * <p>Android keys an untagged notification by host package and integer id. All guests run under
 * the same host package, so forwarding the guest id unchanged lets one space overwrite or cancel
 * another space's notification. Tagged notifications are already isolated by
 * {@link VNotificationManagerService#dealNotificationTag(int, String, String, int)}; this helper
 * provides the equivalent namespace for legacy untagged calls.</p>
 */
public final class NotificationIdentity {

    private NotificationIdentity() {
    }

    public static int namespaceUntaggedId(int guestId, String packageName, int userId) {
        if (packageName == null) {
            return guestId;
        }
        int hash = 17;
        hash = 31 * hash + packageName.hashCode();
        hash = 31 * hash + userId;
        hash = 31 * hash + guestId;
        // Avoid the sentinel used by several framework notification paths.
        return hash == 0 ? 1 : hash;
    }
}
