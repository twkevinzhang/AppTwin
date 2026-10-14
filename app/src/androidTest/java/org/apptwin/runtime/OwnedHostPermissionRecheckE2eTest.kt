package org.apptwin.runtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Host revocation happens only in the external harness before this runner starts, then is restored. */
@RunWith(AndroidJUnit4::class)
class OwnedHostPermissionRecheckE2eTest {
    @Test
    fun revokedHostGrantCannotEnableEitherOwnedClone() = runBlocking {
        assertEquals("Explicit external permission-recheck phase required", "1",
            InstrumentationRegistry.getArguments().getString("hostPermissionRecheckE2e"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AppTwinApplication
        val operations = app.mainContainer.operationGraph.mainOperations()
        assertEquals("Harness must revoke CAMERA before launching this runner", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        context.packageManager.getPackageInfo(PACKAGE, 0)
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        val owned = mutableListOf<String>()
        var primary: Throwable? = null
        try {
            val groups = listOf("A", "B").map { suffix ->
                val group = operations.createGroup("Owned host revoke $suffix").also { owned += it.id }
                operations.addAppToGroup(group.id, PACKAGE)
            }
            assertNotEquals(groups[0].user(), groups[1].user())
            groups.forEach { group ->
                assertTrue(operations.setClonePermission(group.item(), Manifest.permission.CAMERA, false))
                assertTrue(operations.setClonePermission(group.item(), Manifest.permission.RECORD_AUDIO, false))
                assertFalse("Host denial must reject a new guest grant", operations.setClonePermission(group.item(), Manifest.permission.CAMERA, true))
                assertEquals(PackageManager.PERMISSION_DENIED,
                    VPackageManager.get().checkPermission(Manifest.permission.CAMERA, PACKAGE, group.user()))
                val row = requireNotNull(operations.loadSpaceDetails(group.id)).permissions.single { it.permission == Manifest.permission.CAMERA }
                assertFalse(row.hostGranted)
                assertFalse(row.apps.single { it.packageName == PACKAGE }.granted)
            }
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                groups.forEach { group ->
                    val directory = File(VEnvironment.getDataUserPackageDirectory(group.user(), PACKAGE), "files")
                    check(directory.isDirectory || directory.mkdirs())
                    val request = UUID.randomUUID().toString()
                    File(directory, "enable-camera-mic-probe").writeText(request)
                    val result = File(directory, "camera-mic-outcomes.json")
                    check(!result.exists() || result.delete())
                    host.awaitRealForegroundHost()
                    assertTrue(operations.launchGroupApp(group.item()) is RuntimeLaunchResult.Started)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                    while (!result.isFile && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                    assertTrue("Actual revoked-host camera probe completed", result.isFile)
                    val outcome = JSONObject(result.readText())
                    assertEquals(request, outcome.getString("requestId"))
                    assertTrue("Real camera API must be blocked", outcome.getString("cameraOpen").startsWith("blocked:"))
                }
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            owned.asReversed().forEach { id ->
                try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error }
            }
            try { assertEquals(baseline, tuples(store.loadSnapshot().groups)) } catch (error: Throwable) { errors += error }
            if (primary != null) errors.forEach(primary!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }
    private fun Group.user() = requireNotNull(environmentBinding).internalId
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE }, "Owned host fixture", "fixture", true, "")
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",") }" }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
