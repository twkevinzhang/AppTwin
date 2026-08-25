package com.lody.virtual.server.am;

/**
 * Fail-closed classifier for the pinned microG signal that its MCS session needs reconnecting.
 *
 * <p>Binder identity, virtual-user ownership and process generation are runtime facts and must be
 * verified by {@link VActivityManagerService} before consulting this metadata-only policy.</p>
 */
final class TrustedGmsMcsReconnectSignalPolicy {
    static final String EXTRA_MCS_REASON = "org.microg.gms.gcm.mcs.REASON";
    static final String TRIGGER_RECONNECT = "org.microg.gms.gcm.mcs.RECONNECT";
    static final String TRIGGER_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";

    private TrustedGmsMcsReconnectSignalPolicy() {
    }

    static boolean isTrustedReconnectSignal(String callerPackage, String callerProcessName,
            String targetPackage, String targetClassName, String action, String triggerReason) {
        return TrustedGmsCloudMessagingSupervisor.GMS_PACKAGE.equals(callerPackage)
                && TrustedGmsCloudMessagingSupervisor.GMS_PERSISTENT_PROCESS.equals(
                        callerProcessName)
                && TrustedGmsCloudMessagingSupervisor.GMS_PACKAGE.equals(targetPackage)
                && TrustedGmsCloudMessagingSupervisor.MCS_SERVICE.equals(targetClassName)
                && TrustedGmsCloudMessagingSupervisor.ACTION_MCS_CONNECT.equals(action)
                && isSelfHealingTrigger(triggerReason);
    }

    private static boolean isSelfHealingTrigger(String triggerReason) {
        return TRIGGER_RECONNECT.equals(triggerReason)
                || TRIGGER_CONNECTIVITY_CHANGE.equals(triggerReason);
    }
}
