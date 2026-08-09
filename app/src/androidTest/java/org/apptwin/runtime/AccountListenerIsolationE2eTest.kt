package org.apptwin.runtime

import android.accounts.Account
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in ASUS proof that AccountManager listener delivery stays inside one virtual user. */
@RunWith(AndroidJUnit4::class)
class AccountListenerIsolationE2eTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    private val operations
        get() = AndroidMainOperations(context.applicationContext as Application)

    @Test
    fun accountChangesNotifyOnlyTheMutatedGroup() = runBlocking {
        assumeTrue(
            "pass -e $OPT_IN_ARGUMENT 1",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "1",
        )
        val snapshot = FileGroupStore(context).loadSnapshot()
        assertTrue("Group metadata must be readable: ${snapshot.issues}", snapshot.issues.isEmpty())
        val existing = requireNotNull(snapshot.groups.singleOrNull { it.contains(MAPS_PACKAGE) }) {
            "Expected exactly one existing Maps Group"
        }
        require(!existing.contains(FIXTURE_PACKAGE)) {
            "$FIXTURE_PACKAGE must not already belong to the Maps Group"
        }
        val imported = AndroidPackageRevisionImporter(context).sync(FIXTURE_PACKAGE)
        assertTrue(
            "fixture revision must be importable: $imported",
            imported is RevisionImportResult.Activated ||
                imported is RevisionImportResult.AlreadyCurrent,
        )

        val temporary = operations.createGroup("Account listener isolation")
        var existingWithFixture: Group? = null
        var temporaryWithFixture: Group? = null
        val createdAccounts = mutableListOf<Pair<Int, Account>>()
        try {
            existingWithFixture = operations.addAppToGroup(existing.id, FIXTURE_PACKAGE)
            temporaryWithFixture = operations.addAppToGroup(temporary.id, FIXTURE_PACKAGE)
            val userA = existingWithFixture.environmentId()
            val userB = temporaryWithFixture.environmentId()

            assertStarted(operations.launchGroupApp(existingWithFixture.fixtureItem()))
            assertStarted(operations.launchGroupApp(temporaryWithFixture.fixtureItem()))
            val baselineA = awaitEventCount(userA, minimum = 1)
            val baselineB = awaitEventCount(userB, minimum = 1)

            val accountA = syntheticAccount("a")
            assertTrue(VAccountManager.get().remote.addAccountExplicitly(
                userA, accountA, "fixture", Bundle.EMPTY,
            ))
            createdAccounts += userA to accountA
            awaitEventCount(userA, minimum = baselineA + 1)
            assertEventCountRemains(userB, baselineB)

            val stableA = eventCount(userA)
            val accountB = syntheticAccount("b")
            assertTrue(VAccountManager.get().remote.addAccountExplicitly(
                userB, accountB, "fixture", Bundle.EMPTY,
            ))
            createdAccounts += userB to accountB
            awaitEventCount(userB, minimum = baselineB + 1)
            assertEventCountRemains(userA, stableA)
        } finally {
            createdAccounts.forEach { (userId, account) ->
                VAccountManager.get().remote.removeAccountExplicitly(userId, account)
            }
            existingWithFixture?.let { group ->
                assertTrue(
                    operations.uninstallGroupApp(group.fixtureItem()) is
                        GroupAppRemovalResult.Succeeded,
                )
            }
            temporaryWithFixture?.let { group ->
                if (FileGroupStore(context).find(group.id)?.contains(FIXTURE_PACKAGE) == true) {
                    operations.uninstallGroupApp(group.fixtureItem())
                }
            }
            operations.deleteGroup(temporary.id)
        }
    }

    private fun awaitEventCount(userId: Int, minimum: Int): Int {
        repeat(POLL_ATTEMPTS) {
            val count = eventCount(userId)
            if (count >= minimum) return count
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error("account listener user=$userId did not reach event count $minimum")
    }

    private fun assertEventCountRemains(userId: Int, expected: Int) {
        Thread.sleep(ISOLATION_OBSERVATION_MILLIS)
        assertEquals("cross-Group account event leaked to user=$userId", expected, eventCount(userId))
    }

    private fun eventCount(userId: Int): Int = listenerFile(userId)
        .takeIf(File::isFile)
        ?.readLines()
        ?.count(String::isNotBlank)
        ?: 0

    private fun listenerFile(userId: Int): File = File(
        VEnvironment.getDataUserPackageDirectory(userId, FIXTURE_PACKAGE),
        "files/account-listener-events.txt",
    )

    private fun syntheticAccount(suffix: String) = Account(
        "apptwin-listener-$suffix-${UUID.randomUUID()}",
        "org.apptwin.fixture.listener",
    )

    private fun Group.fixtureItem(): GroupAppItem {
        val app = requireNotNull(apps.singleOrNull { it.packageName == FIXTURE_PACKAGE })
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = app,
            appLabel = "AppTwin GMS Capability Fixture",
            versionName = "fixture",
            sourceInstalled = true,
            launchStatus = "",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("fixture clone must start: $result", result is RuntimeLaunchResult.Started)
    }

    private companion object {
        const val OPT_IN_ARGUMENT = "accountListenerE2e"
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val FIXTURE_PACKAGE = "org.apptwin.gms.fixture"
        const val POLL_ATTEMPTS = 30
        const val POLL_INTERVAL_MILLIS = 250L
        const val ISOLATION_OBSERVATION_MILLIS = 1_000L
    }
}
