package com.lody.virtual.client;

final class GoogleProcessKeepAlivePolicy {
    private GoogleProcessKeepAlivePolicy() {
    }

    static boolean shouldKeepAlive(String packageName, String processName) {
        return "com.google.android.gms".equals(packageName)
                && "com.google.android.gms".equals(processName);
    }
}
