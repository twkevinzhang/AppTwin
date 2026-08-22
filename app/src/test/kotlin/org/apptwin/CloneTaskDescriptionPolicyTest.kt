package org.apptwin

import org.junit.Assert.assertEquals
import org.junit.Test

class CloneTaskDescriptionPolicyTest {
    @Test
    fun recentTaskLabelIsAlwaysAppTwin() {
        assertEquals("AppTwin", CloneTaskDescriptionPolicy.RECENT_TASK_LABEL)
    }
}
