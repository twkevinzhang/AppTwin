package org.apptwin.runtime

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
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
import org.junit.Test
import org.junit.runner.RunWith

/** Real startActivityForResult must report the guest parent, including API 31+ ActivityClient. */
@RunWith(AndroidJUnit4::class)
class OwnedActivityCallerIdentityE2eTest {
    @Test
    fun actualGuestChildSeesGuestCallingActivityAndReturnsExactlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operations = (context.applicationContext as AppTwinApplication).mainContainer.operationGraph.mainOperations()
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        var owned: String? = null
        var primary: Throwable? = null
        try {
            val imported = AndroidPackageRevisionImporter(context).sync(PACKAGE)
            assertTrue(imported is RevisionImportResult.Activated || imported is RevisionImportResult.AlreadyCurrent)
            val group = operations.createGroup("Owned caller identity")
            owned = group.id
            val prepared = operations.addAppToGroup(group.id, PACKAGE)
            val user = requireNotNull(prepared.environmentBinding).internalId
            VirtualCore.get().killApp(PACKAGE, user)
            val files = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files")
            check(files.isDirectory || files.mkdirs())
            val request = UUID.randomUUID().toString()
            File(files, "enable-owned-caller-probe").writeText(JSONObject().put("requestId", request).toString())
            val result = File(files, "owned-caller-outcome.json")
            check(!result.exists() || result.delete())
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                host.awaitRealForegroundHost()
                val launch = runBlocking { operations.launchGroupApp(prepared.item()) }
                assertTrue("Exact owned fixture launch", launch is RuntimeLaunchResult.Started)
                launch as RuntimeLaunchResult.Started
                assertEquals(PACKAGE, launch.packageName)
                assertEquals(files.parentFile!!.absolutePath, launch.dataDirectory)
                val deadline = SystemClock.elapsedRealtime() + 25_000
                var observed: JSONObject? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    observed = runCatching { JSONObject(result.readText()) }.getOrNull()
                    if (observed?.optString("requestId") == request) break
                    Thread.sleep(100)
                }
                val outcome = requireNotNull(observed) { "Guest caller result did not return" }
                assertEquals(request, outcome.getString("requestId"))
                listOf("resultOk", "requestMatches", "callingPackageMatches", "callingActivityPackageMatches",
                    "callingActivityClassMatches").forEach { key -> assertTrue(key, outcome.getBoolean(key)) }
                assertEquals(1, outcome.getInt("callbacks"))
                Thread.sleep(1_000)
                assertEquals("No duplicate result callback", 1, JSONObject(result.readText()).getInt("callbacks"))
                val lifecycle = JSONObject(File(files, "fixture-lifecycle.json").readText())
                assertTrue("Parent must resume after child result", lifecycle.getBoolean("resumed"))
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            owned?.let { id -> try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error } }
            try { assertEquals(baseline, tuples(store.loadSnapshot().groups)) } catch (error: Throwable) { errors += error }
            if (primary != null) errors.forEach(primary!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE }, "Owned caller fixture", "fixture", true, "")
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",")}" }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
