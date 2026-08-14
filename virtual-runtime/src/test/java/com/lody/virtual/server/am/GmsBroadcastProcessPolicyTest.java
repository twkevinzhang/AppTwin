package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmsBroadcastProcessPolicyTest {

    @Test
    public void startsMicrogPersistentProcessForServiceInfoRequest() {
        assertTrue(GmsBroadcastProcessPolicy.shouldStart(
                "com.google.android.gms",
                "org.microg.gms.gcm.ServiceInfoReceiver",
                "org.microg.gms.gcm.SERVICE_INFO_REQUEST"));
    }

    @Test
    public void doesNotStartForOtherActionsReceiversOrPackages() {
        assertFalse(GmsBroadcastProcessPolicy.shouldStart(
                "com.google.android.gms",
                "org.microg.gms.gcm.ServiceInfoReceiver",
                "com.google.android.c2dm.intent.RECEIVE"));
        assertFalse(GmsBroadcastProcessPolicy.shouldStart(
                "com.google.android.gms",
                "org.microg.gms.gcm.TriggerReceiver",
                "org.microg.gms.gcm.SERVICE_INFO_REQUEST"));
        assertFalse(GmsBroadcastProcessPolicy.shouldStart(
                "jp.naver.line.android",
                "org.microg.gms.gcm.ServiceInfoReceiver",
                "org.microg.gms.gcm.SERVICE_INFO_REQUEST"));
    }
}
