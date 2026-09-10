package com.lody.virtual.client.hook.proxies.am;

import android.content.Intent;

/** Prevents known synchronous guest screen receivers from consuming Android's broadcast deadline. */
final class BlockingScreenReceiverPolicy {
    private static final String LINE_PACKAGE = "jp.naver.line.android";
    private static final String FACEBOOK_PACKAGE = "com.facebook.katana";

    private BlockingScreenReceiverPolicy() {
    }

    static boolean shouldSuppress(String packageName, String processName, String action) {
        return isProtectedMainProcess(packageName, processName)
                && (Intent.ACTION_SCREEN_OFF.equals(action)
                || Intent.ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action));
    }

    private static boolean isProtectedMainProcess(String packageName, String processName) {
        return (LINE_PACKAGE.equals(packageName) && LINE_PACKAGE.equals(processName))
                || (FACEBOOK_PACKAGE.equals(packageName)
                    && FACEBOOK_PACKAGE.equals(processName));
    }
}
