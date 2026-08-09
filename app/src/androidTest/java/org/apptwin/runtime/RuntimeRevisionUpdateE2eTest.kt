package org.apptwin.runtime

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.groups.FileGroupOperationJournal
import org.apptwin.groups.FileGroupAppRemovalJournal
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two deliberately separate invocations validate an actual host APK replacement.
 *
 * The harness installs fixture revision 1, runs [phaseOne], replaces that host package with
 * revision 2 without clearing AppTwin, then runs [phaseTwo]. Keeping the phases separate prevents
 * an in-process PackageManager cache from making the update test unrealistically easy.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeRevisionUpdateE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun phaseOne() {
        assumePhase("1")
        assertEquals(1L, hostVersionCode())
        val store = FileGroupStore(context)
        val initial = store.loadSnapshot()
        assertTrue("phaseOne requires clean AppTwin group metadata", initial.groups.isEmpty())
        assertTrue("phaseOne requires readable AppTwin group metadata", initial.issues.isEmpty())
        assertTrue("phaseOne state marker must not already exist", !stateFile().exists())

        val importer = AndroidPackageRevisionImporter(context)
        val imported = importer.sync(FIXTURE_PACKAGE)
        assertActivated(imported, expectedVersionCode = 1L)

        val controller = VirtualRuntimeController(context)
        val group = GroupLifecycleCoordinator(
            store = store,
            runtime = controller,
            journal = FileGroupOperationJournal(context),
        ).createGroup(GROUP_NAME)
        val groupWithApp = requireNotNull(
            store.addApp(group.id, FIXTURE_PACKAGE, System.currentTimeMillis()),
        )
        val groupApp = requireNotNull(
            groupWithApp.apps.singleOrNull { it.packageName == FIXTURE_PACKAGE },
        )

        assertStarted(controller.installAndLaunch(groupWithApp, groupApp))
        val environmentId = requireNotNull(groupWithApp.environmentBinding).internalId
        assertEquals(1L, virtualVersionCode(environmentId))
        awaitGuestFile(environmentId, LAUNCH_COUNT_FILE, expected = "1")
        awaitGuestFile(environmentId, SENTINEL_FILE, expected = "created-by-revision=1")

        writeState(groupWithApp.id, environmentId)
    }

    @Test
    fun phaseTwo() {
        assumePhase("2")
        assertEquals(2L, hostVersionCode())
        val previous = readState()
        val store = FileGroupStore(context)
        val beforeUpdate = requireNotNull(store.find(previous.groupId))
        assertEquals(previous.environmentId, requireNotNull(beforeUpdate.environmentBinding).internalId)
        assertTrue(beforeUpdate.contains(FIXTURE_PACKAGE))
        awaitGuestFile(previous.environmentId, LAUNCH_COUNT_FILE, expected = "1")
        awaitGuestFile(previous.environmentId, SENTINEL_FILE, expected = "created-by-revision=1")

        val group = requireNotNull(store.find(previous.groupId))
        assertEquals(previous.environmentId, requireNotNull(group.environmentBinding).internalId)
        val groupApp = requireNotNull(group.apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })
        val launch = runBlocking {
            AndroidMainOperations(context.applicationContext as Application).launchGroupApp(
                GroupAppItem(
                    groupId = group.id,
                    groupName = group.name,
                    groupHealth = group.health,
                    app = groupApp,
                    appLabel = "AppTwin Runtime Fixture",
                    versionName = "fixture-2",
                    sourceInstalled = true,
                    launchStatus = "",
                ),
            )
        }
        assertStarted(launch)
        assertEquals(2L, AndroidPackageRevisionImporter(context).active(FIXTURE_PACKAGE)?.versionCode)

        val afterUpdate = requireNotNull(store.find(previous.groupId))
        assertEquals(previous.environmentId, requireNotNull(afterUpdate.environmentBinding).internalId)
        assertEquals(2L, virtualVersionCode(previous.environmentId))
        awaitGuestFile(previous.environmentId, LAUNCH_COUNT_FILE, expected = "2")
        awaitGuestFile(previous.environmentId, SENTINEL_FILE, expected = "created-by-revision=1")

        val privateDirectories = listOf(
            VEnvironment.getDataUserPackageDirectory(previous.environmentId, FIXTURE_PACKAGE),
            VEnvironment.getDeDataUserPackageDirectory(previous.environmentId, FIXTURE_PACKAGE),
            VEnvironment.getVirtualPrivateStorageDir(previous.environmentId, FIXTURE_PACKAGE),
        )
        val removal = GroupAppRemovalCoordinator(
            store = store,
            runtime = VirtualRuntimeController(context),
            journal = FileGroupAppRemovalJournal(context),
        ).remove(previous.groupId, FIXTURE_PACKAGE)
        assertTrue(removal is GroupAppRemovalResult.Succeeded)
        assertTrue(requireNotNull(store.find(previous.groupId)).apps.isEmpty())
        privateDirectories.forEach { directory ->
            assertTrue("guest private directory must be deleted: $directory", !directory.exists())
        }
    }

    private fun assertActivated(result: RevisionImportResult, expectedVersionCode: Long) {
        assertTrue("expected an activated revision but was $result", result is RevisionImportResult.Activated)
        assertEquals(
            expectedVersionCode,
            (result as RevisionImportResult.Activated).summary.versionCode,
        )
    }

    private fun assumePhase(expected: String) {
        val selected = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue(
            "runtime update E2E is opt-in; pass -e $PHASE_ARGUMENT $expected",
            selected == expected,
        )
    }

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("expected a started runtime but was $result", result is RuntimeLaunchResult.Started)
    }

    private fun hostVersionCode(): Long = packageVersionCode(
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0),
    )

    private fun virtualVersionCode(environmentId: Int): Long {
        val info = VPackageManager.get().getPackageInfo(FIXTURE_PACKAGE, 0, environmentId)
        assertNotNull("fixture must be installed for the original virtual user", info)
        return packageVersionCode(requireNotNull(info))
    }

    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun awaitGuestFile(environmentId: Int, name: String, expected: String) {
        val file = File(
            VEnvironment.getDataUserPackageDirectory(environmentId, FIXTURE_PACKAGE),
            "files/$name",
        )
        var actual: String? = null
        repeat(POLL_ATTEMPTS) {
            actual = file.takeIf(File::isFile)?.readText()?.trim()
            if (actual == expected) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("guest file did not reach '$expected': ${file.absolutePath} (actual=$actual)")
    }

    private fun stateFile(): File = File(context.filesDir, STATE_FILE)

    private fun writeState(groupId: String, environmentId: Int) {
        FileOutputStream(stateFile()).use { output ->
            Properties().apply {
                setProperty("groupId", groupId)
                setProperty("environmentId", environmentId.toString())
            }.store(output, "AppTwin runtime revision E2E state")
            output.fd.sync()
        }
    }

    private fun readState(): PhaseState {
        val file = stateFile()
        assertTrue("phaseOne state marker is missing", file.isFile)
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        return PhaseState(
            groupId = requireNotNull(properties.getProperty("groupId")),
            environmentId = requireNotNull(properties.getProperty("environmentId")?.toIntOrNull()),
        )
    }

    private data class PhaseState(val groupId: String, val environmentId: Int)

    private companion object {
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val GROUP_NAME = "Runtime revision E2E"
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val SENTINEL_FILE = "revision-sentinel.txt"
        const val STATE_FILE = "runtime-revision-e2e.properties"
        const val PHASE_ARGUMENT = "runtimeUpdatePhase"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
