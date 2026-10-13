package org.apptwin.runtime

import android.app.ActivityManager
import android.app.Application
import android.app.KeyguardManager
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.DeleteGroupResult
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** External executor supplies only the exact owned URI already selected by LINE in another Space. */
@RunWith(AndroidJUnit4::class)
class SelectedFileUriIsolationE2eTest {
    @Test fun selectedFileGrantDoesNotLeakToAnotherSpace() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("selectedFileIsolationE2e") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.sendStatus(0, android.os.Bundle().apply { putInt("testHostProcessUid", android.os.Process.myUid()); putInt("testHostPackageUid", context.applicationInfo.uid) })
        var uri = Uri.parse(requireNotNull(arguments.getString("selectedFileOwnedUri")))
        require(uri.scheme == "content")
        val primaryUri = uri
        val alternateUri = arguments.getString("selectedFileAlternateOwnedUri")?.let(Uri::parse)?.also {
            require(it.scheme == "content")
        }
        val expected = Base64.decode(requireNotNull(arguments.getString("selectedFileOwnedBytesBase64")), Base64.NO_WRAP)
        require(expected.size in 1..4096)
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val nonce = UUID.randomUUID().toString()
        val owner = File(context.cacheDir, "selected-file-isolation-owned.json")
        check(!owner.exists() || JSONObject(owner.readText()).optString("groupId").isEmpty()) {
            "Previous selected-file isolation group requires exact cleanup"
        }
        var group: Group? = null
        fun persist() {
            val temporary = File(context.cacheDir, "selected-file-isolation-owned.tmp")
            temporary.writeText(JSONObject().put("nonce", nonce).put("groupId", group?.id ?: "")
                .put("user", group?.environmentBinding?.internalId ?: -1).toString())
            check(temporary.renameTo(owner))
        }
        persist()
        var scenario: ActivityScenario<MainActivity>? = null
        var host: MainActivity? = null
        var primary: Throwable? = null
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        fun ready(): Boolean {
            var ready = false
            instrumentation.runOnMainSync {
                val activity = requireNotNull(host)
                ready = activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                    activity.hasWindowFocus() && mainActivityLaunchHosts.current() === activity &&
                    power.isInteractive && !keyguard.isKeyguardLocked
            }
            return ready
        }
        fun returnHost() {
            val activity = requireNotNull(host)
            activity.getSystemService(ActivityManager::class.java).appTasks.single {
                it.taskInfo.taskId == activity.taskId
            }.moveToFront()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!ready() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Owned host resumed", ready())
        }
        try {
            assertTrue("Awake/unlocked prerequisite", power.isInteractive && !keyguard.isKeyguardLocked)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { host = it; it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            returnHost()
            if (arguments.getString("selectedFileWaitForSelection") == "true") {
                // GET_CONTENT grants are transient. The external driver selects the owned file
                // after instrumentation starts; this test never manufactures or persists a grant.
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("selectedFileIsolationStage", "waitingForOwnedSelection")
                })
                fun hasHostReadGrant(candidate: Uri) = context.checkUriPermission(candidate, android.os.Process.myPid(),
                    android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                fun grantedOwnedUri(): Uri? = when {
                    hasHostReadGrant(primaryUri) -> primaryUri
                    alternateUri != null && hasHostReadGrant(alternateUri) -> alternateUri
                    else -> null
                }
                val selectionDeadline = android.os.SystemClock.elapsedRealtime() + 180_000
                var observedUri = grantedOwnedUri()
                while (observedUri == null && android.os.SystemClock.elapsedRealtime() < selectionDeadline) {
                    Thread.sleep(250)
                    observedUri = grantedOwnedUri()
                }
                assertTrue("Fresh owned host read grant required within 180 seconds", observedUri != null)
                uri = requireNotNull(observedUri)
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("selectedFileIsolationStage", "ownedHostGrantObserved")
                    putString("selectedFileIsolationCandidate", if (uri == primaryUri) "primary" else "alternate")
                })
            }
            // Actual host query/open are prerequisites; guest denial must not be host grant loss.
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue("Owned host metadata available", it.moveToFirst())
                assertEquals(NAME, it.getString(0))
            }
            assertTrue("Owned host bytes available", context.contentResolver.openInputStream(uri)!!.use {
                it.readBytes().contentEquals(expected)
            })
            returnHost()
            operations.reconcileGroups()
            group = operations.createGroup("選取檔案隔離驗收-$nonce"); persist()
            val id = requireNotNull(group).id
            operations.addAppToGroup(id, PACKAGE)
            group = requireNotNull(FileGroupStore(context).find(id)); persist()
            val owned = requireNotNull(group)
            val user = requireNotNull(owned.environmentBinding).internalId
            val directory = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files").apply { mkdirs() }
            val output = File(directory, "media-compat-result.json").apply { delete() }
            File(directory, "media-compat-config.json").writeText(JSONObject()
                .put("mode", "selected-file-isolation").put("phase", nonce).put("name", NAME)
                .put("uri", uri.toString()).toString())
            returnHost()
            val launch = VirtualRuntimeController(context).installAndLaunch(owned,
                owned.apps.single { it.packageName == PACKAGE })
            assertTrue("Strict guest launch", launch is RuntimeLaunchResult.Started)
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            var result: JSONObject? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                result = runCatching { JSONObject(output.readText()) }.getOrNull()?.takeIf { it.optString("phase") == nonce }
                if (result != null) break
                Thread.sleep(100)
            }
            val observed = requireNotNull(result) { "Owned isolation fixture result missing" }
            File(context.cacheDir, "selected-file-isolation-summary.json").writeText(JSONObject()
                .put("hostReadVerified", true).put("queryRejected", observed.getBoolean("queryRejected"))
                .put("openRejected", observed.getBoolean("openRejected"))
                .put("queryException", observed.getString("queryException"))
                .put("openException", observed.getString("openException")).toString())
            for (operation in listOf("query", "open")) {
                assertTrue("Other Space $operation must be denied", observed.getBoolean("${operation}Rejected"))
                assertEquals("SecurityException", observed.getString("${operation}Exception"))
            }
            // Prove the grant remains usable by the host after the negative guest probes.
            assertTrue("Host owned grant remains available", context.contentResolver.openInputStream(uri)!!.use {
                it.readBytes().contentEquals(expected)
            })
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            val failures = mutableListOf<Throwable>()
            group?.let { owned -> runCatching {
                val deleted = operations.deleteGroup(owned.id)
                check(deleted is DeleteGroupResult.Deleted && deleted.cleanupWarning == null) { "Owned group cleanup failed" }
                group = null; persist()
            }.exceptionOrNull()?.let(failures::add) }
            runCatching {
                host?.let { activity -> if (!activity.isDestroyed) activity.getSystemService(ActivityManager::class.java)
                    .appTasks.single { it.taskInfo.taskId == activity.taskId }.apply { moveToFront(); finishAndRemoveTask() } }
                instrumentation.waitForIdleSync(); scenario?.close()
            }.exceptionOrNull()?.let(failures::add)
            if (primary != null) failures.forEach { primary!!.addSuppressed(it) }
            else if (failures.isNotEmpty()) throw AssertionError("Owned isolation cleanup incomplete").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }
    private companion object {
        const val PACKAGE = "org.apptwin.fixture.fileisolation"
        const val NAME = "apptwin-file-probe-20261013.txt"
    }
}
