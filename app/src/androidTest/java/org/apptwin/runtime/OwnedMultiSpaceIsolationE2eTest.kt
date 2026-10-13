package org.apptwin.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import androidx.test.core.app.ActivityScenario
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
import org.apptwin.MainActivity
import org.apptwin.GroupAppItem
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-process acceptance using exclusively newly-created, test-owned spaces.
 *
 * Phase one creates and launches both spaces. The harness force-stops AppTwin before phase two,
 * which verifies durable identity/data separation and then removes only the first membership.
 */
@RunWith(AndroidJUnit4::class)
class OwnedMultiSpaceIsolationE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val operations: AndroidMainOperations
        get() = AndroidMainOperations(context.applicationContext as Application)

    @Test
    fun phaseOneCreatesIndependentSpaces() = runBlocking {
        requirePhase("1")
        check(!stateFile().exists()) { "Run id already owns state; finish phase two before reuse" }
        val store = FileGroupStore(context)
        val snapshot = store.loadSnapshot()
        assertTrue("phase one requires readable Group metadata", snapshot.issues.isEmpty())

        val baseline = Properties().apply {
            setProperty("runId", runId())
            setProperty("baselineGroups", encodeGroups(snapshot.groups))
            setProperty("baselineDeepLinks", encodeCandidates(operations.resolveDeepLink(FIXTURE_DEEP_LINK)))
        }
        saveState(baseline)
        val owned = mutableListOf<String>()
        var host: ActivityScenario<MainActivity>? = null
        var completed = false
        var failure: Throwable? = null
        try {
        host = ActivityScenario.launch(MainActivity::class.java)
        host.awaitRealForegroundHost()
        val imported = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "fixture revision must be available: $imported",
            imported is RevisionImportResult.Activated ||
                imported is RevisionImportResult.AlreadyCurrent,
        )

        baseline.setProperty("revisionId", requireNotNull(AndroidPackageRevisionImporter(context).active(FIXTURE_PACKAGE)).revisionId)
        saveState(baseline)
        val first = operations.createGroup("Owned ${runId()} A").also {
            owned += it.id; baseline.setProperty("firstGroupId", it.id); saveState(baseline)
        }
        val second = operations.createGroup("Owned ${runId()} B").also {
            owned += it.id; baseline.setProperty("secondGroupId", it.id); saveState(baseline)
        }
        val firstWithApp = operations.addAppToGroup(first.id, FIXTURE_PACKAGE)
        val secondWithApp = operations.addAppToGroup(second.id, FIXTURE_PACKAGE)

        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(firstWithApp.item()), firstWithApp.environmentId())
        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(secondWithApp.item()), secondWithApp.environmentId())

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
        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(firstWithApp.item()), firstWithApp.environmentId())
        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(secondWithApp.item()), secondWithApp.environmentId())
        awaitGuestFile(firstEnvironment, LAUNCH_COUNT_FILE, "2")
        awaitGuestFile(secondEnvironment, LAUNCH_COUNT_FILE, "2")
        awaitGuestFile(firstEnvironment, PERMISSION_STATE_FILE, CAMERA_ONLY_PERMISSIONS)
        awaitGuestFile(secondEnvironment, PERMISSION_STATE_FILE, MICROPHONE_ONLY_PERMISSIONS)
        awaitNotificationSpaceLabels(setOf("AppTwin · ${first.name}", "AppTwin · ${second.name}"))

        val deepLinkCandidates = operations.resolveDeepLink(FIXTURE_DEEP_LINK).toSet()
        assertEquals(
            decodeCandidates(baseline.getProperty("baselineDeepLinks")) +
                setOf(first.id to FIXTURE_PACKAGE, second.id to FIXTURE_PACKAGE),
            deepLinkCandidates,
        )
        VirtualCore.get().killApp(FIXTURE_PACKAGE, secondEnvironment)
        host.awaitRealForegroundHost()
        assertStarted(operations.launchDeepLink(secondWithApp.item(), FIXTURE_DEEP_LINK), secondWithApp.environmentId())
        awaitGuestFile(secondEnvironment, LAUNCH_COUNT_FILE, "3")
        writeGuestFile(firstEnvironment, SPACE_SENTINEL_FILE, "space=work")
        writeGuestFile(secondEnvironment, SPACE_SENTINEL_FILE, "space=private")
        assertGuestFile(firstEnvironment, SPACE_SENTINEL_FILE, "space=work")
        assertGuestFile(secondEnvironment, SPACE_SENTINEL_FILE, "space=private")

        baseline.setProperty("firstEnvironmentId", firstWithApp.environmentId().toString())
        baseline.setProperty("secondEnvironmentId", secondWithApp.environmentId().toString())
        assertBaseline(baseline, owned.toSet())
        saveState(baseline)
        completed = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { if (!completed) cleanup(baseline, failure) }
            finally { closeHost(host, failure) }
        }
    }

    @Test
    fun phaseTwoSurvivesRestartAndDeletesOnlyOneSpaceMembership() = runBlocking {
        requirePhase("2")
        val saved = loadState()
        assertEquals(runId(), saved.getProperty("runId"))
        var failure: Throwable? = null
        var host: ActivityScenario<MainActivity>? = null
        try {
        host = ActivityScenario.launch(MainActivity::class.java)
        host.awaitRealForegroundHost()
        val state = readState()
        val store = FileGroupStore(context)
        val first = requireNotNull(store.find(state.firstGroupId))
        val second = requireNotNull(store.find(state.secondGroupId))
        assertEquals(state.firstEnvironmentId, first.environmentId())
        assertEquals(state.secondEnvironmentId, second.environmentId())
        assertGuestFile(state.firstEnvironmentId, SPACE_SENTINEL_FILE, "space=work")
        assertGuestFile(state.secondEnvironmentId, SPACE_SENTINEL_FILE, "space=private")

        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(first.item()), first.environmentId())
        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(second.item()), second.environmentId())
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
        assertEquals(saved.getProperty("revisionId"), requireNotNull(AndroidPackageRevisionImporter(context).active(FIXTURE_PACKAGE)).revisionId)
        VirtualCore.get().killApp(FIXTURE_PACKAGE, state.secondEnvironmentId)
        host.awaitRealForegroundHost()
        assertStarted(operations.launchGroupApp(surviving.item()), surviving.environmentId())
        awaitGuestFile(state.secondEnvironmentId, LAUNCH_COUNT_FILE, "5")

        assertNotNull(operations.deleteGroup(first.id))
        assertNotNull(operations.deleteGroup(second.id))
        assertBaseline(saved, emptySet())
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { cleanup(saved, failure) } finally { closeHost(host, failure) }
        }
    }

    private fun closeHost(host: ActivityScenario<MainActivity>?, originalFailure: Throwable?) {
        host?.closeRealForegroundHost(originalFailure)
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

    private fun assertStarted(result: RuntimeLaunchResult, environmentId: Int) {
        assertTrue("expected runtime start but was $result", result is RuntimeLaunchResult.Started)
        result as RuntimeLaunchResult.Started
        assertEquals(FIXTURE_PACKAGE, result.packageName)
        assertEquals(VEnvironment.getDataUserPackageDirectory(environmentId, FIXTURE_PACKAGE).absolutePath, result.dataDirectory)
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

    private fun runId(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString(RUN_ARGUMENT),
    ).also { require(it.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid owned run id" } }

    private fun stateFile(): File = File(context.cacheDir, "owned-multi-space-${runId()}.properties")

    private fun saveState(properties: Properties) {
        FileOutputStream(stateFile()).use { output ->
            properties.store(output, "Owned AppTwin acceptance metadata")
            output.fd.sync()
        }
    }

    private fun loadState(): Properties = Properties().apply {
        FileInputStream(stateFile()).use(::load)
    }

    private fun readState(): PhaseState {
        val properties = loadState()
        return PhaseState(
            requireNotNull(properties.getProperty("firstGroupId")),
            requireNotNull(properties.getProperty("firstEnvironmentId")).toInt(),
            requireNotNull(properties.getProperty("secondGroupId")),
            requireNotNull(properties.getProperty("secondEnvironmentId")).toInt(),
        )
    }

    private fun requirePhase(expected: String) {
        assertEquals("Select the exact phase method and pass its phase argument", expected,
            InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT))
        runId()
    }

    // IDs and package names are public routing metadata; no account/token/content is persisted.
    private fun encodeGroups(groups: List<Group>): String = groups.sortedBy { it.id }.joinToString("\n") {
        "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",") }"
    }

    private fun encodeCandidates(candidates: Collection<Pair<String, String>>): String =
        candidates.sortedWith(compareBy({ it.first }, { it.second })).joinToString("\n") { "${it.first}|${it.second}" }

    private fun decodeCandidates(value: String): Set<Pair<String, String>> = value.lineSequence()
        .filter(String::isNotEmpty).map { line -> line.split('|').let { it[0] to it[1] } }.toSet()

    private fun assertBaseline(properties: Properties, ownedIds: Set<String>) {
        val snapshot = FileGroupStore(context).loadSnapshot()
        assertTrue("metadata remains readable", snapshot.issues.isEmpty())
        assertEquals("Existing spaces/memberships/environment IDs changed",
            properties.getProperty("baselineGroups"), encodeGroups(snapshot.groups.filter { it.id !in ownedIds }))
    }

    private suspend fun cleanup(properties: Properties, originalFailure: Throwable?) {
        val failures = mutableListOf<Throwable>()
        listOf("secondGroupId", "firstGroupId").mapNotNull(properties::getProperty).forEach { id ->
            try {
                if (FileGroupStore(context).find(id) != null) operations.deleteGroup(id)
                check(FileGroupStore(context).find(id) == null) { "Owned space cleanup failed" }
            } catch (error: Throwable) { failures += error }
        }
        try { assertBaseline(properties, emptySet()) } catch (error: Throwable) { failures += error }
        // Keep ownership metadata when cleanup fails so the harness can recover exact owned IDs.
        if (failures.isEmpty()) {
            try { check(!stateFile().exists() || stateFile().delete()) { "Owned state cleanup failed" } }
            catch (error: Throwable) { failures += error }
        }
        if (originalFailure != null) failures.forEach(originalFailure::addSuppressed)
        else if (failures.isNotEmpty()) throw failures.first().also { first -> failures.drop(1).forEach(first::addSuppressed) }
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
        const val RUN_ARGUMENT = "ownedMultiSpaceRunId"
        const val PHASE_ARGUMENT = "ownedMultiSpacePhase"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
