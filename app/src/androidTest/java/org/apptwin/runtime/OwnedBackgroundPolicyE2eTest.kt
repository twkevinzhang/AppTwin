package org.apptwin.runtime

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.env.BackgroundExecutionSettings
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.os.VUserManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in global policy phase. Refuses to stop any guest outside this test's exact owned fixture. */
@RunWith(AndroidJUnit4::class)
class OwnedBackgroundPolicyE2eTest {
    @Test
    fun explicitOffRejectsRetryAndQueuedEnableCannotOverrideStop() = runBlocking {
        assertEquals("Explicit global-policy phase required", "1",
            InstrumentationRegistry.getArguments().getString("ownedBackgroundPolicyE2e"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AppTwinApplication
        val repository = app.mainContainer.backgroundServiceRepository
        val operations = app.mainContainer.operationGraph.mainOperations()
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        val prior = BackgroundExecutionSettings.isEnabled(context)
        File(context.cacheDir, "owned-policy-prior.txt").writeText("enabled=$prior")
        val owned = mutableListOf<String>()
        var primary: Throwable? = null
        var changed = false
        try {
            assertOnlyOwnedFixtureRunning(emptySet())
            assertNoRawStubProcesses()
            // Cold phase: Off does not need a visible host. Prevent host startup recovery from
            // starting baseline guests before the first global mutation is safely observed.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertOnlyOwnedFixtureRunning(emptySet())
                assertNoRawStubProcesses()
                changed = true
                repository.setEnabled(false)
            }
            await("Cold Off terminal readback") { repository.state.value.loaded && !repository.state.value.busy &&
                !repository.state.value.enabled && !BackgroundExecutionSettings.isEnabled(context) }
            val group = operations.createGroup("Owned explicit policy stop").also { owned += it.id }
            val user = requireNotNull(group.environmentBinding).internalId
            assertFalse("Disabled cold policy rejects owned retry", operations.retrySpaceBackground(group.id))
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                host.awaitRealForegroundHost()
                assertOnlyOwnedFixtureRunning(setOf(user))
                assertFalse("Disabled visible policy rejects exact owned retry", operations.retrySpaceBackground(group.id))
                host.onActivity { repository.setEnabled(true); repository.setEnabled(false) }
                await("Queued enable cannot override explicit Stop") { !repository.state.value.busy &&
                    !repository.state.value.enabled && !BackgroundExecutionSettings.isEnabled(context) }
                assertFalse(operations.retrySpaceBackground(group.id))
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            if (changed) {
                try {
                    // Also runs if the acceptance host failed to launch. Restoration gets its own
                    // real visible host and fails explicitly while retaining the prior-policy record.
                    ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                        host.awaitRealForegroundHost()
                        host.onActivity { repository.setEnabled(prior) }
                        await("Restore prior durable policy") { repository.state.value.loaded && !repository.state.value.busy &&
                            repository.state.value.enabled == prior && repository.state.value.error == null &&
                            BackgroundExecutionSettings.isEnabled(context) == prior }
                        changed = false
                    }
                } catch (restore: Throwable) { errors += restore }
            }
            if (changed) errors += AssertionError("Prior policy restoration incomplete; external harness must recover from owned-policy-prior.txt")
            owned.asReversed().forEach { id ->
                try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error }
            }
            try { assertEquals(baseline, tuples(store.loadSnapshot().groups)); assertEquals(prior, BackgroundExecutionSettings.isEnabled(context)) }
            catch (error: Throwable) { errors += error }
            if (primary != null) errors.forEach(primary!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }
    private fun assertNoRawStubProcesses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val allowedPids = com.lody.virtual.server.am.ColdPolicyProcessGuard.verifyRawAuthority()
        val hostUid = android.os.Process.myUid()
        val mainPid = android.os.Process.myPid()
        val descriptor = instrumentation.uiAutomation.executeShellCommand("ps -A -o UID,PID")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "owned-policy-preflight").apply { isDaemon = true }
        }
        val future = executor.submit<Boolean> {
            try {
                java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                    var forbidden = false
                    var validHeader = false
                    var hostPresent = false
                    var lineCount = 0
                    reader.forEachLine { line ->
                        lineCount++
                        check(lineCount <= 3000) { "Invalid oversized process inventory" }
                        val fields = line.trim().split(Regex("\\s+"))
                        if (lineCount == 1) {
                            validHeader = fields == listOf("UID", "PID")
                        } else {
                            check(fields.size == 2) { "Invalid numeric process inventory" }
                            val uid = fields[0].toIntOrNull() ?: error("Invalid numeric process UID")
                            val pid = fields[1].toIntOrNull() ?: error("Invalid numeric process PID")
                            check(uid >= 0 && pid > 0) { "Invalid process identity values" }
                            if (uid == hostUid && pid == mainPid) hostPresent = true
                            if (uid == hostUid && pid !in allowedPids) forbidden = true
                        }
                    }
                    check(validHeader && hostPresent) { "Invalid or incomplete process inventory; mutation refused" }
                    !forbidden
                }
            } finally { runCatching { descriptor.close() } }
        }
        try { check(future.get(3, java.util.concurrent.TimeUnit.SECONDS)) { "Guest/stub process present; global mutation refused" } }
        finally { future.cancel(true); runCatching { descriptor.close() }; executor.shutdownNow() }
    }
    private fun assertOnlyOwnedFixtureRunning(ownedUsers: Set<Int>) {
        VUserManager.get().users.forEach { user ->
            VirtualCore.get().getInstalledAppsAsUser(user.id, 0).forEach { installed ->
                if (VActivityManager.get().isAppRunning(installed.packageName, user.id)) {
                    assertTrue("Unowned guest workload present; global mutation refused",
                        user.id in ownedUsers && installed.packageName == "org.apptwin.fixture")
                }
            }
        }
    }
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",") }" }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        error("Timed out: $description")
    }
}
