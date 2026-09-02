package org.apptwin.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppState
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device proof for the production add-then-first-launch adapter sequence.
 *
 * MainViewModel owns the UI orchestration that connects these two operations; its unit test proves
 * that connection. This test deliberately exercises the real AndroidMainOperations adapters and
 * virtual runtime so a unit test cannot accidentally substitute for the device acceptance signal.
 */
@RunWith(AndroidJUnit4::class)
class AddCloneFirstLaunchE2eTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    @Test
    fun addThenFirstLaunchEnablesExactCloneAndLeavesNoActiveOperation() = runBlocking {
        assumeTrue(
            "add/first-launch E2E is opt-in; pass -e $OPT_IN_ARGUMENT 1",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "1",
        )
        assertTrue("self-owned fixture must be installed", isFixtureInstalled())
        val imported = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "fixture revision must activate: $imported",
            imported is RevisionImportResult.Activated ||
                imported is RevisionImportResult.AlreadyCurrent,
        )

        val operations = AndroidMainOperations(context.applicationContext as Application)
        val groupStore = FileGroupStore(context)
        val operationStore = FileOperationRecordStore(context)
        val group = operations.createGroup("Add then first launch E2E")

        try {
            val added = operations.addAppToGroup(group.id, FIXTURE_PACKAGE)
            val addedApp = requireNotNull(
                added.apps.singleOrNull { it.packageName == FIXTURE_PACKAGE },
            )
            assertEquals(group.id, added.id)
            assertEquals(GroupAppState.ADDED, addedApp.state)
            assertNoActiveOperation(operationStore, group.id)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity(mainActivityLaunchHosts::onResumed)
                val launched = operations.launchGroupApp(added.fixtureItem())
                assertTrue(
                    "expected exact fixture clone to start but was $launched",
                    launched is RuntimeLaunchResult.Started,
                )
                launched as RuntimeLaunchResult.Started
                assertEquals(FIXTURE_PACKAGE, launched.packageName)
                assertEquals(
                    VEnvironment.getDataUserPackageDirectory(
                        added.environmentId(),
                        FIXTURE_PACKAGE,
                    ).absolutePath,
                    launched.dataDirectory,
                )

                awaitGuestLaunchCount(added.environmentId(), expected = "1")
                awaitExactCloneInForeground(added.environmentId())
                executeShellCommand("logcat -c")

                scenario.onActivity(mainActivityLaunchHosts::onResumed)
                val relaunched = operations.launchGroupApp(added.fixtureItem())
                assertTrue(
                    "expected verified fixture clone to relaunch but was $relaunched",
                    relaunched is RuntimeLaunchResult.Started,
                )
                // A foreground fixture task is reused rather than recreated, so its onCreate
                // counter remains one.
                awaitGuestLaunchCount(added.environmentId(), expected = "1")
                awaitExactCloneInForeground(added.environmentId())

                scenario.onActivity(mainActivityLaunchHosts::onResumed)
                val sessionRelaunched = operations.launchGroupApp(added.fixtureItem())
                assertTrue(
                    "expected stable daemon session to relaunch fixture but was $sessionRelaunched",
                    sessionRelaunched is RuntimeLaunchResult.Started,
                )
                awaitGuestLaunchCount(added.environmentId(), expected = "1")
                awaitExactCloneInForeground(added.environmentId())

                val runtimeLog = executeShellCommand(
                    "logcat -d -v brief -s AppTwinRuntime:I '*:S'",
                )
                assertTrue(
                    "verified revision marker fast path was not observed:\n$runtimeLog",
                    runtimeLog.contains("package-revision-fast-path package=$FIXTURE_PACKAGE"),
                )
                assertTrue(
                    "stable daemon session fast path was not observed:\n$runtimeLog",
                    runtimeLog.contains("daemon-launch-fast-path"),
                )
            }

            val persisted = requireNotNull(groupStore.find(group.id))
            val persistedApp = requireNotNull(
                persisted.apps.singleOrNull { it.packageName == FIXTURE_PACKAGE },
            )
            assertEquals(GroupAppState.ENABLED, persistedApp.state)
            assertNoActiveOperation(operationStore, group.id)
        } finally {
            assertNotNull(
                "dedicated E2E Group cleanup must succeed",
                operations.deleteGroup(group.id),
            )
            assertTrue(
                "dedicated E2E Group must be absent after cleanup",
                groupStore.find(group.id) == null,
            )
        }
    }

    private fun Group.fixtureItem(): GroupAppItem {
        val fixture = requireNotNull(apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = fixture,
            appLabel = "AppTwin Runtime Fixture",
            versionName = "fixture",
            sourceInstalled = true,
            launchStatus = "準備中",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertNoActiveOperation(
        store: FileOperationRecordStore,
        groupId: String,
    ) {
        val active = store.listPending().filter { operation ->
            operation.target.spaceId == groupId &&
                operation.target.packageName == FIXTURE_PACKAGE
        }
        assertTrue("exact clone must not retain active operations: $active", active.isEmpty())
    }

    private fun awaitGuestLaunchCount(environmentId: Int, expected: String) {
        val file = File(
            VEnvironment.getDataUserPackageDirectory(environmentId, FIXTURE_PACKAGE),
            "files/$LAUNCH_COUNT_FILE",
        )
        var actual: String? = null
        repeat(POLL_ATTEMPTS) {
            actual = file.takeIf(File::isFile)?.readText()?.trim()
            if (actual == expected) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error(
            "fixture Activity launch count did not reach $expected: " +
                "${file.absolutePath} (actual=$actual)",
        )
    }

    @Suppress("DEPRECATION")
    private fun awaitExactCloneInForeground(environmentId: Int) {
        var actualPackage: String? = null
        var actualEnvironmentId: Int? = null
        repeat(POLL_ATTEMPTS) {
            val physicalTaskId = context.getSystemService(ActivityManager::class.java)
                .getRunningTasks(1)
                .firstOrNull()
                ?.id
            val virtualTask = physicalTaskId?.let { VActivityManager.get().getTaskInfo(it) }
            actualPackage = virtualTask?.topActivity?.packageName
            actualEnvironmentId = virtualTask?.userId
            if (actualPackage == FIXTURE_PACKAGE && actualEnvironmentId == environmentId) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error(
            "exact fixture clone did not remain foreground: " +
                "expectedPackage=$FIXTURE_PACKAGE expectedEnvironmentId=$environmentId " +
                "actualPackage=$actualPackage actualEnvironmentId=$actualEnvironmentId",
        )
    }

    private fun isFixtureInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private fun executeShellCommand(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            descriptor.close()
        }
    }

    private companion object {
        const val OPT_IN_ARGUMENT = "addCloneFirstLaunchE2e"
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val LAUNCH_COUNT_FILE = "launch-count.txt"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
