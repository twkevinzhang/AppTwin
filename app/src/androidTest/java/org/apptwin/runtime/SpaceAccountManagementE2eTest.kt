package org.apptwin.runtime

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.MainActivity
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.accounts.SpaceAccountSync
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceAccountManagementE2eTest {
    @Test fun addSyncRemoveRemainsInSelectedSpace() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spaceAccountE2e") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AndroidPackageRevisionImporter(context).sync(TYPE)
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val host = ActivityScenario.launch(MainActivity::class.java)
        host.onActivity(mainActivityLaunchHosts::onResumed)
        val first = try {
            operations.reconcileGroups()
            operations.createGroup("Account E2E A")
        }
            catch (failure: Throwable) { host.close(); throw failure }
        var secondId: String? = null
        var fromBackupId: String? = null
        var backupId: String? = null
        var primaryFailure: Throwable? = null
        val backup = File(context.cacheDir, "account-e2e-${System.nanoTime()}.apptwin-space")
        try {
            val second = operations.createGroup("Account E2E B")
            secondId = second.id
            val a = operations.addAppToGroup(first.id, TYPE)
            val b = operations.addAppToGroup(second.id, TYPE)
            val userA = requireNotNull(a.environmentBinding).internalId
            val userB = requireNotNull(b.environmentBinding).internalId
            VirtualCore.get().waitForEngine()
            // Production account inventory prepares the active revision before guest binding.
            assertTrue(requireNotNull(operations.loadSpaceDetails(first.id)).accountTypes.any { it.type == TYPE })
            assertTrue(requireNotNull(operations.loadSpaceDetails(second.id)).accountTypes.any { it.type == TYPE })
            operations.addSpaceAccount(first.id, TYPE)
            val account = VAccountManager.get().getAccounts(userA, TYPE).single()
            assertTrue(VAccountManager.get().getAccounts(userB, TYPE).isEmpty())
            val adapter = SpaceAccountSync.adapters(userA, TYPE).single()
            val entry = requireNotNull(operations.loadSpaceDetails(first.id)).accounts.single { it.type == TYPE }
            assertEquals("同步完成", operations.synchronizeSpaceAccount(first.id, entry, adapter.authority))
            val proof = File(VEnvironment.getDataUserPackageDirectory(userA, TYPE),
                "shared_prefs/fixture_sync_proof.xml")
            assertTrue("Guest must finish an actual local sync", proof.isFile)
            assertTrue(proof.readText().contains("value=\"1\""))
            // Backup must carry only source-user AccountManager state, and remap its user identity.
            VAccountManager.get().remote.setUserData(userA, account, "fixture-proof", "synthetic")
            VAccountManager.get().remote.setAuthToken(userA, account, "fixture-token-type", "synthetic-token")
            operations.exportSpace(first.id, Uri.fromFile(backup), SpaceArchiveCompression.LOW)
            backupId = requireNotNull(operations.loadSpaceDetails(first.id)).backups
                .single { it.uri == Uri.fromFile(backup).toString() }.id
            val copied = operations.createSpaceFromBackup(Uri.fromFile(backup)).group
            fromBackupId = copied.id
            val copiedUser = requireNotNull(copied.environmentBinding).internalId
            assertNotEquals(userA, copiedUser)
            assertEquals(account, VAccountManager.get().getAccounts(copiedUser, TYPE).single())
            assertEquals("synthetic", VAccountManager.get().remote.getUserData(copiedUser, account, "fixture-proof"))
            assertEquals("synthetic-token", VAccountManager.get().remote.peekAuthToken(copiedUser, account, "fixture-token-type"))
            assertTrue(VAccountManager.get().getAccounts(userB, TYPE).isEmpty())
            assertEquals(org.apptwin.AccountRemovalResult.REMOVED, operations.removeSpaceAccount(first.id, entry))
            val removalProof = File(VEnvironment.getDataUserPackageDirectory(userA, TYPE),
                "shared_prefs/fixture_account_proof.xml")
            assertTrue("Authenticator must approve actual removal", removalProof.isFile)
            assertTrue(removalProof.readText().contains("value=\"1\""))
            assertTrue(VAccountManager.get().getAccounts(userA, TYPE).isEmpty())
            assertTrue(VAccountManager.get().getAccounts(userB, TYPE).isEmpty())
            assertFalse(SpaceAccountSync.synchronize(userA, account, adapter.authority).success)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { cleanupFailures += failure }
            }
            cleanup {
                val registeredId = backupId ?: operations.loadSpaceDetails(first.id)?.backups
                    ?.singleOrNull { it.uri == Uri.fromFile(backup).toString() }?.id
                registeredId?.let { operations.deleteBackup(it) }
            }
            cleanup { fromBackupId?.let { assertNotNull(operations.deleteGroup(it)) } }
            cleanup { secondId?.let { assertNotNull(operations.deleteGroup(it)) } }
            cleanup { assertNotNull(operations.deleteGroup(first.id)) }
            cleanup { host.close() }
            // A failed export may leave a partial local file before registry registration.
            cleanup { if (backup.exists()) assertTrue("Partial backup cleanup", backup.delete()) }
            if (primaryFailure != null) cleanupFailures.forEach { primaryFailure!!.addSuppressed(it) }
            else if (cleanupFailures.isNotEmpty()) {
                val firstFailure = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(firstFailure::addSuppressed)
                throw firstFailure
            }
        }
    }
    companion object { private const val TYPE = "org.apptwin.fixture" }
}
