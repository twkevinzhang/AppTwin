package org.apptwin.runtime

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.DeleteGroupResult
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Root ADB harness chooses the synthetic image in the real system picker during the bounded wait. */
@RunWith(AndroidJUnit4::class)
class SelectedUriPermissionE2eTest {
    @Test fun explicitlySelectedImagePersistsWithoutBroadPhotoPermission() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val hostPermission = Manifest.permission.READ_EXTERNAL_STORAGE
        File(context.cacheDir, "selected-uri-e2e-prior-host-grants.txt").writeText(
            "$hostPermission=${context.checkSelfPermission(hostPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED}",
        )
        var media: Uri? = null
        var groupId: String? = null
        var originalFailure: Throwable? = null
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, hostPermission)
            context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(SENTINEL))
            operations.reconcileGroups()
            media = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, SENTINEL)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
                }))
            context.contentResolver.openOutputStream(media)!!.use { stream ->
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
                finally { bitmap.recycle() }
            }
            val created = operations.createGroup("選取圖片授權驗收")
            groupId = created.id
            operations.addAppToGroup(created.id, PACKAGE)
            val group = requireNotNull(FileGroupStore(context).find(created.id))
            val user = requireNotNull(group.environmentBinding).internalId
            val item = GroupAppItem(group.id, group.name, group.health,
                group.apps.single { it.packageName == PACKAGE }, "Selected URI probe", "fixture", true, "")
            assertTrue(operations.setClonePermission(item, hostPermission, false))
            val directory = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files")
            directory.mkdirs()
            File(directory, "enable-selected-uri-probe").writeText("test-owned")
            val resultFile = File(directory, "selected-uri-probe.json")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity(mainActivityLaunchHosts::onResumed)
                launch(group)
                // The harness taps '選取測試圖片', then this test-owned image in DocumentsUI.
                val selected = awaitResult(resultFile, "selection", 180_000)
                assertTrue(selected.toString(), selected.getBoolean("readable"))
                assertTrue(selected.toString(), selected.getBoolean("persisted"))
            }
            VActivityManager.get().killAppByPkg(PACKAGE, user)
            resultFile.delete()
            // The picker and guest pause the first host. A fresh resumed host is required
            // for the next product launch; no bypass of the foreground launch contract.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity(mainActivityLaunchHosts::onResumed)
                launch(group)
                val relaunched = awaitResult(resultFile, "relaunch", 30_000)
                assertTrue(relaunched.toString(), relaunched.getBoolean("readable"))
                assertTrue(relaunched.toString(), relaunched.getBoolean("persisted"))
            }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            val failures = mutableListOf<Throwable>()
            groupId?.let {
                try {
                    val cleanup = operations.deleteGroup(it)
                    check(cleanup !is DeleteGroupResult.Deleted || cleanup.cleanupWarning == null) {
                        "Selected URI owned space cleanup incomplete"
                    }
                } catch (failure: Throwable) { failures += failure }
            }
            media?.let {
                try { check(context.contentResolver.delete(it, null, null) > 0) }
                catch (failure: Throwable) { failures += failure }
            }
            val primary = originalFailure
            if (primary != null) failures.forEach(primary::addSuppressed)
            else if (failures.isNotEmpty()) {
                val failure = AssertionError("Selected URI fixture cleanup failed")
                failures.forEach(failure::addSuppressed)
                throw failure
            }
            // Host UID revocation is performed by the ADB harness after terminal result.
        }
    }

    private fun launch(group: Group) {
        val result = VirtualRuntimeController(InstrumentationRegistry.getInstrumentation().targetContext)
            .installAndLaunch(group, group.apps.single { it.packageName == PACKAGE })
        assertTrue("Selected URI guest failed to launch: $result", result is RuntimeLaunchResult.Started)
    }

    private fun awaitResult(file: File, phase: String, timeout: Long): JSONObject {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val parsed = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (parsed?.optString("phase") == phase) return parsed
            Thread.sleep(100)
        }
        throw AssertionError("Selected URI $phase result timed out")
    }

    private companion object {
        const val PACKAGE = "org.apptwin.fixture"
        const val SENTINEL = "apptwin-permission-sentinel.png"
    }
}
