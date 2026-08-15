package com.lody.virtual.server.am;

import com.lody.virtual.client.stub.VASettings;

/** Narrow policy for waking cloned LINE before delivering a package-scoped FCM broadcast. */
final class LinePushBroadcastPolicy {
    static final String LINE_PACKAGE = "jp.naver.line.android";
    static final String C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";

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
                && shouldStart(packageName, processName, action);
    }
}
