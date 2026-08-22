package org.apptwin.runtime

import android.app.Application
import android.content.Context
import java.io.FileInputStream
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.MainActivity
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in Pixel-device proof for clone card labels in Android's recent-apps overview. */
@RunWith(AndroidJUnit4::class)
class CloneRecentTaskDescriptionE2eTest {
    @Test
    fun virtualCloneTaskUsesAppTwinLabel() = runBlocking {
        assumeTrue(
            "recent-task E2E is opt-in; pass -e cloneRecentTaskE2e 1",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "1",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("self-owned fixture must be installed", isFixtureInstalled(context))
        val imported = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "fixture revision must activate: $imported",
            imported is RevisionImportResult.Activated || imported is RevisionImportResult.AlreadyCurrent,
        )

        val operations = AndroidMainOperations(context.applicationContext as Application)
        val group = operations.createGroup("Recent task E2E")
        try {
            val groupWithApp = operations.addAppToGroup(group.id, FIXTURE_PACKAGE)
            val fixture = requireNotNull(groupWithApp.apps.singleOrNull())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity(mainActivityLaunchHosts::onResumed)
                val launched = VirtualRuntimeController(context).installAndLaunch(groupWithApp, fixture)
                assertTrue("expected guest launch but was $launched", launched is RuntimeLaunchResult.Started)

                val dump = awaitGuestTaskDump()
                assertTrue(
                    "guest task must expose the AppTwin recent-task label:\n$dump",
                    GUEST_TASK_DESCRIPTION.containsMatchIn(dump),
                )
            }
        } finally {
            assertNotNull("E2E Space cleanup must succeed", operations.deleteGroup(group.id))
        }
    }

    private fun awaitGuestTaskDump(): String {
        repeat(POLL_ATTEMPTS) {
            val descriptor = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("dumpsys activity activities")
            val dump = FileInputStream(descriptor.fileDescriptor)
                .bufferedReader()
                .use { it.readText() }
            descriptor.close()
            if (dump.contains(STUB_ACTIVITY_PREFIX)) {
                return dump
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("guest StubActivity task did not appear in recent tasks")
    }

    private fun isFixtureInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0)
    }.isSuccess

    private companion object {
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val OPT_IN_ARGUMENT = "cloneRecentTaskE2e"
        const val STUB_ACTIVITY_PREFIX = "com.lody.virtual.client.stub.StubActivity"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
        val GUEST_TASK_DESCRIPTION = Regex(
            "${Regex.escape(STUB_ACTIVITY_PREFIX)}.*?taskDescription: label=\\\"AppTwin\\\"",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
