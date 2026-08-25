package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrustedGmsMcsReconnectSignalPolicyTest {
    private static final String GMS = TrustedGmsCloudMessagingSupervisor.GMS_PACKAGE;
    private static final String PERSISTENT =
            TrustedGmsCloudMessagingSupervisor.GMS_PERSISTENT_PROCESS;
    private static final String MCS = TrustedGmsCloudMessagingSupervisor.MCS_SERVICE;
    private static final String CONNECT = TrustedGmsCloudMessagingSupervisor.ACTION_MCS_CONNECT;

    @Test
    public void acceptsPinnedMicrogReconnectAndConnectivityTriggers() {
        assertTrue(allowed(TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT));
        assertTrue(allowed(TrustedGmsMcsReconnectSignalPolicy.TRIGGER_CONNECTIVITY_CHANGE));
    }

    @Test
    public void rejectsSupervisorForcedAndUnreviewedTriggers() {
        assertFalse(allowed("apptwin-supervisor"));
        assertFalse(allowed("org.microg.gms.gcm.FORCE_TRY_RECONNECT"));
        assertFalse(allowed("android.intent.action.BOOT_COMPLETED"));
        assertFalse(allowed("android.provider.Telephony.SECRET_CODE"));
        assertFalse(allowed(null));
    }

    @Test
    public void rejectsSpoofedCallerOrTargetMetadata() {
        assertFalse(TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(
                "com.example.clone", PERSISTENT, GMS, MCS, CONNECT,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT));
        assertFalse(TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(
                GMS, "com.google.android.gms", GMS, MCS, CONNECT,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT));
        assertFalse(TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(
                GMS, PERSISTENT, "com.example.clone", MCS, CONNECT,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT));
        assertFalse(TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(
                GMS, PERSISTENT, GMS, "org.microg.gms.gcm.OtherService", CONNECT,
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT));
        assertFalse(TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(
                GMS, PERSISTENT, GMS, MCS, "org.microg.gms.gcm.mcs.HEARTBEAT",
                TrustedGmsMcsReconnectSignalPolicy.TRIGGER_RECONNECT));
    }

    private static boolean allowed(String reason) {
        return TrustedGmsMcsReconnectSignalPolicy.isTrustedReconnectSignal(
                GMS, PERSISTENT, GMS, MCS, CONNECT, reason);
    }
}
