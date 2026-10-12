package org.apptwin.runtime

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserHandle
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.DeleteGroupResult
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only test-owned files. Host installation/grants/restore are owned by the external executor. */
@RunWith(AndroidJUnit4::class)
class SharedMediaWritePermissionE2eTest {
    @Test fun sharedWritesRespectIndependentSpacePermission() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sharedMediaWriteE2e") == "true")
        assumeTrue(Build.VERSION.SDK_INT == 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            assertEquals("Host grant prerequisite $permission", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val groups = mutableListOf<Group>()
        val media = mutableListOf<Uri>()
        val paths = mutableListOf<File>()
        val nonce = UUID.randomUUID().toString()
        val original = "AppTwin-owned-original-$nonce".toByteArray()
        val changed = "AppTwin-owned-mutated-$nonce".toByteArray()
        val relativeChanged = "AppTwin-owned-relative-mutated-$nonce".toByteArray()
        val summaries = JSONArray()
        val owner = File(context.cacheDir, "shared-media-write-owned.json")
        check(!owner.exists() || JSONObject(owner.readText()).let {
            it.getJSONArray("groups").length() == 0 && it.getJSONArray("media").length() == 0 && it.getJSONArray("paths").length() == 0
        }) { "Previous shared-write resources require exact cleanup" }
        fun persist() {
            val temp = File(context.cacheDir, "shared-media-write-owned.tmp")
            temp.writeText(JSONObject().put("nonce", nonce).put("groups", JSONArray().apply {
                groups.forEach { put(JSONObject().put("id", it.id).put("user", it.environmentBinding?.internalId)) }
            }).put("media", JSONArray(media.map(Uri::toString))).put("paths", JSONArray(paths.map(File::getAbsolutePath))).toString())
            check(temp.renameTo(owner))
        }
        fun saveSummary() = File(context.cacheDir, "shared-media-write-summary.json").writeText(
            JSONObject().put("nonce", nonce).put("results", summaries).toString())
        persist()
        var scenario: ActivityScenario<MainActivity>? = null
        var host: MainActivity? = null
        var primary: Throwable? = null
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        fun hostState(): JSONObject {
            var result = JSONObject()
            instrumentation.runOnMainSync {
                val activity = requireNotNull(host)
                result = JSONObject().put("lifecycle", activity.lifecycle.currentState.toString()).put("focus", activity.hasWindowFocus())
                    .put("registrySame", mainActivityLaunchHosts.current() === activity).put("interactive", power.isInteractive)
                    .put("keyguard", keyguard.isKeyguardLocked)
            }
            return result
        }
        fun ready(): Boolean = hostState().let { it.getString("lifecycle") == "RESUMED" && it.getBoolean("focus") &&
            it.getBoolean("registrySame") && it.getBoolean("interactive") && !it.getBoolean("keyguard") }
        fun returnHost() {
            val activity = requireNotNull(host)
            activity.getSystemService(ActivityManager::class.java).appTasks.single { it.taskInfo.taskId == activity.taskId }.moveToFront()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!ready() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Owned host must resume: ${hostState()}", ready())
        }
        fun hash(file: File): String = if (!file.exists()) "absent" else MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        try {
            assertTrue("Awake/unlocked prerequisite", power.isInteractive && !keyguard.isKeyguardLocked)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { host = it; it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            returnHost()
            operations.reconcileGroups()
            repeat(2) { index ->
                val created = operations.createGroup("共享寫入驗收-$nonce-$index")
                groups += created; persist()
                operations.addAppToGroup(created.id, PACKAGE)
                groups[index] = requireNotNull(FileGroupStore(context).find(created.id)); persist()
            }
            suspend fun setPermissions(group: Group, read: Boolean, write: Boolean) {
                returnHost()
                val item = GroupAppItem(group.id, group.name, group.health, group.apps.single { it.packageName == PACKAGE },
                    "Media write fixture", "fixture", true, "")
                for ((permission, value) in listOf(Manifest.permission.READ_EXTERNAL_STORAGE to read,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE to write)) {
                    assertTrue(operations.setClonePermission(item, permission, value))
                    val user = requireNotNull(group.environmentBinding).internalId
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                    while (VActivityManager.get().isAppRunning(PACKAGE, user) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                    assertTrue("Exact fixture process must stop after $permission", !VActivityManager.get().isAppRunning(PACKAGE, user))
                }
            }
            fun createOwned(phase: String, key: String): JSONObject {
                val name = "apptwin-write-$nonce-$phase-$key.png"
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }))
                media += uri; persist()
                context.contentResolver.openOutputStream(uri)!!.use { it.write(original) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val path = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
                    check(it.moveToFirst()); File(it.getString(0))
                }
                paths += path; persist()
                return JSONObject().put("name", name).put("id", android.content.ContentUris.parseId(uri)).put("path", path.absolutePath)
            }
            suspend fun phase(group: Group, label: String, read: Boolean, write: Boolean) {
                setPermissions(group, read, write)
                val config = JSONObject().put("mode", "shared-write").put("phase", label)
                    .put("original", Base64.encodeToString(original, Base64.NO_WRAP)).put("changed", Base64.encodeToString(changed, Base64.NO_WRAP))
                    .put("relativeChanged", Base64.encodeToString(relativeChanged, Base64.NO_WRAP))
                val keys = listOf("read", "providerWrite", "directTruncate", "directRename", "directDelete")
                keys.forEach { config.put(it, createOwned(label, it)) }
                val parent = File(config.getJSONObject("read").getString("path")).parentFile!!
                for (key in listOf("directCreate", "renameDestination")) {
                    val file = File(parent, "apptwin-write-$nonce-$label-$key.png")
                    check(!file.exists()); paths += file; persist()
                    config.put(key, JSONObject().put("path", file.absolutePath))
                }
                val files = (keys + listOf("directCreate", "renameDestination")).associateWith { File(config.getJSONObject(it).getString("path")) }
                val before = files.mapValues { hash(it.value) }
                val user = requireNotNull(group.environmentBinding).internalId
                val directory = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files").apply { mkdirs() }
                val output = File(directory, "media-compat-result.json").apply { delete() }
                File(directory, "media-compat-config.json").writeText(config.toString())
                returnHost()
                val launch = VirtualRuntimeController(context).installAndLaunch(group, group.apps.single { it.packageName == PACKAGE })
                val deadline = android.os.SystemClock.elapsedRealtime() + if (launch is RuntimeLaunchResult.Started) 30_000 else 2_000
                var result: JSONObject? = null
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    result = runCatching { JSONObject(output.readText()) }.getOrNull()?.takeIf { it.optString("phase") == label }
                    if (result != null) break
                    Thread.sleep(100)
                }
                val observed = result ?: JSONObject().put("phase", label)
                if (launch !is RuntimeLaunchResult.Started || result == null) {
                    val exactPids = JSONArray()
                    val manager = context.getSystemService(ActivityManager::class.java)
                    manager.runningAppProcesses.orEmpty().forEach { process ->
                        runCatching {
                            val vam = VActivityManager.get()
                            if (vam.getInitialPackage(process.pid) == PACKAGE &&
                                VUserHandle.getUserId(vam.getUidByPid(process.pid)) == user) exactPids.put(process.pid)
                        }
                    }
                    observed.put("exactFixturePids", exactPids)
                        .put("exactFixtureRunning", VActivityManager.get().isAppRunning(PACKAGE, user))
                }
                observed.put("launch", launch.toString()).put("host", hostState()).put("groupId", group.id).put("user", user)
                    .put("hostBefore", JSONObject(before)).put("hostAfter", JSONObject(files.mapValues { hash(it.value) }))
                summaries.put(observed); saveSummary()
                assertTrue("Strict guest launch $observed", launch is RuntimeLaunchResult.Started)
                assertTrue("Fixture result missing $label", result != null)
                assertEquals(read, observed.getBoolean("READ_EXTERNAL_STORAGE"))
                assertEquals(write, observed.getBoolean("WRITE_EXTERNAL_STORAGE"))
                for (key in listOf("sharedQuery", "sharedOpen", "sharedDirectRead")) assertEquals("$label $key", read, observed.getString(key) == "allowed")
                for (key in listOf("providerWrite", "directTruncate", "relativeWrite", "directRename", "directDelete", "directCreate")) {
                    assertEquals("$label $key: $observed", write, observed.getString(key) == "allowed")
                }
                assertEquals("allowed", observed.getString("privateWrite")); assertEquals("allowed", observed.getString("virtualWrite"))
                if (!write) files.forEach { (key, file) -> assertEquals("Host unchanged: $label $key", before.getValue(key), hash(file)) }
                else {
                    for (key in listOf("providerWrite", "directCreate")) assertTrue("Real host write $key", files.getValue(key).readBytes().contentEquals(changed))
                    assertTrue("Real host relative-dirfd write", files.getValue("directTruncate").readBytes().contentEquals(relativeChanged))
                    assertTrue("Real host rename destination", files.getValue("renameDestination").readBytes().contentEquals(original))
                    assertTrue("Real host rename source absent", !files.getValue("directRename").exists())
                    assertTrue("Real host delete", !files.getValue("directDelete").exists())
                }
                assertTrue("Read sentinel unchanged", files.getValue("read").readBytes().contentEquals(original))
                val title = "Media compatibility acceptance fixture"
                val closeDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var visible = false
                while (!visible && android.os.SystemClock.elapsedRealtime() < closeDeadline) {
                    visible = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)?.any { it.text?.toString() == title } == true
                    if (!visible) Thread.sleep(100)
                }
                assertTrue("Only close exact fixture", visible)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returnHost()
                observed.put("phaseCompleted", true).put("closingFixture", true); saveSummary()
            }
            phase(groups[0], "A-read-only", true, false)
            phase(groups[0], "A-write-allowed", true, true)
            phase(groups[1], "B-write-denied", true, false)
            phase(groups[0], "A-write-revoked", true, false)
            phase(groups[0], "A-write-without-read", false, true)
            phase(groups[0], "A-restored", true, true)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            val failures = mutableListOf<Throwable>()
            groups.toList().forEach { group -> runCatching {
                val deleted = operations.deleteGroup(group.id)
                check(deleted is DeleteGroupResult.Deleted && deleted.cleanupWarning == null) { "Owned group cleanup: ${group.id}" }
                groups.remove(group); persist()
            }.exceptionOrNull()?.let(failures::add) }
            media.toList().forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null); media.remove(uri); persist() }.exceptionOrNull()?.let(failures::add) }
            paths.toList().forEach { file -> runCatching {
                check(file.name.startsWith("apptwin-write-$nonce-"))
                check(!file.exists() || file.delete()) { "Owned file cleanup failed: ${file.name}" }
                paths.remove(file); persist()
            }.exceptionOrNull()?.let(failures::add) }
            runCatching {
                host?.let { activity -> if (!activity.isDestroyed) activity.getSystemService(ActivityManager::class.java).appTasks
                    .single { it.taskInfo.taskId == activity.taskId }.apply { moveToFront(); finishAndRemoveTask() } }
                instrumentation.waitForIdleSync(); scenario?.close()
            }.exceptionOrNull()?.let(failures::add)
            if (primary != null) failures.forEach { primary!!.addSuppressed(it) }
            else if (failures.isNotEmpty()) throw AssertionError("Owned cleanup incomplete groups=${groups.map { it.id }}, media=$media, paths=${paths.map { it.name }}").apply { failures.forEach(::addSuppressed) }
        }
    }
    private companion object { const val PACKAGE = "org.apptwin.fixture.mediacompat" }
}
