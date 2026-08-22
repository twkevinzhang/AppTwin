package org.apptwin.gms.ports

import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsRuntimeObservationTest {
    @Test
    fun `legacy construction defaults cloud messaging to unknown`() {
        val observation = GmsRuntimeObservation(
            GmsGroupId("group-a"),
            installed = true,
            releaseId = "release-1",
            privateStatePresent = true,
        )

        assertEquals(CloudMessagingState.UNKNOWN, observation.cloudMessaging.state)
        assertTrue(observation.satisfies(GmsDesiredState.ENABLED, "release-1"))
    }

    @Test
    fun `disabled suspension may retain private state but destructive reset may not`() {
        val suspended = GmsRuntimeObservation(
            groupId = GmsGroupId("group-a"),
            installed = false,
            privateStatePresent = true,
        )

        assertTrue(suspended.satisfies(GmsDesiredState.DISABLED, null))
        assertFalse(suspended.isFullyAbsent())
        assertTrue(
            GmsRuntimeObservation(GmsGroupId("group-a"), installed = false).isFullyAbsent(),
        )
    }
}
