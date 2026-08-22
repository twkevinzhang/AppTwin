package org.apptwin.ui

import org.apptwin.gms.ports.CloudMessagingState
import org.junit.Assert.assertEquals
import org.junit.Test

class GmsStatusLabelsTest {
    @Test
    fun `cloud messaging phases have distinct honest labels`() {
        assertEquals("已停用", cloudMessagingLabel(CloudMessagingState.DISABLED))
        assertEquals("連線中", cloudMessagingLabel(CloudMessagingState.STARTING))
        assertEquals("已連線", cloudMessagingLabel(CloudMessagingState.CONNECTED))
        assertEquals("需要處理", cloudMessagingLabel(CloudMessagingState.DEGRADED))
        assertEquals("未知", cloudMessagingLabel(CloudMessagingState.UNKNOWN))
        assertEquals("未知", cloudMessagingLabel(null))
    }
}
