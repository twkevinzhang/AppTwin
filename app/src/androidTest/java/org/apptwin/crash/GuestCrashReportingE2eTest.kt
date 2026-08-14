package org.apptwin.crash

import android.app.Application
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.MainActivity
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.apptwin.runtime.VirtualRuntimeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device proof that a virtual guest fatal reaches the private journal. */
@RunWith(AndroidJUnit4::class)
class GuestCrashReportingE2eTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun virtualGuestFatalIsPersistedWithoutRawGuestIdentity() = runBlocking {
        assumeOptIn()
        assertFixtureReady()
        val journal = GuestCrashJournal(context)
        val existingIds = journal.pending().mapTo(mutableSetOf(), GuestCrashRecord::id)
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val group = operations.createGroup("Crash reporting probe")

        try {
            val groupWithFixture = operations.addAppToGroup(group.id, FIXTURE_PACKAGE)
            val fixture = requireNotNull(
                groupWithFixture.apps.singleOrNull { it.packageName == FIXTURE_PACKAGE },
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                // The intentional guest exception can race the launch acknowledgement. The
                // acceptance signal is the independently persisted journal record below.
                VirtualRuntimeController(context).installAndLaunch(
                    groupWithFixture,
                    fixture,
                    CRASH_ACTIVITY,
                )
                val record = awaitNewRecord(journal, existingIds)
                assertEquals(EXCEPTION_CLASS, record.exceptionClassName)
                assertTrue(record.packageNameHash.matches(HASH))
                assertTrue(record.processNameHash.matches(HASH))
                assertTrue(record.stackFrames.any { it.className == CRASH_ACTIVITY })
            }
        } finally {
            runCatching { operations.deleteGroup(group.id) }
        }
    }

    private fun assertFixtureReady() {
        assertEquals(
            2L,
            context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0).longVersionCode,
        )
        val revision = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "host fixture revision must activate: $revision",
            revision is RevisionImportResult.Activated ||
                revision is RevisionImportResult.AlreadyCurrent,
        )
    }

    private fun awaitNewRecord(
        journal: GuestCrashJournal,
        existingIds: Set<String>,
    ): GuestCrashRecord {
        repeat(POLL_ATTEMPTS) {
            journal.pending().firstOrNull { it.id !in existingIds }?.let { return it }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("virtual guest crash did not reach the private journal")
    }

    private fun assumeOptIn() {
        val phase = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue(
            "guest crash reporting is opt-in; pass -e $PHASE_ARGUMENT 1",
            phase == "1",
        )
    }

    private companion object {
        const val PHASE_ARGUMENT = "crashReportingPhase"
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
        const val CRASH_ACTIVITY = "$FIXTURE_PACKAGE.SyntheticCrashActivity"
        const val EXCEPTION_CLASS = "$CRASH_ACTIVITY\$SyntheticGuestCrashException"
        const val POLL_ATTEMPTS = 100
        const val POLL_INTERVAL_MILLIS = 100L
        val HASH = Regex("[0-9a-f]{64}")
    }
}
