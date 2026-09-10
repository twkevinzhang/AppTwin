package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlockingScreenReceiverPolicyTest {
    @Test
    public void suppressesScreenLifecycleActionsInProtectedMainProcesses() {
        String[] actions = {
                "android.intent.action.SCREEN_OFF",
                "android.intent.action.SCREEN_ON",
                "android.intent.action.USER_PRESENT"
        };
        for (String action : actions) {
            assertTrue(BlockingScreenReceiverPolicy.shouldSuppress(
                    "jp.naver.line.android", "jp.naver.line.android", action));
            assertTrue(BlockingScreenReceiverPolicy.shouldSuppress(
                    "com.facebook.katana", "com.facebook.katana", action));
            assertFalse(BlockingScreenReceiverPolicy.shouldSuppress(
                    "jp.naver.line.android", "jp.naver.line.android:push", action));
            assertFalse(BlockingScreenReceiverPolicy.shouldSuppress(
                    "com.facebook.katana", "com.facebook.katana:browser", action));
            assertFalse(BlockingScreenReceiverPolicy.shouldSuppress(
                    "org.mozilla.firefox", "org.mozilla.firefox", action));
        }
    }

    @Test
    public void preservesUnrelatedProtectedPackageBroadcasts() {
        assertFalse(BlockingScreenReceiverPolicy.shouldSuppress(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "android.net.conn.CONNECTIVITY_CHANGE"));
        assertFalse(BlockingScreenReceiverPolicy.shouldSuppress(
                "com.facebook.katana",
                "com.facebook.katana",
                "com.google.android.c2dm.intent.RECEIVE"));
    }
}
