package com.lody.virtual.client.hook.proxies.notification;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NotificationSpaceLabelerTest {
    @Test
    public void encodedEnvironmentNameExposesOnlyDisplayName() {
        assertEquals(
                "AppTwin · 工作",
                NotificationSpaceLabeler.label(
                        "AppTwin:group:00000000-0000-0000-0000-000000000001|工作",
                        null));
    }

    @Test
    public void legacyEnvironmentNameDoesNotExposeStableIdentifier() {
        assertEquals(
                "AppTwin 分身空間",
                NotificationSpaceLabeler.label(
                        "AppTwin:group:00000000-0000-0000-0000-000000000001",
                        null));
    }

    @Test
    public void includesSpaceNameWithoutReadingNotificationBody() {
        assertEquals("AppTwin · 工作", NotificationSpaceLabeler.label("工作", null));
    }

    @Test
    public void preservesExistingSubTextAfterSpaceIdentity() {
        assertEquals(
                "AppTwin · 私人 · 2 則新訊息",
                NotificationSpaceLabeler.label("私人", "2 則新訊息"));
    }

    @Test
    public void missingNameStillIdentifiesAppTwinSpace() {
        assertEquals("AppTwin 分身空間", NotificationSpaceLabeler.label(" ", ""));
    }
}
