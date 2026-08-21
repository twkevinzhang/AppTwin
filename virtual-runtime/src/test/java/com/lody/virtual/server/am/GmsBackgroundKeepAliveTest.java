package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;

public class GmsBackgroundKeepAliveTest {

    @Test
    public void retainsMicrogMessagingAndFirefoxGeckoChildProcesses() {
        assertTrue(GmsBackgroundKeepAlive.shouldRetain(
                "com.google.android.gms", "com.google.android.gms:persistent", 7, false));
        assertTrue(GmsBackgroundKeepAlive.shouldRetain(
                "com.google.android.gms", "com.google.android.gms", 7, false));
        assertTrue(GmsBackgroundKeepAlive.shouldRetain(
                "org.mozilla.firefox", "org.mozilla.firefox:tab_disable_art_image_11", 7,
                false));
        assertTrue(GmsBackgroundKeepAlive.shouldRetain(
                "org.mozilla.firefox", "org.mozilla.firefox:gpu_disable_art_image_", 7,
                false));
        assertTrue(GmsBackgroundKeepAlive.shouldRetain(
                "org.mozilla.firefox", "org.mozilla.firefox:media", 7, false));
        assertTrue(GmsBackgroundKeepAlive.shouldRetain(
                "org.mozilla.firefox", "org.mozilla.firefox:utility_disable_art_image_", 7,
                false));
        assertFalse(GmsBackgroundKeepAlive.shouldRetain(
                "com.google.android.gms", "com.google.android.gms:ui", 7, false));
        assertFalse(GmsBackgroundKeepAlive.shouldRetain(
                "org.mozilla.firefox", "org.mozilla.firefox:crashhelper_disable_art_image_",
                7, false));
        assertFalse(GmsBackgroundKeepAlive.shouldRetain(
                "jp.naver.line.android", "jp.naver.line.android", 7, false));
        assertFalse(GmsBackgroundKeepAlive.shouldRetain(
                "com.google.android.gms", "com.google.android.gms:persistent", 7, true));
    }

    @Test
    public void rejectsSlotsOutsideDeclaredStubRange() {
        assertFalse(GmsBackgroundKeepAlive.shouldRetain(
                "com.google.android.gms", "com.google.android.gms:persistent", -1, false));
        assertFalse(GmsBackgroundKeepAlive.shouldRetain(
                "com.google.android.gms", "com.google.android.gms:persistent", 50, false));
    }

    @Test
    public void bindingIsAutoCreatedAndImportant() {
        int flags = GmsBackgroundKeepAlive.bindingFlags();
        assertTrue((flags & Context.BIND_AUTO_CREATE) != 0);
        assertTrue((flags & Context.BIND_IMPORTANT) != 0);
    }
}
