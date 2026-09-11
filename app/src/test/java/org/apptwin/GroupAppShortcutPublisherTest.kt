package org.apptwin

import android.app.Application
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAppShortcutPublisherTest {
    @Test
    fun `existing pinned product shortcut is refreshed then requested again`() {
        val platform = FakeShortcutPlatform(
            pinnedIds = mutableSetOf(LAUNCH_KEY),
        )
        val publisher = publisher(platform)

        val result = publisher.requestPin(item())

        assertEquals(ShortcutCreationResult.Requested, result)
        assertEquals(listOf(spec()), platform.pinRequests)
        assertEquals(listOf(listOf(spec())), platform.updates)
    }

    @Test
    fun `new product shortcut requests pin exactly once`() {
        val platform = FakeShortcutPlatform()
        val publisher = publisher(platform)

        assertEquals(ShortcutCreationResult.Requested, publisher.requestPin(item()))

        assertEquals(listOf(spec()), platform.pinRequests)
        assertTrue(platform.updates.isEmpty())
    }

    @Test
    fun `reconcile repairs only changed durable product IDs and then becomes a no-op`() {
        val failedKey = GroupAppLaunchContract.launchKey(GROUP_ID, FAILED_PACKAGE)
        val platform = FakeShortcutPlatform(
            pinnedIds = mutableSetOf(LAUNCH_KEY, failedKey, GUEST_SHORTCUT_ID, ORPHAN_ID),
        )
        val publisher = publisher(platform)
        val group = group().copy(
            apps = listOf(
                GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED),
                GroupApp(FAILED_PACKAGE, 3L, GroupAppState.FAILED),
            ),
        )

        assertEquals(ShortcutReconciliationResult.Reconciled(2), publisher.reconcile(listOf(group)))
        assertEquals(ShortcutReconciliationResult.Reconciled(0), publisher.reconcile(listOf(group)))

        assertTrue(platform.pinRequests.isEmpty())
        assertEquals(1, platform.updates.size)
        platform.updates.forEach { repaired ->
            assertEquals(setOf(LAUNCH_KEY, failedKey), repaired.mapTo(mutableSetOf()) { it.id })
            assertTrue(repaired.single { it.id == LAUNCH_KEY }.available)
            assertTrue(!repaired.single { it.id == failedKey }.available)
            assertTrue(repaired.none { it.id == GUEST_SHORTCUT_ID || it.id == ORPHAN_ID })
        }
        assertEquals(1, platform.enables.size)
        platform.enables.forEach { enabled ->
            assertEquals(setOf(LAUNCH_KEY, failedKey), enabled.toSet())
        }
    }

    @Test
    fun `reconcile skips a pinned shortcut while its pin request is pending`() {
        val platform = FakeShortcutPlatform(pinnedIds = mutableSetOf(LAUNCH_KEY))
        val publisher = publisher(platform, pendingPinIds = { setOf(LAUNCH_KEY) })

        assertEquals(ShortcutReconciliationResult.Reconciled(0), publisher.reconcile(listOf(group())))

        assertTrue(platform.enables.isEmpty())
        assertTrue(platform.updates.isEmpty())
    }

    @Test
    fun `reconcile does not update unchanged pinned shortcut`() {
        val platform = FakeShortcutPlatform(
            pinnedIds = mutableSetOf(LAUNCH_KEY),
            currentSpecs = mutableMapOf(LAUNCH_KEY to spec()),
        )
        val publisher = publisher(platform)

        assertEquals(ShortcutReconciliationResult.Reconciled(0), publisher.reconcile(listOf(group())))

        assertTrue(platform.enables.isEmpty())
        assertTrue(platform.updates.isEmpty())
    }

    @Test
    fun `existing unchanged pinned shortcut is still requested again`() {
        val platform = FakeShortcutPlatform(
            pinnedIds = mutableSetOf(LAUNCH_KEY),
            currentSpecs = mutableMapOf(LAUNCH_KEY to spec()),
        )
        val publisher = publisher(platform)

        assertEquals(ShortcutCreationResult.Requested, publisher.requestPin(item()))

        assertEquals(listOf(spec()), platform.pinRequests)
        assertTrue(platform.updates.isEmpty())
    }

    @Test
    fun `pin request failure is reported after refreshing existing metadata`() {
        val platform = FakeShortcutPlatform(
            pinnedIds = mutableSetOf(LAUNCH_KEY),
            requestAccepted = false,
        )
        val publisher = publisher(platform)

        assertEquals(
            ShortcutCreationResult.Failed("啟動器未接受捷徑要求"),
            publisher.requestPin(item()),
        )

        assertEquals(listOf(listOf(spec())), platform.updates)
        assertEquals(listOf(spec()), platform.pinRequests)
    }

    @Test
    fun `reconcile grays shortcut when source app is no longer installed`() {
        val platform = FakeShortcutPlatform(pinnedIds = mutableSetOf(LAUNCH_KEY))
        val publisher = publisher(platform, sourceInstalled = false)
        val group = group().copy(
            apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED)),
        )

        assertEquals(ShortcutReconciliationResult.Reconciled(1), publisher.reconcile(listOf(group)))
        assertTrue(!platform.updates.single().single().available)
    }

    @Test
    fun `removed clone disables only its existing pinned shortcut with launcher message`() {
        val platform = FakeShortcutPlatform(
            pinnedIds = mutableSetOf(LAUNCH_KEY, GUEST_SHORTCUT_ID),
        )
        val publisher = publisher(platform)

        assertEquals(
            ShortcutReconciliationResult.Reconciled(1),
            publisher.disable(GROUP_ID, APP_PACKAGE),
        )

        assertEquals(listOf(listOf(LAUNCH_KEY) to "此分身 App 已刪除"), platform.disables)
    }

    private fun publisher(
        platform: ProductShortcutPlatform,
        sourceInstalled: Boolean = true,
        pendingPinIds: () -> Set<String> = { emptySet() },
    ) = GroupAppShortcutPublisher(
        application = Application(),
        platform = platform,
        appLabel = { packageName -> if (packageName == APP_PACKAGE) "LINE" else "失效 App" },
        sourceInstalled = { sourceInstalled },
        pendingPinIds = pendingPinIds,
    )

    private fun item() = GroupAppItem(
        groupId = GROUP_ID,
        groupName = GROUP_NAME,
        groupHealth = GroupHealth.HEALTHY,
        app = GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED),
        appLabel = "LINE",
        versionName = "15.0",
        sourceInstalled = true,
        launchStatus = "可使用",
    )

    private fun spec() = ProductShortcutSpec(
        id = LAUNCH_KEY,
        groupId = GROUP_ID,
        packageName = APP_PACKAGE,
        groupName = GROUP_NAME,
        appLabel = "LINE",
        available = true,
    )

    private fun group() = Group(
        id = GROUP_ID,
        name = GROUP_NAME,
        createdAtEpochMillis = 1L,
        environmentBinding = EnvironmentBinding(7),
        health = GroupHealth.HEALTHY,
    )

    private class FakeShortcutPlatform(
        private val pinnedIds: MutableSet<String> = mutableSetOf(),
        private val currentSpecs: MutableMap<String, ProductShortcutSpec> = mutableMapOf(),
        override val pinSupported: Boolean = true,
        private val requestAccepted: Boolean = true,
    ) : ProductShortcutPlatform {
        val pinRequests = mutableListOf<ProductShortcutSpec>()
        val updates = mutableListOf<List<ProductShortcutSpec>>()
        val enables = mutableListOf<List<String>>()
        val disables = mutableListOf<Pair<List<String>, String>>()

        override fun pinnedIds(): Set<String> = pinnedIds.toSet()

        override fun isCurrent(spec: ProductShortcutSpec): Boolean = currentSpecs[spec.id] == spec

        override fun requestPin(spec: ProductShortcutSpec): Boolean {
            pinRequests += spec
            if (requestAccepted) {
                pinnedIds += spec.id
                currentSpecs[spec.id] = spec
            }
            return requestAccepted
        }

        override fun update(specs: List<ProductShortcutSpec>): Boolean {
            updates += specs
            specs.forEach { spec -> currentSpecs[spec.id] = spec }
            return true
        }

        override fun enable(ids: List<String>): Boolean {
            enables += ids
            return true
        }

        override fun disable(ids: List<String>, message: String): Boolean {
            disables += ids to message
            return true
        }
    }

    private companion object {
        const val GROUP_ID = "11111111-1111-1111-1111-111111111111"
        const val GROUP_NAME = "工作"
        const val APP_PACKAGE = "jp.naver.line.android"
        const val FAILED_PACKAGE = "com.example.failed"
        const val GUEST_SHORTCUT_ID = "jp.naver.line.android7"
        const val ORPHAN_ID = "22222222-2222-2222-2222-222222222222:com.example.orphan"
        val LAUNCH_KEY = GroupAppLaunchContract.launchKey(GROUP_ID, APP_PACKAGE)
    }
}
