package com.lody.virtual.client.hook.proxies.am;

import android.content.Intent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Constrains guest notification-settings intents to the physical host identity. */
final class NotificationSettingsIntentIdentity {
    static final String EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE";
    static final String EXTRA_LEGACY_APP_PACKAGE = "app_package";
    static final String EXTRA_LEGACY_APP_UID = "app_uid";
    static final String EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME";
    static final String EXTRA_UID = "android.intent.extra.UID";

    private static final Set<String> PACKAGE_SCOPED_NOTIFICATION_ACTIONS =
            new HashSet<>(Arrays.asList(
                    "android.settings.APP_NOTIFICATION_SETTINGS",
                    "android.settings.CHANNEL_NOTIFICATION_SETTINGS",
                    "android.settings.APP_NOTIFICATION_BUBBLE_SETTINGS",
                    "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS",
                    // Hidden on some platform releases, but still handled by Settings.
                    "android.settings.CONVERSATION_SETTINGS"
            ));

    private NotificationSettingsIntentIdentity() {
    }

    static boolean rewrite(Intent intent, String guestPackage, String hostPackage, int hostUid) {
        if (intent == null) {
            return false;
        }
        return rewrite(intent.getAction(), guestPackage, hostPackage, hostUid,
                new ExtraWriter() {
                    @Override
                    public void putString(String key, String value) {
                        intent.putExtra(key, value);
                    }

                    @Override
                    public void putInt(String key, int value) {
                        intent.putExtra(key, value);
                    }
                });
    }

    static boolean rewrite(String action, String guestPackage, String hostPackage, int hostUid,
                           ExtraWriter extras) {
        if (!PACKAGE_SCOPED_NOTIFICATION_ACTIONS.contains(action)
                || isEmpty(guestPackage)
                || isEmpty(hostPackage)
                || guestPackage.equals(hostPackage)
                || hostUid < 0
                || extras == null) {
            return false;
        }

        // Always overwrite target identity for these actions. This both fills missing extras and
        // prevents a guest from using the system Settings activity to inspect another package.
        extras.putString(EXTRA_APP_PACKAGE, hostPackage);
        extras.putString(EXTRA_LEGACY_APP_PACKAGE, hostPackage);
        extras.putString(EXTRA_PACKAGE_NAME, hostPackage);
        extras.putInt(EXTRA_LEGACY_APP_UID, hostUid);
        extras.putInt(EXTRA_UID, hostUid);
        return true;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    interface ExtraWriter {
        void putString(String key, String value);

        void putInt(String key, int value);
    }
}
