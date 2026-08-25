package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class NotificationSettingsIntentIdentityTest {
    private static final String GUEST_PACKAGE = "jp.naver.line.android";
    private static final String HOST_PACKAGE = "org.apptwin";
    private static final int HOST_UID = 10123;

    @Test
    public void channelSettingsRewritesGuestIdentityAndPreservesChannelAndConversation() {
        MapExtras extras = new MapExtras();
        extras.values.put(NotificationSettingsIntentIdentity.EXTRA_APP_PACKAGE, GUEST_PACKAGE);
        extras.values.put("android.provider.extra.CHANNEL_ID", "new-messages");
        extras.values.put("android.provider.extra.CONVERSATION_ID", "friend-42");

        assertTrue(rewrite("android.settings.CHANNEL_NOTIFICATION_SETTINGS", extras));

        assertHostIdentity(extras);
        assertEquals("new-messages",
                extras.values.get("android.provider.extra.CHANNEL_ID"));
        assertEquals("friend-42",
                extras.values.get("android.provider.extra.CONVERSATION_ID"));
    }

    @Test
    public void appSettingsOverwritesWrongPackageInsteadOfAllowingArbitraryTarget() {
        MapExtras extras = new MapExtras();
        extras.values.put(NotificationSettingsIntentIdentity.EXTRA_APP_PACKAGE,
                "com.example.victim");
        extras.values.put(NotificationSettingsIntentIdentity.EXTRA_LEGACY_APP_PACKAGE,
                "com.example.victim");
        extras.values.put(NotificationSettingsIntentIdentity.EXTRA_LEGACY_APP_UID, 4242);

        assertTrue(rewrite("android.settings.APP_NOTIFICATION_SETTINGS", extras));

        assertHostIdentity(extras);
    }

    @Test
    public void appSettingsAddsIdentityWhenCallerOmittedAllPackageExtras() {
        MapExtras extras = new MapExtras();

        assertTrue(rewrite("android.settings.APP_NOTIFICATION_BUBBLE_SETTINGS", extras));

        assertHostIdentity(extras);
    }

    @Test
    public void nonNotificationSettingsActionIsUntouched() {
        MapExtras extras = new MapExtras();
        extras.values.put(NotificationSettingsIntentIdentity.EXTRA_APP_PACKAGE, GUEST_PACKAGE);

        assertFalse(rewrite("android.settings.APPLICATION_DETAILS_SETTINGS", extras));

        assertEquals(GUEST_PACKAGE,
                extras.values.get(NotificationSettingsIntentIdentity.EXTRA_APP_PACKAGE));
        assertEquals(1, extras.values.size());
    }

    private static boolean rewrite(String action, MapExtras extras) {
        return NotificationSettingsIntentIdentity.rewrite(
                action, GUEST_PACKAGE, HOST_PACKAGE, HOST_UID, extras);
    }

    private static void assertHostIdentity(MapExtras extras) {
        assertEquals(HOST_PACKAGE,
                extras.values.get(NotificationSettingsIntentIdentity.EXTRA_APP_PACKAGE));
        assertEquals(HOST_PACKAGE,
                extras.values.get(NotificationSettingsIntentIdentity.EXTRA_LEGACY_APP_PACKAGE));
        assertEquals(HOST_PACKAGE,
                extras.values.get(NotificationSettingsIntentIdentity.EXTRA_PACKAGE_NAME));
        assertEquals(HOST_UID,
                extras.values.get(NotificationSettingsIntentIdentity.EXTRA_LEGACY_APP_UID));
        assertEquals(HOST_UID,
                extras.values.get(NotificationSettingsIntentIdentity.EXTRA_UID));
    }

    private static final class MapExtras
            implements NotificationSettingsIntentIdentity.ExtraWriter {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            values.put(key, value);
        }
    }
}
