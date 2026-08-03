package org.maskaccounts.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleRuntimeBootstrapTest {
    @Test
    fun checkinTargetsOriginalGuestGmsIntentOperation() {
        assertEquals(
            "com.google.android.gms.checkin.CHECKIN_START_ACTION",
            GoogleRuntimeBootstrap.CHECKIN_ACTION,
        )
        assertEquals("com.google.android.gms", GoogleRuntimeBootstrap.GMS_PACKAGE)
        assertEquals(
            "com.google.android.gms.chimera.GmsIntentOperationService",
            GoogleRuntimeBootstrap.GMS_INTENT_OPERATION_SERVICE,
        )
        assertEquals(
            "targeted_intent_op_prefix:.checkin.CheckinIntentOperation",
            GoogleRuntimeBootstrap.CHECKIN_OPERATION_CATEGORY,
        )
    }
}
