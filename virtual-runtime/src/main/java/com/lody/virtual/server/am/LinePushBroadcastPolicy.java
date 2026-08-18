package com.lody.virtual.server.am;

import com.lody.virtual.client.stub.VASettings;

/** Narrow policy for starting or thawing cloned LINE before selected broadcasts. */
final class LinePushBroadcastPolicy {
    static final String LINE_PACKAGE = "jp.naver.line.android";
    static final String C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    static final String SCREEN_OFF = "android.intent.action.SCREEN_OFF";
    static final String SCREEN_ON = "android.intent.action.SCREEN_ON";
    static final String USER_PRESENT = "android.intent.action.USER_PRESENT";

    private LinePushBroadcastPolicy() {
    }

    static boolean shouldStart(String packageName, String processName, String action) {
        return LINE_PACKAGE.equals(packageName)
                && LINE_PACKAGE.equals(processName)
                && C2DM_RECEIVE.equals(action);
    }

    static boolean shouldProtect(String packageName, String processName, String action,
                                 int slot, boolean isolatedWorker) {
        return !isolatedWorker
                && slot >= 0
                && slot < VASettings.STUB_COUNT
                && LINE_PACKAGE.equals(packageName)
                && LINE_PACKAGE.equals(processName)
                && isProtectedAction(action);
    }

    private static boolean isProtectedAction(String action) {
        return C2DM_RECEIVE.equals(action)
                || SCREEN_OFF.equals(action)
                || SCREEN_ON.equals(action)
                || USER_PRESENT.equals(action);
    }
}
