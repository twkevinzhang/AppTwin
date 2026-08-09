package org.apptwin.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VPackageManager
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
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-process acceptance for the M1 release gate: the same package in two newly-created spaces.
 *
 * Phase one creates and launches both spaces. The harness force-stops AppTwin before phase two,
 * which verifies durable identity/data separation and then removes only the first membership.
 */
@RunWith(AndroidJUnit4::class)
class MultiSpaceIsolationE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val operations: AndroidMainOperations
        get() = AndroidMainOperations(context.applicationContext as Application)

    @Test
    fun phaseOneCreatesIndependentSpaces() = runBlocking {
        assumePhase("1")
        assertEquals(1L, hostVersionCode())
        val store = FileGroupStore(context)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        val snapshot = store.loadSnapshot()
        assertTrue("phase one requires clean Group metadata", snapshot.groups.isEmpty())
        assertTrue("phase one requires readable Group metadata", snapshot.issues.isEmpty())

        val imported = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "fixture revision must be available: $imported",
            imported is RevisionImportResult.Activated ||
                imported is RevisionImportResult.AlreadyCurrent,
        )

        val first = operations.createGroup("工作")
        val second = operations.createGroup("私人")
        val firstWithApp = operations.addAppToGroup(first.id, FIXTURE_PACKAGE)
        val secondWithApp = operations.addAppToGroup(second.id, FIXTURE_PACKAGE)

        assertStarted(operations.launchGroupApp(firstWithApp.item()))
        assertStarted(operations.launchGroupApp(secondWithApp.item()))

        val firstEnvironment = firstWithApp.environmentId()
        val secondEnvironment = secondWithApp.environmentId()
        assertTrue("spaces must own different environments", firstEnvironment != secondEnvironment)
        awaitGuestFile(firstEnvironment, LAUNCH_COUNT_FILE, "1")
        awaitGuestFile(secondEnvironment, LAUNCH_COUNT_FILE, "1")
        awaitGuestFile(firstEnvironment, PERMISSION_STATE_FILE, DENIED_PERMISSIONS)
        awaitGuestFile(secondEnvironment, PERMISSION_STATE_FILE, DENIED_PERMISSIONS)

        assertTrue(
            VPackageManager.get().setRuntimePermissionGranted(
                Manifest.permission.CAMERA,
                FIXTURE_PACKAGE,
                firstEnvironment,
                true,
            ),
        )
        assertTrue(
            VPackageManager.get().setRuntimePermissionGranted(
                Manifest.permission.RECORD_AUDIO,
                FIXTURE_PACKAGE,
                secondEnvironment,
                true,
            ),
        )
        VirtualCore.get().killApp(FIXTURE_PACKAGE, firstEnvironment)
        VirtualCore.get().killApp(FIXTURE_PACKAGE, secondEnvironment)
        assertStarted(operations.launchGroupApp(firstWithApp.item()))
        assertStarted(operations.launchGroupApp(secondWithApp.item()))
        awaitGuestFile(firstEnvironment, LAUNCH_COUNT_FILE, "2")
        awaitGuestFile(secondEnvironment, LAUNCH_COUNT_FILE, "2")
        awaitGuestFile(firstEnvironment, PERMISSION_STATE_FILE, CAMERA_ONLY_PERMISSIONS)
        awaitGuestFile(secondEnvironment, PERMISSION_STATE_FILE, MICROPHONE_ONLY_PERMISSIONS)
        awaitNotificationSpaceLabels(setOf("AppTwin · 工作", "AppTwin · 私人"))

        val deepLinkCandidates = operations.resolveDeepLink(FIXTURE_DEEP_LINK).toSet()
        assertEquals(
            setOf(first.id to FIXTURE_PACKAGE, second.id to FIXTURE_PACKAGE),
            deepLinkCandidates,
        )
        VirtualCore.get().killApp(FIXTURE_PACKAGE, secondEnvironment)
        assertStarted(operations.launchDeepLink(secondWithApp.item(), FIXTURE_DEEP_LINK))
        awaitGuestFile(secondEnvironment, LAUNCH_COUNT_FILE, "3")
        writeGuestFile(firstEnvironment, SPACE_SENTINEL_FILE, "space=work")
        writeGuestFile(secondEnvironment, SPACE_SENTINEL_FILE, "space=private")
        assertGuestFile(firstEnvironment, SPACE_SENTINEL_FILE, "space=work")
        assertGuestFile(secondEnvironment, SPACE_SENTINEL_FILE, "space=private")

        writeState(firstWithApp, secondWithApp)
    }

    @Test
    fun phaseTwoSurvivesRestartAndDeletesOnlyOneSpaceMembership() = runBlocking {
        assumePhase("2")
        val state = readState()
        val store = FileGroupStore(context)
        val first = requireNotNull(store.find(state.firstGroupId))
        val second = requireNotNull(store.find(state.secondGroupId))
        assertEquals(state.firstEnvironmentId, first.environmentId())
        assertEquals(state.secondEnvironmentId, second.environmentId())
        assertGuestFile(state.firstEnvironmentId, SPACE_SENTINEL_FILE, "space=work")
        assertGuestFile(state.secondEnvironmentId, SPACE_SENTINEL_FILE, "space=private")

        assertStarted(operations.launchGroupApp(first.item()))
        assertStarted(operations.launchGroupApp(second.item()))
        awaitGuestFile(state.firstEnvironmentId, LAUNCH_COUNT_FILE, "3")
        awaitGuestFile(state.secondEnvironmentId, LAUNCH_COUNT_FILE, "4")
        awaitGuestFile(
            state.firstEnvironmentId,
            PERMISSION_STATE_FILE,
            CAMERA_ONLY_PERMISSIONS,
        )
        awaitGuestFile(
            state.secondEnvironmentId,
            PERMISSION_STATE_FILE,
            MICROPHONE_ONLY_PERMISSIONS,
        )

        val firstItem = first.item()
        val firstPrivateDirectories = privateDirectories(state.firstEnvironmentId)
        val removal = operations.uninstallGroupApp(firstItem)
        assertTrue("first membership removal failed: $removal", removal is GroupAppRemovalResult.Succeeded)
        assertTrue(requireNotNull(store.find(first.id)).apps.isEmpty())
        firstPrivateDirectories.forEach { directory ->
            assertTrue("first space data must be removed: $directory", !directory.exists())
        }

        val surviving = requireNotNull(store.find(second.id))
        assertTrue(surviving.contains(FIXTURE_PACKAGE))
        assertGuestFile(state.secondEnvironmentId, SPACE_SENTINEL_FILE, "space=private")
        assertTrue(VirtualCore.get().isAppInstalled(FIXTURE_PACKAGE))
        assertNotNull(AndroidPackageRevisionImporter(context).active(FIXTURE_PACKAGE))
        VirtualCore.get().killApp(FIXTURE_PACKAGE, state.secondEnvironmentId)
        assertStarted(operations.launchGroupApp(surviving.item()))
        awaitGuestFile(state.secondEnvironmentId, LAUNCH_COUNT_FILE, "5")

        assertNotNull(operations.deleteGroup(first.id))
        assertNotNull(operations.deleteGroup(second.id))
        assertTrue(store.loadSnapshot().groups.isEmpty())
    }

    private fun Group.item(): GroupAppItem {
        val groupApp = requireNotNull(apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = groupApp,
            appLabel = "AppTwin Runtime Fixture",
            versionName = "fixture-1",
            sourceInstalled = true,
            launchStatus = "",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("expected runtime start but was $result", result is RuntimeLaunchResult.Started)
    }

    private fun privateDirectories(userId: Int): List<File> = listOf(
        VEnvironment.getDataUserPackageDirectory(userId, FIXTURE_PACKAGE),
        VEnvironment.getDeDataUserPackageDirectory(userId, FIXTURE_PACKAGE),
        VEnvironment.getVirtualPrivateStorageDir(userId, FIXTURE_PACKAGE),
    )

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

    private fun awaitNotificationSpaceLabels(expected: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        var actual = emptySet<String>()
        repeat(POLL_ATTEMPTS) {
            actual = manager.activeNotifications.mapNotNull { notification ->
                notification.notification.extras
                    .getCharSequence(Notification.EXTRA_SUB_TEXT)
                    ?.toString()
            }.toSet()
            if (actual.containsAll(expected)) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("notifications did not expose both space labels: expected=$expected actual=$actual")
    }

    private fun writeState(first: Group, second: Group) {
        FileOutputStream(stateFile()).use { output ->
            Properties().apply {
                setProperty("firstGroupId", first.id)
                setProperty("firstEnvironmentId", first.environmentId().toString())
                setProperty("secondGroupId", second.id)
                setProperty("secondEnvironmentId", second.environmentId().toString())
            }.store(output, "AppTwin multi-space E2E state")
            output.fd.sync()
        }
    }

    private fun readState(): PhaseState {
        val properties = Properties().apply { FileInputStream(stateFile()).use(::load) }
        return PhaseState(
            firstGroupId = requireNotNull(properties.getProperty("firstGroupId")),
            firstEnvironmentId = requireNotNull(
                properties.getProperty("firstEnvironmentId")?.toIntOrNull(),
            ),
            secondGroupId = requireNotNull(properties.getProperty("secondGroupId")),
            secondEnvironmentId = requireNotNull(
                properties.getProperty("secondEnvironmentId")?.toIntOrNull(),
            ),
        )
    }

    private fun stateFile(): File = File(context.filesDir, STATE_FILE)

    private fun hostVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
        @Suppress("DEPRECATION")
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private fun assumePhase(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue("pass -e $PHASE_ARGUMENT $expected", actual == expected)
    }

    private data class PhaseState(
        val firstGroupId: String,
        val firstEnvironmentId: Int,
        val secondGroupId: String,
        val secondEnvironmentId: Int,
    )

    private companion object {
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val FIXTURE_DEEP_LINK = "https://fixture.apptwin.test/probe"
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val SPACE_SENTINEL_FILE = "space-sentinel.txt"
        const val PERMISSION_STATE_FILE = "permission-state.txt"
        const val DENIED_PERMISSIONS = "camera=false\nmicrophone=false"
        const val CAMERA_ONLY_PERMISSIONS = "camera=true\nmicrophone=false"
        const val MICROPHONE_ONLY_PERMISSIONS = "camera=false\nmicrophone=true"
        const val STATE_FILE = "multi-space-e2e.properties"
        const val PHASE_ARGUMENT = "multiSpacePhase"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
