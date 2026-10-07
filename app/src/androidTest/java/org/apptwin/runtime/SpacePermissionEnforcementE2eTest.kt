package org.apptwin.runtime

import android.Manifest
import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.os.VEnvironment
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** ASUS acceptance. Creates and deletes its own media/contact fixtures; never clears user data. */
@RunWith(AndroidJUnit4::class)
class SpacePermissionEnforcementE2eTest {
    private val diagnosticOutcomes = org.json.JSONArray()
    @Test fun realProviderAccessIsDeniedPerSpaceAndRevocationPersists() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val groups = mutableListOf<String>()
        val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val priorGrants = permissions.associateWith {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        // Revoking the host UID inside instrumentation kills its own process before the
        // terminal result can be delivered. The ADB harness restores this bounded list
        // after instrumentation has finished; no account or payload data is recorded.
        File(context.cacheDir, "permission-e2e-prior-host-grants.txt").writeText(
            priorGrants.entries.joinToString("\n") { "${it.key}=${it.value}" },
        )
        var media: android.net.Uri? = null
        var contact: android.net.Uri? = null
        var primaryFailure: Throwable? = null
        try {
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        // Recover only this acceptance fixture's sentinels left by interrupted runs.
        // Exact predicates deliberately exclude user contacts and unrelated media.
        context.contentResolver.delete(
            android.provider.ContactsContract.RawContacts.CONTENT_URI,
            "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${android.provider.ContactsContract.RawContacts.ACCOUNT_NAME} = ?",
            arrayOf("org.apptwin.fixture", "permission-e2e"),
        )
        context.contentResolver.delete(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("apptwin-permission-sentinel.png"),
        )
        operations.reconcileGroups()
        contact = requireNotNull(context.contentResolver.insert(
            android.provider.ContactsContract.RawContacts.CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, "org.apptwin.fixture")
                put(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, "permission-e2e")
            }))
        media = requireNotNull(context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "apptwin-permission-sentinel.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            }))
        context.contentResolver.openOutputStream(requireNotNull(media))!!.use { output ->
            val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            try { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
            finally { bitmap.recycle() }
        }
            val denied = operations.createGroup("權限探針拒絕")
            groups += denied.id
            val allowed = operations.createGroup("權限探針允許")
            groups += allowed.id
            for ((group, granted) in listOf(denied to false, allowed to true)) {
                operations.addAppToGroup(group.id, PACKAGE)
                val current = requireNotNull(FileGroupStore(context).find(group.id))
                val user = requireNotNull(current.environmentBinding).internalId
                val item = GroupAppItem(
                    groupId = current.id,
                    groupName = current.name,
                    groupHealth = current.health,
                    app = current.apps.single { it.packageName == PACKAGE },
                    appLabel = "Permission probe",
                    versionName = "fixture",
                    sourceInstalled = true,
                    launchStatus = "",
                )
                permissions.forEach {
                    assertTrue("product setter $it", operations.setClonePermission(item, it, granted))
                }
                val result = launchProbe(current, user)
                if (granted) {
                    assertEquals(result.toString(), "allowed", result.getString("contactsWrite"))
                    assertEquals(result.toString(), "allowed", result.getString("contactsRead"))
                    assertEquals(result.toString(), "allowed", result.getString("cameraOpen"))
                    assertEquals(result.toString(), "allowed", result.getString("microphoneRead"))
                    assertEquals(result.toString(), "allowed", result.getString("photosRead"))
                    assertEquals(result.toString(), "allowed", result.getString("photosOpen"))
                    assertEquals(result.toString(), "allowed", result.getString("photosDirect"))
                    // Subscription acceptance checks registration enforcement, not a delivered GPS fix.
                    assertEquals(result.toString(), "allowed", result.getString("locationSubscription"))
                    assertTrue(operations.setClonePermission(item, Manifest.permission.ACCESS_FINE_LOCATION, false))
                    val coarseOnly = launchProbe(current, user)
                    assertTrue(coarseOnly.toString(), coarseOnly.getString("locationSubscription") != "allowed")
                    assertEquals(coarseOnly.toString(), "allowed", coarseOnly.getString("coarseLocationSubscription"))
                    // Exercise an allow -> revoke transition, not just initial denied state.
                    permissions.forEach { assertTrue(operations.setClonePermission(item, it, false)) }
                    assertDenied(launchProbe(current, user))
                } else assertDenied(result)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            try {
                File(context.cacheDir, "permission-e2e-failure-outcomes.json").writeText(
                    JSONObject().put("failureType", failure.javaClass.simpleName)
                        .put("outcomes", diagnosticOutcomes).toString(),
                )
            } catch (diagnosticFailure: Throwable) {
                failure.addSuppressed(diagnosticFailure)
            }
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            for (groupId in groups.reversed()) {
                try {
                    val cleanup = operations.deleteGroup(groupId)
                    check(cleanup !is org.apptwin.DeleteGroupResult.Deleted || cleanup.cleanupWarning == null) {
                        "Owned space cleanup incomplete"
                    }
                } catch (failure: Throwable) { cleanupFailures += failure }
            }
            for (uri in listOfNotNull(media, contact)) {
                try {
                    check(context.contentResolver.delete(uri, null, null) > 0) { "Owned fixture cleanup incomplete" }
                } catch (failure: Throwable) { cleanupFailures += failure }
            }
            val originalFailure = primaryFailure
            if (originalFailure != null) {
                cleanupFailures.forEach { originalFailure.addSuppressed(it) }
            } else if (cleanupFailures.isNotEmpty()) {
                val cleanupFailure = AssertionError("Permission acceptance cleanup failed")
                cleanupFailures.forEach(cleanupFailure::addSuppressed)
                throw cleanupFailure
            }
        }
    }

    private fun assertDenied(result: JSONObject) {
        listOf("contactsRead", "contactsWrite", "photosRead", "photosOpen", "photosDirect", "locationSubscription", "coarseLocationSubscription", "locationRead", "cameraOpen", "microphoneRead")
            .forEach { assertTrue("$it: $result", result.getString(it) != "allowed") }
    }

    private fun launchProbe(group: org.apptwin.groups.Group, user: Int): JSONObject {
        val directory = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files")
        directory.mkdirs()
        File(directory, "enable-permission-probe").writeText("test-owned")
        val result = File(directory, "permission-enforcement.json")
        result.delete()
        com.lody.virtual.client.ipc.VActivityManager.get().killAppByPkg(PACKAGE, user)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity(mainActivityLaunchHosts::onResumed)
            val launch = VirtualRuntimeController(InstrumentationRegistry.getInstrumentation().targetContext)
                .installAndLaunch(group, group.apps.single { it.packageName == PACKAGE })
            assertTrue("Launch failed: $launch", launch is RuntimeLaunchResult.Started)
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            while (!result.exists() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Probe result timed out", result.exists())
            val parsed = JSONObject(result.readText())
            val safe = JSONObject()
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = parsed.optString(key)
                if (key.matches(Regex("[a-zA-Z]+")) &&
                    (value == "allowed" || value == "empty" || value.matches(Regex("blocked:[a-zA-Z]+")))) {
                    safe.put(key, value)
                }
            }
            if (parsed.has("readiness")) {
                val readiness = parsed.optString("readiness")
                if (readiness == "foreground-timeout") safe.put("readiness", readiness)
            }
            listOf("resumed", "hasWindowFocus", "isFinishing").forEach { key ->
                if (parsed.opt(key) is Boolean) safe.put(key, parsed.getBoolean(key))
            }
            diagnosticOutcomes.put(safe)
            assertTrue("Guest foreground readiness failed: $parsed",
                parsed.optString("readiness") != "foreground-timeout")
            listOf("contactsRead", "contactsWrite", "photosRead", "photosOpen", "photosDirect",
                "locationSubscription", "coarseLocationSubscription", "locationRead", "cameraOpen", "microphoneRead")
                .forEach { key -> assertTrue("Missing operation $key; guest result: $parsed", parsed.has(key)) }
            return parsed
        }
    }

    companion object { const val PACKAGE = "org.apptwin.fixture" }
}
