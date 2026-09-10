package com.lody.virtual.server.am;

import android.content.ComponentName;

import com.lody.virtual.client.stub.VASettings;

/** Exact allowlist for removing unrecoverable physical guest tasks. */
final class OrphanStubTaskPolicy {
    private OrphanStubTaskPolicy() {
    }

    static boolean shouldRemove(String hostPackage, ComponentName base, ComponentName top,
            boolean hasLiveOwnership, int stubCount) {
        return shouldRemove(hostPackage,
                base == null ? null : base.getPackageName(),
                base == null ? null : base.getClassName(),
                top == null ? null : top.getPackageName(),
                top == null ? null : top.getClassName(),
                hasLiveOwnership, stubCount);
    }

    static boolean shouldRemove(String hostPackage, String basePackage, String baseClass,
            String topPackage, String topClass, boolean hasLiveOwnership, int stubCount) {
        if (hasLiveOwnership || hostPackage == null || stubCount <= 0) return false;
        return isExactStubActivity(hostPackage, basePackage, baseClass, stubCount)
                || isExactStubActivity(hostPackage, topPackage, topClass, stubCount);
    }

    static boolean isExactStubActivity(String hostPackage, String componentPackage,
            String className,
            int stubCount) {
        if (!hostPackage.equals(componentPackage) || className == null) return false;
        String suffix = slotSuffix(className, VASettings.STUB_ACTIVITY);
        if (suffix == null) suffix = slotSuffix(className, VASettings.STUB_DIALOG);
        if (suffix == null) {
            suffix = slotSuffix(className, VASettings.STUB_EXCLUDE_FROM_RECENT_ACTIVITY);
        }
        if (suffix == null) return false;
        if (suffix.length() == 0) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        try {
            int slot = Integer.parseInt(suffix);
            return slot >= 0 && slot < stubCount;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String slotSuffix(String className, String stubClassName) {
        String prefix = stubClassName + "$C";
        return className.startsWith(prefix) ? className.substring(prefix.length()) : null;
    }
}
