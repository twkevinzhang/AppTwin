package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LineScreenReceiverPolicyTest {
    @Test
    public void suppressesScreenLifecycleActionsOnlyInLineMainProcess() {
        String[] actions = {
                "android.intent.action.SCREEN_OFF",
                "android.intent.action.SCREEN_ON",
                "android.intent.action.USER_PRESENT"
        };
        for (String action : actions) {
            assertTrue(LineScreenReceiverPolicy.shouldSuppress(
                    "jp.naver.line.android", "jp.naver.line.android", action));
            assertFalse(LineScreenReceiverPolicy.shouldSuppress(
                    "jp.naver.line.android", "jp.naver.line.android:push", action));
            assertFalse(LineScreenReceiverPolicy.shouldSuppress(
                    "org.mozilla.firefox", "org.mozilla.firefox", action));
        }
    }

    @Test
    public void preservesUnrelatedLineBroadcasts() {
        assertFalse(LineScreenReceiverPolicy.shouldSuppress(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "android.net.conn.CONNECTIVITY_CHANGE"));
    }
}
