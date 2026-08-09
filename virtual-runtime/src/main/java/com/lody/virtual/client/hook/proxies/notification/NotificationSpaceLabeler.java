package com.lody.virtual.client.hook.proxies.notification;

/** Produces a stable visible origin label without inspecting notification content. */
final class NotificationSpaceLabeler {
    private NotificationSpaceLabeler() {
    }

    static CharSequence label(String spaceName, CharSequence existingSubText) {
        String normalized = displayName(spaceName);
        String prefix = normalized.isEmpty() ? "AppTwin 分身空間" : "AppTwin · " + normalized;
        if (existingSubText == null || existingSubText.toString().trim().isEmpty()) {
            return prefix;
        }
        return prefix + " · " + existingSubText;
    }

    static String displayName(String storedUserName) {
        String normalized = storedUserName == null ? "" : storedUserName.trim();
        if (!normalized.startsWith(ENVIRONMENT_PREFIX)) {
            return normalized;
        }
        int separator = normalized.indexOf('|', ENVIRONMENT_PREFIX.length());
        return separator < 0 ? "" : normalized.substring(separator + 1).trim();
    }

    private static final String ENVIRONMENT_PREFIX = "AppTwin:group:";
}
