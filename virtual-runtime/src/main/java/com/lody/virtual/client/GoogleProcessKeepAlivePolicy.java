package com.lody.virtual.client;

import com.lody.virtual.client.stub.StubProcessKeepAliveService;

final class GoogleProcessKeepAlivePolicy {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    private GoogleProcessKeepAlivePolicy() {
    }

    static boolean shouldKeepAlive(String packageName, String processName) {
        return GMS_PACKAGE.equals(packageName) || PLAY_STORE_PACKAGE.equals(packageName);
    }

    static String serviceClassNameForProcess(String hostPackage, String hostProcessName,
                                             int stubCount) {
        if (hostPackage == null || hostProcessName == null || stubCount <= 0) {
            return null;
        }
        String prefix = hostPackage + ":p";
        if (!hostProcessName.startsWith(prefix)) {
            return null;
        }
        try {
            int slot = Integer.parseInt(hostProcessName.substring(prefix.length()));
            if (slot < 0 || slot >= stubCount) {
                return null;
            }
            return StubProcessKeepAliveService.class.getName() + "$C" + slot;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
