package org.apptwin.runtime

import android.content.Intent
import com.lody.virtual.remote.PreparedActivityLaunch
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostActivityLaunchAdapterTest {
    @Test
    fun `visible daemon request captures epoch before starting service`() {
        val source = File(
            "src/main/java/org/apptwin/runtime/DaemonWorkloadAuthorization.kt",
        ).readText()
        val capture = source.indexOf("daemonWorkloadGateReopenEpoch")
        val startup = source.indexOf("DaemonService.startup(")

        assertTrue(capture >= 0)
        assertTrue(startup > capture)
    }

    @Test
    fun `new Group launch starts prepared stub from resumed host and waits for exact ACK`() {
        TestMainThread().use { main ->
            val request = Intent("test.request")
            val preparedIntent = Intent("test.prepared")
            var started: Intent? = null
            val events = mutableListOf<String>()
            val adapter = HostActivityLaunchAdapter(
                prepareActivity = { intent, packageName, userId ->
                    events += "prepare-$userId"
                    assertEquals("com.example.guest", packageName)
                    assertNotSame(request, intent)
                    PreparedActivityLaunch.hostStartRequired(preparedIntent, "user-2-launch")
                },
                resumedHost = { "visible-host" },
                startActivity = { host, intent ->
                    assertTrue(main.isMainThread())
                    assertEquals("visible-host", host)
                    events += "start"
                    started = intent
                },
                moveTaskToFront = { _, _ -> error("new launch must not reuse a task") },
                observeDaemonReopenEpoch = {
                    events += "observe"
                    41L
                },
                refreshDaemonFromVisibleHost = { host ->
                    assertTrue(main.isMainThread())
                    assertEquals("visible-host", host)
                    events += "daemon"
                },
                awaitDaemonReady = { observedReopenEpoch, timeoutMs ->
                    assertEquals(41L, observedReopenEpoch)
                    assertEquals(5_000L, timeoutMs)
                    events += "daemon-ready"
                    true
                },
                dispatchToMain = main::dispatch,
                isMainThread = main::isMainThread,
                awaitAcknowledgement = { launchId, _ ->
                    events += "ack"
                    launchId == "user-2-launch"
                },
                cancelAcknowledgement = { error("successful launch must not cancel") },
            )

            val result = adapter.launch(request, "com.example.guest", 2)

            assertTrue(result.isSuccess)
            assertNotSame(preparedIntent, started)
            assertEquals(
                listOf("observe", "daemon", "daemon-ready", "prepare-2", "start", "ack"),
                events,
            )
        }
    }

    @Test
    fun `closed daemon gate fails before preparing guest workload`() {
        var prepared = false
        val adapter = HostActivityLaunchAdapter(
            prepareActivity = { _, _, _ ->
                prepared = true
                error("closed gate must prevent preparation")
            },
            resumedHost = { "visible-host" },
            startActivity = { _, _ -> error("closed gate must prevent launch") },
            moveTaskToFront = { _, _ -> error("closed gate must prevent reuse") },
            observeDaemonReopenEpoch = { 7L },
            refreshDaemonFromVisibleHost = {},
            awaitDaemonReady = { observedReopenEpoch, _ ->
                assertEquals(7L, observedReopenEpoch)
                false
            },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ -> false },
            cancelAcknowledgement = {},
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertFalse(prepared)
    }

    @Test
    fun `reuse moves only prepared task and succeeds after expected Group resumes`() {
        var movedTask = -1
        val adapter = HostActivityLaunchAdapter(
            prepareActivity = { _, _, userId ->
                assertEquals(2, userId)
                PreparedActivityLaunch.reused(73, "user-2-reuse")
            },
            resumedHost = { "visible-host" },
            startActivity = { _, _ -> error("reuse must not start a new activity") },
            moveTaskToFront = { _, taskId -> movedTask = taskId },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { launchId, _ -> launchId == "user-2-reuse" },
            cancelAcknowledgement = { error("successful reuse must not cancel") },
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertTrue(result.isReused)
        assertEquals(73, movedTask)
    }

    @Test
    fun `exact guest task confirms launch when startup transition misses ACK`() {
        val events = mutableListOf<String>()
        var nowMs = 0L
        var probeCount = 0
        var cancelled: String? = null
        val adapter = HostActivityLaunchAdapter(
            prepareActivity = { _, _, _ ->
                PreparedActivityLaunch.hostStartRequired(
                    Intent("test.prepared"),
                    "user-2-launch",
                )
            },
            resumedHost = { "visible-host" },
            startActivity = { _, _ -> events += "start" },
            moveTaskToFront = { _, _ -> error("new launch must not reuse a task") },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ ->
                events += "ack-missed"
                false
            },
            hasExpectedGuestActivity = { packageName, environmentId, prepared ->
                probeCount += 1
                events += "task-probe-$probeCount"
                packageName == "com.example.guest" &&
                    environmentId == 2 &&
                    prepared.launchId == "user-2-launch" &&
                    probeCount == 3
            },
            cancelAcknowledgement = { cancelled = it },
            acknowledgementTimeoutMs = 10L,
            activityConfirmationTimeoutMs = 100L,
            activityConfirmationPollMs = 25L,
            monotonicTimeMs = { nowMs },
            pause = { nowMs += it },
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertTrue(result.isSuccess)
        assertEquals(
            listOf("start", "ack-missed", "task-probe-1", "task-probe-2", "task-probe-3"),
            events,
        )
        assertEquals("user-2-launch", cancelled)
    }

    @Test
    fun `environment two launch rejects stale environment one task without exact ACK`() {
        var movedTask = -1
        var cancelled: String? = null
        val resumedEnvironmentId = 1
        val adapter = HostActivityLaunchAdapter(
            prepareActivity = { _, _, userId ->
                assertEquals(2, userId)
                PreparedActivityLaunch.reused(81, "user-2-reuse")
            },
            resumedHost = { "visible-host" },
            startActivity = { _, _ -> error("reuse must not start a new activity") },
            moveTaskToFront = { _, taskId -> movedTask = taskId },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ -> resumedEnvironmentId == 2 },
            cancelAcknowledgement = { cancelled = it },
            acknowledgementTimeoutMs = 10L,
            activityConfirmationTimeoutMs = 0L,
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertEquals("Expected Group activity did not resume", result.failureReason)
        assertEquals(81, movedTask)
        assertEquals("user-2-reuse", cancelled)
    }

    @Test
    fun `guest task probe failure remains a launch failure and cleans up ACK`() {
        var cancelled: String? = null
        val adapter = HostActivityLaunchAdapter(
            prepareActivity = { _, _, _ ->
                PreparedActivityLaunch.hostStartRequired(
                    Intent("test.prepared"),
                    "user-2-launch",
                )
            },
            resumedHost = { "visible-host" },
            startActivity = { _, _ -> Unit },
            moveTaskToFront = { _, _ -> error("new launch must not reuse a task") },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ -> false },
            hasExpectedGuestActivity = { _, _, _ -> error("task service unavailable") },
            cancelAcknowledgement = { cancelled = it },
            acknowledgementTimeoutMs = 10L,
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertEquals("Expected Group activity did not resume", result.failureReason)
        assertEquals("user-2-launch", cancelled)
    }

    @Test
    fun `launch without resumed AppTwin host fails before prepare`() {
        var prepared = false
        var daemonRefreshed = false
        val adapter = HostActivityLaunchAdapter<String>(
            prepareActivity = { _, _, _ ->
                prepared = true
                PreparedActivityLaunch.reused(1, "unused")
            },
            resumedHost = { null },
            startActivity = { _, _ -> error("must not start") },
            moveTaskToFront = { _, _ -> error("must not move") },
            refreshDaemonFromVisibleHost = {
                daemonRefreshed = true
            },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ -> error("must not await") },
            cancelAcknowledgement = { error("nothing was prepared") },
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertFalse(prepared)
        assertFalse(daemonRefreshed)
    }

    @Test
    fun `host paused during prepare refreshes daemon first but cannot start guest`() {
        var resumed = true
        var daemonRefreshed = false
        var started = false
        val adapter = HostActivityLaunchAdapter(
            prepareActivity = { _, _, _ ->
                resumed = false
                PreparedActivityLaunch.hostStartRequired(
                    Intent("test.prepared"),
                    "user-2-launch",
                )
            },
            resumedHost = { if (resumed) "visible-host" else null },
            startActivity = { _, _ -> started = true },
            moveTaskToFront = { _, _ -> error("must not move") },
            refreshDaemonFromVisibleHost = {
                daemonRefreshed = true
            },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ -> error("must not await") },
            cancelAcknowledgement = {},
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertEquals("AppTwin activity is no longer resumed", result.failureReason)
        assertTrue(daemonRefreshed)
        assertFalse(started)
    }

    @Test
    fun `guest task confirmation requires exact launch environment package and task`() {
        assertTrue(
            matchesExpectedGuestActivity(
                expectedPackage = "com.example.guest",
                expectedEnvironmentId = 2,
                taskEnvironmentId = 2,
                topActivityPackage = "com.example.guest",
                preparedTaskId = -1,
                preparedLaunchId = "launch-new",
                taskId = 73,
                taskPreparedLaunchId = "launch-new",
            ),
        )
        assertFalse(
            matchesExpectedGuestActivity(
                expectedPackage = "com.example.guest",
                expectedEnvironmentId = 2,
                taskEnvironmentId = 1,
                topActivityPackage = "com.example.guest",
                preparedTaskId = -1,
                preparedLaunchId = "launch-new",
                taskId = 73,
                taskPreparedLaunchId = "launch-new",
            ),
        )
        assertFalse(
            matchesExpectedGuestActivity(
                expectedPackage = "com.example.guest",
                expectedEnvironmentId = 2,
                taskEnvironmentId = 2,
                topActivityPackage = "com.example.other",
                preparedTaskId = -1,
                preparedLaunchId = "launch-new",
                taskId = 73,
                taskPreparedLaunchId = "launch-new",
            ),
        )
        assertFalse(
            matchesExpectedGuestActivity(
                expectedPackage = "com.example.guest",
                expectedEnvironmentId = 2,
                taskEnvironmentId = 2,
                topActivityPackage = "com.example.guest",
                preparedTaskId = -1,
                preparedLaunchId = "launch-new",
                taskId = 73,
                taskPreparedLaunchId = "stale-launch",
            ),
        )
        assertTrue(
            matchesExpectedGuestActivity(
                expectedPackage = "com.example.guest",
                expectedEnvironmentId = 2,
                taskEnvironmentId = 2,
                topActivityPackage = "com.example.guest",
                preparedTaskId = 73,
                preparedLaunchId = "launch-reuse",
                taskId = 73,
                taskPreparedLaunchId = null,
            ),
        )
        assertFalse(
            matchesExpectedGuestActivity(
                expectedPackage = "com.example.guest",
                expectedEnvironmentId = 2,
                taskEnvironmentId = 2,
                topActivityPackage = "com.example.guest",
                preparedTaskId = 73,
                preparedLaunchId = "launch-reuse",
                taskId = 74,
                taskPreparedLaunchId = null,
            ),
        )
    }

    @Test
    fun `resumed host registry exposes only the exact current instance`() {
        val registry = ResumedHostRegistry<Any>()
        val first = Any()
        val second = Any()

        registry.onResumed(first)
        registry.onPaused(second)
        assertTrue(registry.current() === first)
        registry.onPaused(first)
        assertEquals(null, registry.current())
    }

    private class TestMainThread : AutoCloseable {
        @Volatile
        private var thread: Thread? = null
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "host-launch-test-main").also { thread = it }
        }

        fun dispatch(runnable: Runnable): Boolean {
            executor.execute(runnable)
            return true
        }

        fun isMainThread(): Boolean = Thread.currentThread() === thread

        fun <T> call(block: () -> T): T =
            executor.submit(Callable(block)).get(1, TimeUnit.SECONDS)

        override fun close() {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun runImmediately(runnable: Runnable): Boolean {
            runnable.run()
            return true
        }
    }
}
