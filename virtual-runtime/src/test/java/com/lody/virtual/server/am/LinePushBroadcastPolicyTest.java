package com.lody.virtual.server.am;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinePushBroadcastPolicyTest {
    @Test
    public void startsAndProtectsOnlyLineMainProcessForC2dmReceive() {
        assertTrue(LinePushBroadcastPolicy.shouldStart(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "com.google.android.c2dm.intent.RECEIVE"));
        assertTrue(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "com.google.android.c2dm.intent.RECEIVE",
                3,
                false));

        assertFalse(LinePushBroadcastPolicy.shouldStart(
                "com.discord",
                "com.discord",
                "com.google.android.c2dm.intent.RECEIVE"));
        assertFalse(LinePushBroadcastPolicy.shouldStart(
                "jp.naver.line.android",
                "jp.naver.line.android:secondary",
                "com.google.android.c2dm.intent.RECEIVE"));
        assertFalse(LinePushBroadcastPolicy.shouldStart(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "android.intent.action.BOOT_COMPLETED"));
    }

    @Test
    public void protectsLineMainProcessForScreenLifecycleBroadcastsWithoutStartingIt() {
        String[] actions = {
                "android.intent.action.SCREEN_OFF",
                "android.intent.action.SCREEN_ON",
                "android.intent.action.USER_PRESENT"
        };

        for (String action : actions) {
            assertTrue(LinePushBroadcastPolicy.shouldProtect(
                    "jp.naver.line.android",
                    "jp.naver.line.android",
                    action,
                    3,
                    false));
            assertFalse(LinePushBroadcastPolicy.shouldStart(
                    "jp.naver.line.android",
                    "jp.naver.line.android",
                    action));
        }
    }

    @Test
    public void screenLifecycleProtectionRejectsOtherPackagesAndSecondaryProcesses() {
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "com.discord",
                "com.discord",
                "android.intent.action.SCREEN_ON",
                3,
                false));
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android:secondary",
                "android.intent.action.SCREEN_ON",
                3,
                false));
    }

    @Test
    public void rejectsIsolatedOrUndeclaredSlots() {
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "com.google.android.c2dm.intent.RECEIVE",
                -1,
                false));
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "com.google.android.c2dm.intent.RECEIVE",
                50,
                false));
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "com.google.android.c2dm.intent.RECEIVE",
                3,
                true));

        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "android.intent.action.USER_PRESENT",
                -1,
                false));
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "android.intent.action.USER_PRESENT",
                50,
                false));
        assertFalse(LinePushBroadcastPolicy.shouldProtect(
                "jp.naver.line.android",
                "jp.naver.line.android",
                "android.intent.action.USER_PRESENT",
                3,
                true));
    }
}
