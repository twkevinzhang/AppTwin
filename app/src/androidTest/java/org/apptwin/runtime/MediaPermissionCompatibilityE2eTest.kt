package org.apptwin.runtime

import android.Manifest
import android.app.Application
import android.app.ActivityManager
import android.app.KeyguardManager
import android.os.PowerManager
import android.view.WindowManager
import android.view.KeyEvent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in API31 physical-device test. Host fixture install/grant/restore belong to the caller. */
@RunWith(AndroidJUnit4::class)
class MediaPermissionCompatibilityE2eTest {
    @Test fun absentModernPermissionsNeverBypassLegacyMediaGate() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mediaCompatE2e") == "true")
        assumeTrue(Build.VERSION.SDK_INT == 31)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Host broad-media grant required before acceptance", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE))
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val media = mutableListOf<android.net.Uri>()
        val groups = mutableListOf<Group>()
        val nonce = UUID.randomUUID().toString()
        val ownedFile = File(context.cacheDir, "media-compat-owned.json")
        val summaries = org.json.JSONArray()
        fun persistOwned() {
            val temporary = File(context.cacheDir, "media-compat-owned.tmp")
            temporary.writeText(JSONObject().put("nonce", nonce)
                .put("groups", org.json.JSONArray().apply { groups.forEach { group ->
                    put(JSONObject().put("id", group.id).put("user", group.environmentBinding?.internalId))
                } }).put("media", org.json.JSONArray(media.map { it.toString() })).toString())
            check(temporary.renameTo(ownedFile)) { "Owned-resource manifest update failed" }
        }
        check(!ownedFile.exists() || JSONObject(ownedFile.readText()).let {
            it.getJSONArray("groups").length() == 0 && it.getJSONArray("media").length() == 0
        }) { "Previous owned resources require cleanup: ${ownedFile.name}" }
        persistOwned()
        var hostScenario: ActivityScenario<MainActivity>? = null
        var originalHost: MainActivity? = null
        var primary: Throwable? = null
        try {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            assertTrue("Screen must be awake and unlocked before creating owned fixtures", power.isInteractive && !keyguard.isKeyguardLocked)
            hostScenario = ActivityScenario.launch(MainActivity::class.java)
            hostScenario!!.onActivity {
                originalHost = it
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            fun hostMetadata(): String {
                val host = requireNotNull(originalHost)
                var metadata = ""
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val current = mainActivityLaunchHosts.current()
                    metadata = "lifecycle=${host.lifecycle.currentState}, focus=${host.hasWindowFocus()}, registrySame=${current === host}, registryNull=${current == null}, interactive=${power.isInteractive}, keyguard=${keyguard.isKeyguardLocked}"
                }
                return metadata
            }
            fun hostReady(): Boolean {
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val host = requireNotNull(originalHost)
                    ready = mainActivityLaunchHosts.current() === host && host.hasWindowFocus() &&
                        host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                        power.isInteractive && !keyguard.isKeyguardLocked
                }
                return ready
            }
            val baselineDeadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!hostReady() && android.os.SystemClock.elapsedRealtime() < baselineDeadline) Thread.sleep(100)
            assertTrue("Original host baseline not ready: ${hostMetadata()}", hostReady())
            val config = JSONObject()
            for (kind in listOf("image", "video")) {
                val collection = if (kind == "image") MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val name = "apptwin-media-$nonce.${if (kind == "image") "png" else "mp4"}"
                val bytes = if (kind == "image") ByteArrayOutputStream().use { stream ->
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(nonce.hashCode()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray() }
                    finally { bitmap.recycle() }
                } else "AppTwin-owned-video-$nonce".toByteArray()
                val uri = requireNotNull(context.contentResolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (kind == "image") "image/png" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (kind == "image") "Pictures/" else "Movies/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                media += uri
                persistOwned()
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val path = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                    check(it.moveToFirst()); it.getString(0)
                }
                config.put(kind, JSONObject().put("name", name).put("id", android.content.ContentUris.parseId(uri))
                    .put("path", path).put("bytes", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            }
            operations.reconcileGroups()
            repeat(2) { index ->
                val created = operations.createGroup("媒體相容驗收-$nonce-$index")
                groups += created
                persistOwned()
                operations.addAppToGroup(created.id, PACKAGE)
                groups[index] = requireNotNull(FileGroupStore(context).find(created.id))
                persistOwned()
            }
            fun item(group: Group) = GroupAppItem(group.id, group.name, group.health,
                group.apps.single { it.packageName == PACKAGE }, "Media compatibility fixture", "fixture", true, "")
            suspend fun grant(group: Group, value: Boolean) {
                assertTrue(operations.setClonePermission(item(group), Manifest.permission.READ_EXTERNAL_STORAGE, value))
                val user = requireNotNull(group.environmentBinding).internalId
                val cleanupDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                while (VActivityManager.get().isAppRunning(PACKAGE, user) &&
                    android.os.SystemClock.elapsedRealtime() < cleanupDeadline) Thread.sleep(100)
                val stopped = !VActivityManager.get().isAppRunning(PACKAGE, user)
                val cleanup = JSONObject().put("event", "permission-process-stop").put("groupId", group.id)
                    .put("user", user).put("legacyGranted", value).put("processAbsent", stopped)
                summaries.put(cleanup)
                File(context.cacheDir, "media-compat-summary.json").writeText(
                    JSONObject().put("nonce", nonce).put("results", summaries).toString())
                assertTrue("Exact fixture process must be absent after permission transition: $cleanup", stopped)
            }
            fun returnToOriginalHost() {
                val host = requireNotNull(originalHost)
                host.getSystemService(ActivityManager::class.java).appTasks
                    .single { it.taskInfo.taskId == host.taskId }.moveToFront()
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (!hostReady() && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                }
                assertTrue("Original MainActivity must resume through its existing task: ${hostMetadata()}", hostReady())
            }
            fun probe(group: Group, phase: String, allowed: Boolean) {
                returnToOriginalHost()
                val user = requireNotNull(group.environmentBinding).internalId
                val directory = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files").apply { mkdirs() }
                val resultFile = File(directory, "media-compat-result.json").apply { delete() }
                File(directory, "media-compat-config.json").writeText(JSONObject(config.toString()).put("phase", phase).toString())
                val launch = VirtualRuntimeController(context).installAndLaunch(group, group.apps.single { it.packageName == PACKAGE })
                if (launch !is RuntimeLaunchResult.Started) {
                    val evidenceDeadline = android.os.SystemClock.elapsedRealtime() + 2_000
                    var evidence: JSONObject? = null
                    while (android.os.SystemClock.elapsedRealtime() < evidenceDeadline) {
                        evidence = runCatching { JSONObject(resultFile.readText()) }.getOrNull()
                            ?.takeIf { it.optString("phase") == phase }
                        if (evidence != null) break
                        Thread.sleep(100)
                    }
                    val safe = JSONObject()
                    evidence?.let { result ->
                        listOf("phase", "pid", "taskId", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_EXTERNAL_STORAGE",
                            "imageQuery", "imageOpen", "imageDirect", "videoQuery", "videoOpen", "videoDirect")
                            .filter(result::has).forEach { safe.put(it, result.get(it)) }
                    }
                    val foregroundFixture = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("Media compatibility acceptance fixture")
                        ?.any { it.text?.toString() == "Media compatibility acceptance fixture" } == true
                    val diagnostic = JSONObject().put("phase", phase).put("groupId", group.id).put("user", user)
                        .put("launch", launch.toString()).put("foregroundFixture", foregroundFixture)
                        .put("host", hostMetadata()).put("exactPhaseResult", safe)
                    File(context.cacheDir, "media-compat-launch-diagnostic.json").writeText(diagnostic.toString())
                    android.util.Log.e("MediaCompatAcceptance", diagnostic.toString())
                }
                assertTrue("Fixture launch: $launch", launch is RuntimeLaunchResult.Started)
                val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                var result: JSONObject? = null
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    result = runCatching { JSONObject(resultFile.readText()) }.getOrNull()
                    if (result?.optString("phase") == phase) break
                    Thread.sleep(100)
                }
                val observed = requireNotNull(result) { "Fixture result timeout: $phase" }
                summaries.put(observed)
                File(context.cacheDir, "media-compat-summary.json").writeText(
                    JSONObject().put("nonce", nonce).put("results", summaries).toString())
                assertEquals(phase, observed.getString("phase"))
                assertTrue(observed.toString(), observed.getBoolean("READ_MEDIA_IMAGES"))
                assertTrue(observed.toString(), observed.getBoolean("READ_MEDIA_VIDEO"))
                assertEquals(observed.toString(), allowed, observed.getBoolean("READ_EXTERNAL_STORAGE"))
                for (kind in listOf("image", "video")) for (operation in listOf("Query", "Open", "Direct")) {
                    assertEquals("$phase $kind$operation: $observed", allowed, observed.getString("$kind$operation") == "allowed")
                }
                observed.put("phaseCompleted", true).put("closingFixture", false)
                File(context.cacheDir, "media-compat-summary.json").writeText(
                    JSONObject().put("nonce", nonce).put("results", summaries).toString())
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val fixtureTitle = "Media compatibility acceptance fixture"
                val closeObservationDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var foregroundFixture = false
                var foregroundRootNull = true
                while (!foregroundFixture && android.os.SystemClock.elapsedRealtime() < closeObservationDeadline) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    foregroundRootNull = root == null
                    foregroundFixture = root?.findAccessibilityNodeInfosByText(fixtureTitle)
                        ?.any { it.text?.toString() == fixtureTitle } == true
                    if (!foregroundFixture) Thread.sleep(100)
                }
                observed.put("foregroundFixture", foregroundFixture).put("foregroundRootNull", foregroundRootNull)
                    .put("hostBeforeClose", hostMetadata())
                File(context.cacheDir, "media-compat-summary.json").writeText(
                    JSONObject().put("nonce", nonce).put("results", summaries).toString())
                assertTrue("Must only close exact foreground fixture: $phase rootNull=$foregroundRootNull host=${hostMetadata()}", foregroundFixture)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                returnToOriginalHost()
                observed.put("closingFixture", true)
                File(context.cacheDir, "media-compat-summary.json").writeText(
                    JSONObject().put("nonce", nonce).put("results", summaries).toString())
            }
            grant(groups[0], true); grant(groups[1], false)
            probe(groups[0], "allowed-A", true); probe(groups[1], "denied-B", false)
            grant(groups[0], false)
            probe(groups[0], "revoked-A", false)
            grant(groups[1], true)
            probe(groups[1], "allowed-B", true); probe(groups[0], "still-denied-A", false)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            val failures = mutableListOf<Throwable>()
            groups.toList().forEach { group -> runCatching {
                val result = operations.deleteGroup(group.id)
                check(result is DeleteGroupResult.Deleted && result.cleanupWarning == null) {
                    "Owned space cleanup failed: ${group.id}"
                }
                groups.remove(group)
                persistOwned()
            }.exceptionOrNull()?.let(failures::add) }
            media.toList().forEach { uri -> runCatching {
                check(context.contentResolver.delete(uri, null, null) == 1) { "Owned media cleanup failed: $uri" }
                media.remove(uri)
                persistOwned()
            }.exceptionOrNull()?.let(failures::add) }
            runCatching {
                originalHost?.let { host ->
                    if (!host.isDestroyed) {
                        host.getSystemService(ActivityManager::class.java).appTasks
                            .single { it.taskInfo.taskId == host.taskId }.apply { moveToFront(); finishAndRemoveTask() }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    }
                }
                hostScenario?.close()
            }.exceptionOrNull()?.let(failures::add)
            if (primary != null) failures.forEach { primary!!.addSuppressed(it) }
            else if (failures.isNotEmpty()) throw AssertionError("Media compatibility cleanup failed; remaining groups=${groups.map { it.id }}, media=$media").apply { failures.forEach(::addSuppressed) }
        }
    }
    /** Minimum reproduction of guest A → B → A without media or permission mutation. */
    @Test fun guestRestartSmoke() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mediaCompatSmoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val groups = mutableListOf<Group>()
        val nonce = UUID.randomUUID().toString()
        val ownedFile = File(context.cacheDir, "media-compat-smoke-owned.json")
        check(!ownedFile.exists() || JSONObject(ownedFile.readText()).getJSONArray("groups").length() == 0) {
            "Previous smoke resources require cleanup"
        }
        fun persist() {
            val temporary = File(context.cacheDir, "media-compat-smoke-owned.tmp")
            temporary.writeText(JSONObject().put("nonce", nonce).put("groups", org.json.JSONArray().apply {
                groups.forEach { group -> put(JSONObject().put("id", group.id).put("user", group.environmentBinding?.internalId)) }
            }).toString())
            check(temporary.renameTo(ownedFile))
        }
        persist()
        var scenario: ActivityScenario<MainActivity>? = null
        var host: MainActivity? = null
        var primary: Throwable? = null
        val phases = org.json.JSONArray()
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        fun metadata(): JSONObject {
            var value = JSONObject()
            instrumentation.runOnMainSync {
                val activity = requireNotNull(host)
                value = JSONObject().put("lifecycle", activity.lifecycle.currentState.toString())
                    .put("focus", activity.hasWindowFocus()).put("registrySame", mainActivityLaunchHosts.current() === activity)
                    .put("registryNull", mainActivityLaunchHosts.current() == null)
                    .put("interactive", power.isInteractive).put("keyguard", keyguard.isKeyguardLocked)
            }
            return value
        }
        fun ready(): Boolean {
            val state = metadata()
            return state.getBoolean("registrySame") && state.getBoolean("focus") &&
                state.getString("lifecycle") == "RESUMED" && state.getBoolean("interactive") && !state.getBoolean("keyguard")
        }
        fun returnHost() {
            val activity = requireNotNull(host)
            activity.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == activity.taskId }.moveToFront()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!ready() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Smoke host not ready: ${metadata()}", ready())
        }
        try {
            assertTrue("Smoke requires awake/unlocked device", power.isInteractive && !keyguard.isKeyguardLocked)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { host = it; it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            returnHost()
            operations.reconcileGroups()
            repeat(2) { index ->
                val created = operations.createGroup("媒體啟動驗收-$nonce-$index")
                groups += created; persist()
                operations.addAppToGroup(created.id, PACKAGE)
                groups[index] = requireNotNull(FileGroupStore(context).find(created.id)); persist()
            }
            for ((phase, group) in listOf("A-first" to groups[0], "B-first" to groups[1], "A-restart" to groups[0])) {
                returnHost()
                val user = requireNotNull(group.environmentBinding).internalId
                if (phase == "A-restart") VActivityManager.get().killAppByPkg(PACKAGE, user)
                val launch = VirtualRuntimeController(context).installAndLaunch(group, group.apps.single { it.packageName == PACKAGE })
                val title = "Media compatibility acceptance fixture"
                val visibleDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var visible = false
                while (!visible && android.os.SystemClock.elapsedRealtime() < visibleDeadline) {
                    visible = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)
                        ?.any { it.text?.toString() == title } == true
                    if (!visible) Thread.sleep(100)
                }
                val record = JSONObject().put("phase", phase).put("groupId", group.id).put("user", user)
                    .put("launch", launch.toString()).put("foregroundFixture", visible).put("host", metadata())
                phases.put(record)
                File(context.cacheDir, "media-compat-smoke-summary.json").writeText(JSONObject().put("nonce", nonce).put("phases", phases).toString())
                assertTrue("Smoke launch failed: $record", launch is RuntimeLaunchResult.Started)
                assertTrue("Smoke only closes exact foreground fixture: $record", visible)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                returnHost()
                record.put("closingFixture", true)
                File(context.cacheDir, "media-compat-smoke-summary.json").writeText(JSONObject().put("nonce", nonce).put("phases", phases).toString())
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            val failures = mutableListOf<Throwable>()
            groups.toList().forEach { group -> runCatching {
                val deleted = operations.deleteGroup(group.id)
                check(deleted is DeleteGroupResult.Deleted && deleted.cleanupWarning == null) { "Smoke cleanup failed: ${group.id}" }
                groups.remove(group); persist()
            }.exceptionOrNull()?.let(failures::add) }
            runCatching {
                host?.let { activity -> if (!activity.isDestroyed) activity.getSystemService(ActivityManager::class.java)
                    .appTasks.single { it.taskInfo.taskId == activity.taskId }.apply { moveToFront(); finishAndRemoveTask() } }
                instrumentation.waitForIdleSync()
                scenario?.close()
            }.exceptionOrNull()?.let(failures::add)
            if (primary != null) failures.forEach { primary!!.addSuppressed(it) }
            else if (failures.isNotEmpty()) throw AssertionError("Smoke cleanup failed; remaining=${groups.map { it.id }}").apply { failures.forEach(::addSuppressed) }
        }
    }

    private companion object { const val PACKAGE = "org.apptwin.fixture.mediacompat" }
}
