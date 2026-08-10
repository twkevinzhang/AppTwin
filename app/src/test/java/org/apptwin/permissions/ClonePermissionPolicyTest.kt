package org.apptwin.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClonePermissionPolicyTest {
    @Test
    fun `same permission is aggregated once and affected clones are deduplicated`() {
        val firstClone = target("group-1", "工作", "com.example.camera", "Camera")
        val secondClone = target("group-2", "個人", "com.example.chat", "Chat")

        val summaries = ClonePermissionPolicy.aggregate(
            listOf(
                requirement(CAMERA, firstClone),
                requirement(CAMERA, firstClone),
                requirement(CAMERA, secondClone),
            ),
        )

        assertEquals(1, summaries.size)
        assertEquals(CAMERA, summaries.single().permission)
        assertEquals("CAMERA", summaries.single().label)
        assertEquals(listOf(firstClone, secondClone), summaries.single().affectedClones)
        assertEquals(ClonePermissionVirtualScope.CAMERA_MIC_PER_SPACE, summaries.single().virtualScope)
    }

    @Test
    fun `permissions requiring an action are sorted before resolved permissions`() {
        val clone = target("group-1", "工作", "com.example.chat", "Chat")

        val summaries = ClonePermissionPolicy.aggregate(
            listOf(
                requirement(INTERNET, clone, category = ClonePermissionCategory.AUTOMATIC),
                requirement(CAMERA, clone, hostGranted = false, canRequestRuntime = true),
                requirement(UNSUPPORTED, clone, category = ClonePermissionCategory.UNSUPPORTED),
                requirement(
                    OVERLAY,
                    clone,
                    category = ClonePermissionCategory.SPECIAL,
                    hostGranted = false,
                ),
            ),
        )

        assertEquals(listOf(CAMERA, OVERLAY, INTERNET, UNSUPPORTED), summaries.map { it.permission })
        assertTrue(summaries[0].actionRequired)
        assertTrue(summaries[1].actionRequired)
        assertFalse(summaries[2].actionRequired)
        assertFalse(summaries[3].actionRequired)
    }

    @Test
    fun `missing runtime permission requests directly when request is available`() {
        val summary = aggregateOne(
            requirement(
                CAMERA,
                target("group-1", "工作", "com.example.camera", "Camera"),
                hostGranted = false,
                canRequestRuntime = true,
            ),
        )

        assertFalse(summary.granted)
        assertEquals(ClonePermissionAction.REQUEST_RUNTIME, summary.action)
    }

    @Test
    fun `missing runtime permission opens app details when direct request is unavailable`() {
        val summary = aggregateOne(
            requirement(
                CAMERA,
                target("group-1", "工作", "com.example.camera", "Camera"),
                hostGranted = false,
                canRequestRuntime = false,
            ),
        )

        assertEquals(ClonePermissionAction.OPEN_APP_DETAILS, summary.action)
    }

    @Test
    fun `special automatic and unsupported categories expose their matching state and action`() {
        val clone = target("group-1", "工作", "com.example.chat", "Chat")

        val special = aggregateOne(
            requirement(
                OVERLAY,
                clone,
                category = ClonePermissionCategory.SPECIAL,
                hostGranted = false,
            ),
        )
        val automatic = aggregateOne(
            requirement(
                INTERNET,
                clone,
                category = ClonePermissionCategory.AUTOMATIC,
                hostGranted = false,
            ),
        )
        val unsupported = aggregateOne(
            requirement(
                UNSUPPORTED,
                clone,
                category = ClonePermissionCategory.UNSUPPORTED,
                hostGranted = false,
            ),
        )

        assertEquals(ClonePermissionAction.OPEN_SPECIAL_SETTINGS, special.action)
        assertTrue(automatic.granted)
        assertEquals(ClonePermissionAction.NONE, automatic.action)
        assertFalse(unsupported.granted)
        assertEquals(ClonePermissionAction.NONE, unsupported.action)
    }

    @Test
    fun `granted actionable permission has no action`() {
        val summary = aggregateOne(
            requirement(
                CAMERA,
                target("group-1", "工作", "com.example.camera", "Camera"),
                hostGranted = true,
                canRequestRuntime = true,
            ),
        )

        assertTrue(summary.granted)
        assertEquals(ClonePermissionAction.NONE, summary.action)
    }

    @Test
    fun `inconsistent metadata for one permission is rejected`() {
        val clone = target("group-1", "工作", "com.example.camera", "Camera")

        val error = assertThrows(IllegalArgumentException::class.java) {
            ClonePermissionPolicy.aggregate(
                listOf(
                    requirement(CAMERA, clone),
                    requirement(CAMERA, clone, category = ClonePermissionCategory.SPECIAL),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("inconsistent categories"))
    }

    private fun aggregateOne(requirement: ClonePermissionRequirement): ClonePermissionSummary =
        ClonePermissionPolicy.aggregate(listOf(requirement)).single()

    private fun requirement(
        permission: String,
        target: ClonePermissionTarget,
        category: ClonePermissionCategory = ClonePermissionCategory.RUNTIME,
        virtualScope: ClonePermissionVirtualScope =
            if (permission == CAMERA) {
                ClonePermissionVirtualScope.CAMERA_MIC_PER_SPACE
            } else {
                ClonePermissionVirtualScope.HOST_SHARED
            },
        hostGranted: Boolean = true,
        canRequestRuntime: Boolean = false,
    ) = ClonePermissionRequirement(
        permission = permission,
        target = target,
        category = category,
        virtualScope = virtualScope,
        hostGranted = hostGranted,
        canRequestRuntime = canRequestRuntime,
    )

    private fun target(
        groupId: String,
        groupName: String,
        packageName: String,
        appLabel: String,
    ) = ClonePermissionTarget(
        groupId = groupId,
        groupName = groupName,
        packageName = packageName,
        appLabel = appLabel,
    )

    private companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val INTERNET = "android.permission.INTERNET"
        const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
        const val UNSUPPORTED = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
