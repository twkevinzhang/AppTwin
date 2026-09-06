package com.lody.virtual.client.stub;

/** Pure decision policy for publishing the daemon foreground-service notification. */
final class DaemonForegroundNotificationPolicy {
    private DaemonForegroundNotificationPolicy() {
    }

    static boolean shouldPublish(
            boolean foreground,
            boolean activeNotificationInspectionSupported,
            boolean daemonNotificationActive) {
        if (!foreground) {
            return true;
        }
        return activeNotificationInspectionSupported && !daemonNotificationActive;
    }
}
