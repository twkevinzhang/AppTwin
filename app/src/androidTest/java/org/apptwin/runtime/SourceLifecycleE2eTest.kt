package org.apptwin.runtime

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host uninstall/reinstall acceptance. The harness changes only the self-owned fixture package. */
@RunWith(AndroidJUnit4::class)
class SourceLifecycleE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val operations: AndroidMainOperations
        get() = AndroidMainOperations(context.applicationContext as Application)

    @Test
    fun phaseOneCreatesCloneFromInstalledSource() = runBlocking {
        assumePhase("1")
        assertTrue(isSourceInstalled())
        val store = FileGroupStore(context)
        assertTrue("phase one requires clean Group metadata", store.loadSnapshot().groups.isEmpty())
        val group = operations.createGroup("來源生命週期")
        val withApp = operations.addAppToGroup(group.id, FIXTURE_PACKAGE)
        assertStarted(operations.launchGroupApp(withApp.item(sourceInstalled = true)))
        val environmentId = withApp.environmentId()
        awaitGuestFile(environmentId, LAUNCH_COUNT_FILE, "1")
        writeGuestFile(environmentId, SOURCE_SENTINEL_FILE, "preserve-after-host-uninstall")
        writeState(withApp.id, environmentId)
    }

    @Test
    fun phaseTwoRetainsMembershipAndDataWhileSourceIsMissing() {
        assumePhase("2")
        assertTrue("fixture host must be uninstalled between phases", !isSourceInstalled())
        val state = readState()
        val group = requireNotNull(FileGroupStore(context).find(state.groupId))
        assertEquals(state.environmentId, group.environmentId())
        assertTrue(group.contains(FIXTURE_PACKAGE))
        assertGuestFile(
            state.environmentId,
            SOURCE_SENTINEL_FILE,
            "preserve-after-host-uninstall",
        )
        assertNotNull(
            "immutable active revision must remain while source is absent",
            AndroidPackageRevisionImporter(context).active(FIXTURE_PACKAGE),
        )
    }

    @Test
    fun phaseThreeSameSignerReinstallResumesOriginalSpace() = runBlocking {
        assumePhase("3")
        assertTrue("fixture host must be reinstalled between phases", isSourceInstalled())
        val state = readState()
        val store = FileGroupStore(context)
        val group = requireNotNull(store.find(state.groupId))
        assertEquals(state.environmentId, group.environmentId())
        assertGuestFile(
            state.environmentId,
            SOURCE_SENTINEL_FILE,
            "preserve-after-host-uninstall",
        )
        VirtualCore.get().killApp(FIXTURE_PACKAGE, state.environmentId)
        assertStarted(operations.launchGroupApp(group.item(sourceInstalled = true)))
        awaitGuestFile(state.environmentId, LAUNCH_COUNT_FILE, "2")
        assertGuestFile(
            state.environmentId,
            SOURCE_SENTINEL_FILE,
            "preserve-after-host-uninstall",
        )
        assertNotNull(operations.deleteGroup(group.id))
        assertTrue(store.loadSnapshot().groups.isEmpty())
    }

    private fun Group.item(sourceInstalled: Boolean): GroupAppItem {
        val groupApp = requireNotNull(apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = groupApp,
            appLabel = "AppTwin Runtime Fixture",
            versionName = if (sourceInstalled) "fixture-1" else "",
            sourceInstalled = sourceInstalled,
            launchStatus = if (sourceInstalled) "可使用" else "原始 App 已移除",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("expected runtime start but was $result", result is RuntimeLaunchResult.Started)
    }

    private fun isSourceInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private fun guestFile(userId: Int, name: String): File = File(
        VEnvironment.getDataUserPackageDirectory(userId, FIXTURE_PACKAGE),
        "files/$name",
    )

    private fun writeGuestFile(userId: Int, name: String, value: String) {
        val file = guestFile(userId, name)
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create ${file.parent}" }
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray())
            output.fd.sync()
        }
    }

    private fun assertGuestFile(userId: Int, name: String, expected: String) {
        assertEquals(expected, guestFile(userId, name).readText().trim())
    }

    private fun awaitGuestFile(userId: Int, name: String, expected: String) {
        val file = guestFile(userId, name)
        var actual: String? = null
        repeat(POLL_ATTEMPTS) {
            actual = file.takeIf(File::isFile)?.readText()?.trim()
            if (actual == expected) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("guest file did not reach '$expected': ${file.absolutePath} (actual=$actual)")
    }

    private fun writeState(groupId: String, environmentId: Int) {
        FileOutputStream(stateFile()).use { output ->
            Properties().apply {
                setProperty("groupId", groupId)
                setProperty("environmentId", environmentId.toString())
            }.store(output, "AppTwin source lifecycle E2E state")
            output.fd.sync()
        }
    }

    private fun readState(): PhaseState {
        val properties = Properties().apply { FileInputStream(stateFile()).use(::load) }
        return PhaseState(
            groupId = requireNotNull(properties.getProperty("groupId")),
            environmentId = requireNotNull(properties.getProperty("environmentId")?.toIntOrNull()),
        )
    }

    private fun stateFile(): File = File(context.filesDir, STATE_FILE)

    private fun assumePhase(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue("pass -e $PHASE_ARGUMENT $expected", actual == expected)
    }

    private data class PhaseState(val groupId: String, val environmentId: Int)

    private companion object {
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val SOURCE_SENTINEL_FILE = "source-lifecycle-sentinel.txt"
        const val STATE_FILE = "source-lifecycle-e2e.properties"
        const val PHASE_ARGUMENT = "sourceLifecyclePhase"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
