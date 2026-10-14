package org.apptwin.runtime

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An owned Space without Google accounts and unsupported options must never open the host chooser. */
@RunWith(AndroidJUnit4::class)
class OwnedGoogleCredentialBoundaryE2eTest {
    @Test
    fun emptySpaceGoogleAndUnsupportedRequestsFailClosedWithoutHostChooser() = runBlocking {
        assumeTrue("Framework CredentialManager requires API 34", Build.VERSION.SDK_INT >= 34)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val operations = (context.applicationContext as AppTwinApplication).mainContainer.operationGraph.mainOperations()
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        var owned: String? = null
        var primary: Throwable? = null
        try {
            val revision = AndroidPackageRevisionImporter(context).sync(PACKAGE)
            assertTrue(revision is RevisionImportResult.Activated || revision is RevisionImportResult.AlreadyCurrent)
            val group = operations.createGroup("Owned credential boundary")
            owned = group.id
            val prepared = operations.addAppToGroup(group.id, PACKAGE)
            val user = requireNotNull(prepared.environmentBinding).internalId
            assertTrue("Owned Space must have no configured Google account",
                VAccountManager.get().getAccounts(user, "com.google").isEmpty())
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                listOf(false, true).forEach { unsupported ->
                    assertTrue("Google account prerequisite must remain empty for the exact owned Space",
                        VAccountManager.get().getAccounts(user, "com.google").isEmpty())
                    VirtualCore.get().killApp(PACKAGE, user)
                    val files = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files")
                    check(files.isDirectory || files.mkdirs())
                    val request = UUID.randomUUID().toString()
                    File(files, "enable-owned-credential-probe").writeText(JSONObject().put("requestId", request)
                        .put("unsupportedOption", unsupported).toString())
                    val result = File(files, "owned-credential-outcome.json")
                    check(!result.exists() || result.delete())
                    host.awaitRealForegroundHost()
                    val launch = runBlocking { operations.launchGroupApp(prepared.item()) }
                    assertTrue("Real owned fixture launch", launch is RuntimeLaunchResult.Started)
                    launch as RuntimeLaunchResult.Started
                    assertEquals(PACKAGE, launch.packageName)
                    assertEquals(files.parentFile!!.absolutePath, launch.dataDirectory)
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    var outcome: JSONObject? = null
                    while (SystemClock.elapsedRealtime() < deadline) {
                        assertNoPhysicalHostChooser()
                        outcome = runCatching { JSONObject(result.readText()) }.getOrNull()
                        if (outcome?.optString("requestId") == request) break
                        Thread.sleep(100)
                    }
                    val observed = requireNotNull(outcome) { "Framework credential request did not terminate" }
                    assertEquals(request, observed.getString("requestId"))
                    assertEquals("error", observed.getString("status"))
                    assertEquals("android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL", observed.getString("errorType"))
                    assertEquals(1, observed.getInt("callbacks"))
                    repeat(10) { Thread.sleep(100); assertNoPhysicalHostChooser() }
                    assertEquals("No duplicate late callback", 1, JSONObject(result.readText()).getInt("callbacks"))
                }
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            owned?.let { id -> try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error } }
            try { assertEquals("Unowned Space membership preserved", baseline, tuples(store.loadSnapshot().groups)) }
            catch (error: Throwable) { errors += error }
            if (primary != null) errors.forEach(primary!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }

    private fun assertNoPhysicalHostChooser() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 3_000
        do {
            val snapshot = automation.executeShellCommand("dumpsys activity activities").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            val focused = snapshot.lineSequence().filter {
                it.contains("mResumedActivity") || it.contains("topResumedActivity")
            }.toList()
            assertFalse("Guest credential request escaped to physical Google chooser", focused.any {
                it.contains("com.google.android.gms/") || it.contains("CredentialChooserActivity") || it.contains("GoogleSignInActivity")
            })
            // A transition can briefly have no resumed activity. Require an actual witness;
            // never count missing metadata as evidence that the host chooser was absent.
            if (focused.any { it.contains("ActivityRecord{") }) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Physical resumed activity metadata unavailable after bounded transition wait")
    }
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE }, "Owned credential fixture", "fixture", true, "")
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",")}" }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
