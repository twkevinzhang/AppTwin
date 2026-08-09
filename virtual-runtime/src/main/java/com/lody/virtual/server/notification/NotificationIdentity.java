package com.lody.virtual.server.notification;

/**
 * Stable host-side identity for a notification emitted by a virtual package/user pair.
 *
 * <p>Android keys an untagged notification by host package and integer id. All guests run under
 * the same host package, so untagged calls receive a deterministic synthetic tag. Unlike a
 * 32-bit hash folded into the integer id, the full package/user tuple cannot collide merely
 * because guest ids differ by a linear offset.</p>
 */
public final class NotificationIdentity {

    private NotificationIdentity() {
    }

    public static String namespaceUntaggedTag(String packageName, int userId) {
        if (packageName == null) return null;
        return "apptwin:untagged:" + packageName.length() + ":" + packageName + ":" + userId;
    }
}
