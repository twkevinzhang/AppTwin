package org.apptwin.runtime

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnedCameraMicrophoneE2eTest {
    @Test
    fun actualCapabilitiesRemainScopedAcrossRestartAndRevocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            assertEquals("Harness must grant host sensor permission before acceptance", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(it))
        }
        context.packageManager.getPackageInfo(PACKAGE, 0)
        AndroidPackageRevisionImporter(context).sync(PACKAGE)
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val store = FileGroupStore(context)
        val baseline = store.loadSnapshot().groups.map { it.id }.toSet()
        val owned = mutableListOf<String>()
        var primary: Throwable? = null
        try {
            val first = operations.createGroup("Owned camera A").also { owned += it.id }
            val second = operations.createGroup("Owned microphone B").also { owned += it.id }
            val a = operations.addAppToGroup(first.id, PACKAGE)
            val b = operations.addAppToGroup(second.id, PACKAGE)
            assertNotEquals(a.user(), b.user())
            assertTrue(operations.setClonePermission(a.item(), Manifest.permission.CAMERA, true))
            assertTrue(operations.setClonePermission(a.item(), Manifest.permission.RECORD_AUDIO, false))
            assertTrue(operations.setClonePermission(b.item(), Manifest.permission.CAMERA, false))
            assertTrue(operations.setClonePermission(b.item(), Manifest.permission.RECORD_AUDIO, true))
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                verifyProbe(operations, host, a, camera = true, microphone = false)
                verifyProbe(operations, host, b, camera = false, microphone = true)
                // Each probe kills/restarts only the exact owned guest, proving durable permission decisions.
                verifyProbe(operations, host, a, camera = true, microphone = false)
                verifyProbe(operations, host, b, camera = false, microphone = true)
                assertTrue(operations.setClonePermission(a.item(), Manifest.permission.CAMERA, false))
                verifyProbe(operations, host, a, camera = false, microphone = false)
                verifyProbe(operations, host, b, camera = false, microphone = true)
                assertTrue(operations.setClonePermission(b.item(), Manifest.permission.RECORD_AUDIO, false))
                verifyProbe(operations, host, b, camera = false, microphone = false)
                verifyProbe(operations, host, a, camera = false, microphone = false)
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val failures = mutableListOf<Throwable>()
            owned.asReversed().forEach { id ->
                try {
                    operations.deleteGroup(id)
                    assertNull("Owned space cleanup", store.find(id))
                } catch (error: Throwable) { failures += error }
            }
            try { assertEquals("Existing space identities preserved", baseline, store.loadSnapshot().groups.map { it.id }.toSet()) }
            catch (error: Throwable) { failures += error }
            if (primary != null) failures.forEach(primary!!::addSuppressed)
            else if (failures.isNotEmpty()) throw failures.first().also { failure -> failures.drop(1).forEach(failure::addSuppressed) }
        }
    }

    private fun verifyProbe(operations: AndroidMainOperations, host: ActivityScenario<MainActivity>,
        group: Group, camera: Boolean, microphone: Boolean) {
        val user = group.user()
        VirtualCore.get().killApp(PACKAGE, user)
        val directory = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files")
        check(directory.isDirectory || directory.mkdirs())
        val requestId = UUID.randomUUID().toString()
        File(directory, "enable-camera-mic-probe").writeText(requestId)
        val result = File(directory, "camera-mic-outcomes.json")
        check(!result.exists() || result.delete())
        host.awaitRealForegroundHost()
        val launch = runBlocking { operations.launchGroupApp(group.item()) }
        assertTrue("Exact owned guest launch", launch is RuntimeLaunchResult.Started)
        launch as RuntimeLaunchResult.Started
        assertEquals(PACKAGE, launch.packageName)
        assertEquals(directory.parentFile!!.absolutePath, launch.dataDirectory)
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!result.isFile && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Sensor probe timed out", result.isFile)
        val outcome = JSONObject(result.readText())
        assertEquals("Fresh invocation must complete", requestId, outcome.optString("requestId"))
        assertCapability(outcome.getString("cameraOpen"), camera)
        assertCapability(outcome.getString("microphoneRead"), microphone)
        val expected = "camera=$camera\nmicrophone=$microphone"
        assertEquals("Permission decision and actual capability agree", expected,
            File(directory, "permission-state.txt").readText().trim())
    }

    private fun assertCapability(outcome: String, allowed: Boolean) {
        if (allowed) assertEquals("allowed", outcome)
        else assertTrue("Denied capability must be blocked, not merely empty: $outcome", outcome.startsWith("blocked:"))
    }
    private fun Group.user() = requireNotNull(environmentBinding).internalId
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE },
        "Owned sensor fixture", "fixture", true, "")
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
