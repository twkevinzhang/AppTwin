package com.lody.virtual.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransientServicePolicyTest {

    @Test
    public void recreatesOnlyGmsCheckinIntentOperation() {
        String service = "com.google.android.gms.chimera.GmsIntentOperationService";
        String action = "com.google.android.gms.checkin.CHECKIN_START_ACTION";

        assertTrue(TransientServicePolicy.shouldRecreate("com.google.android.gms", service, action));
        assertFalse(TransientServicePolicy.shouldRecreate("com.google.android.gms", service,
                "com.google.android.chimera.container.IntentOperationService.SAVED_INTENT"));
        assertFalse(TransientServicePolicy.shouldRecreate("example.guest", service, action));
    }
}
