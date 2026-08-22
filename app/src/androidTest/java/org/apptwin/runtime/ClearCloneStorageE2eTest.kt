package org.apptwin.runtime

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VEnvironment
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.groups.GroupHealth
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.usecases.ClearCloneStorageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in destructive proof using the self-owned fixture package only.
 *
 * It creates and removes its own Space so it never touches an existing clone. The user-wide
 * virtual SD sentinel intentionally survives because that directory is shared by clones in a
 * Space and is not safe to attribute to one package.
 */
@RunWith(AndroidJUnit4::class)
class ClearCloneStorageE2eTest {
    @Test
    fun clearRetainsCloneBindingAndSharedStorageWhileRemovingPrivateStorage() = runBlocking {
        assumeTrue(
            "storage clear E2E is opt-in; pass -e clearCloneStorageE2e 1",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "1",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("self-owned fixture must be installed", isFixtureInstalled(context))
        AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)

        val operations = AndroidMainOperations(context.applicationContext as Application)
        val group = operations.createGroup("Storage clear E2E")
        try {
            val groupWithApp = operations.addAppToGroup(group.id, FIXTURE_PACKAGE)
            val app = requireNotNull(groupWithApp.apps.singleOrNull())
            val environmentId = requireNotNull(groupWithApp.environmentBinding).internalId
            val item = GroupAppItem(
                groupId = groupWithApp.id,
                groupName = groupWithApp.name,
                groupHealth = GroupHealth.HEALTHY,
                app = app,
                appLabel = "AppTwin Runtime Fixture",
                versionName = "fixture",
                sourceInstalled = true,
                launchStatus = "",
            )
            VirtualCore.get().waitForEngine()
            assertTrue(VirtualCore.get().installPackageAsUser(environmentId, FIXTURE_PACKAGE))

            val privateDirectories = listOf(
                VEnvironment.getDataUserPackageDirectory(environmentId, FIXTURE_PACKAGE),
                VEnvironment.getDeDataUserPackageDirectory(environmentId, FIXTURE_PACKAGE),
                requireNotNull(VEnvironment.getVirtualPrivateStorageDir(environmentId, FIXTURE_PACKAGE)),
            )
            privateDirectories.forEachIndexed { index, directory ->
                assertTrue(directory.mkdirs() || directory.isDirectory)
                File(directory, "clear-probe-$index.txt").writeText("private")
            }
            val sharedSentinel = File(
                requireNotNull(VEnvironment.getVirtualStorageDir(FIXTURE_PACKAGE, environmentId)),
                "clear-storage-shared-sentinel.txt",
            ).apply { writeText("shared") }

            assertEquals(ClearCloneStorageResult.Cleared, operations.clearGroupAppStorage(item))
            assertTrue(requireNotNull(operations.findGroup(group.id)).contains(FIXTURE_PACKAGE))
            assertTrue(VirtualCore.get().isAppInstalledAsUser(environmentId, FIXTURE_PACKAGE))
            privateDirectories.forEach { directory ->
                assertFalse("private storage must be deleted: $directory", directory.exists())
            }
            assertTrue("shared virtual SD must be retained", sharedSentinel.isFile)
            assertEquals("shared", sharedSentinel.readText())
        } finally {
            assertTrue("E2E Space cleanup must succeed", operations.deleteGroup(group.id) != null)
        }
    }

    private fun isFixtureInstalled(context: android.content.Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private companion object {
        const val OPT_IN_ARGUMENT = "clearCloneStorageE2e"
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
    }
}
