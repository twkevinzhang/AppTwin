package org.apptwin.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState

class ClonePermissionRequestIdentityTest {
    private val request = ClonePermissionRequestIdentity("group-a", "org.fixture", "camera", "launch-a", 4, 100L)

    @Test
    fun `launch metadata change retains membership while replacement member is rejected`() {
        val captured = GroupApp("org.fixture", 100L, GroupAppState.ADDED)
        assertTrue(sameClonePermissionMembership(captured, captured.copy(state = GroupAppState.ENABLED)))
        assertTrue(sameClonePermissionMembership(captured, captured.copy(state = GroupAppState.INSTALLING)))
        assertFalse(sameClonePermissionMembership(captured, captured.copy(addedAtEpochMillis = 101L)))
        assertFalse(sameClonePermissionMembership(captured, captured.copy(packageName = "org.other")))
    }

    @Test
    fun `result retains original request scope and rejects replacement binding`() {
        assertTrue(request.matches("group-a", "org.fixture", "camera", "launch-a", 4, 100L))
        assertFalse(request.matches("group-b", "org.fixture", "camera", "launch-a", 4, 100L))
        assertFalse(request.matches("group-a", "org.other", "camera", "launch-a", 4, 100L))
        assertFalse(request.matches("group-a", "org.fixture", "microphone", "launch-a", 4, 100L))
        assertFalse(request.matches("group-a", "org.fixture", "camera", "launch-b", 4, 100L))
        assertFalse(request.matches("group-a", "org.fixture", "camera", "launch-a", 5, 100L))
        assertFalse(request.matches("group-a", "org.fixture", "camera", "launch-a", 4, 101L))
    }

    @Test
    fun `host revoke rejects guest grant but does not block guest revocation`() {
        assertFalse(canApplyClonePermission(granted = true, platformExempt = false, hostGranted = false))
        assertTrue(canApplyClonePermission(granted = true, platformExempt = false, hostGranted = true))
        assertTrue(canApplyClonePermission(granted = false, platformExempt = false, hostGranted = false))
    }

    @Test
    fun `platform exempt remains idempotent grant only`() {
        assertTrue(canApplyClonePermission(granted = true, platformExempt = true, hostGranted = false))
        assertFalse(canApplyClonePermission(granted = false, platformExempt = true, hostGranted = true))
    }
}
