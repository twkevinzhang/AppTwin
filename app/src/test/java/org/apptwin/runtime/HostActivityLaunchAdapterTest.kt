package org.apptwin.runtime

import android.content.Intent
import com.lody.virtual.remote.PreparedActivityLaunch
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
            assertEquals(listOf("prepare-2", "start", "ack"), events)
        }
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
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertEquals("Expected Group activity did not resume", result.failureReason)
        assertEquals(81, movedTask)
        assertEquals("user-2-reuse", cancelled)
    }

    @Test
    fun `launch without resumed AppTwin host fails before prepare`() {
        var prepared = false
        val adapter = HostActivityLaunchAdapter<String>(
            prepareActivity = { _, _, _ ->
                prepared = true
                PreparedActivityLaunch.reused(1, "unused")
            },
            resumedHost = { null },
            startActivity = { _, _ -> error("must not start") },
            moveTaskToFront = { _, _ -> error("must not move") },
            dispatchToMain = ::runImmediately,
            isMainThread = { false },
            awaitAcknowledgement = { _, _ -> error("must not await") },
            cancelAcknowledgement = { error("nothing was prepared") },
        )

        val result = adapter.launch(Intent("test.request"), "com.example.guest", 2)

        assertFalse(result.isSuccess)
        assertFalse(prepared)
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
