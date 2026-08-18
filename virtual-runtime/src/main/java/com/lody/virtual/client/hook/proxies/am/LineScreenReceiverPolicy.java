package com.lody.virtual.client.hook.proxies.am;

import android.content.Intent;

/** Prevents LINE's synchronous screen receiver from consuming Android's broadcast deadline. */
final class LineScreenReceiverPolicy {
    private static final String LINE_PACKAGE = "jp.naver.line.android";

    private LineScreenReceiverPolicy() {
    }

    static boolean shouldSuppress(String packageName, String processName, String action) {
        return LINE_PACKAGE.equals(packageName)
                && LINE_PACKAGE.equals(processName)
                && (Intent.ACTION_SCREEN_OFF.equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action));
    }
}
