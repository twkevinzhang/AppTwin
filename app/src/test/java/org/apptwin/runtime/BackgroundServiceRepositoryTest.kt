package org.apptwin.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundServiceRepositoryTest {
    private class Backend : BackgroundServiceBackend {
        var enabled = false
        var failRead = false
        var failWrite = false
        var failStart = false
        var ignoreWrite = false
        var cancelWrite = false
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Boolean>()
        var starts = 0
        var notices = 0
        override suspend fun readEnabled(): Boolean { check(!failRead); return enabled }
        override suspend fun writeEnabled(enabled: Boolean) {
            writes += enabled
            writeGate?.await()
            if (cancelWrite) throw kotlinx.coroutines.CancellationException("cancelled")
            check(!failWrite)
            if (!ignoreWrite) this.enabled = enabled
        }
        override suspend fun startFromVisibleHost() { starts++; check(!failStart) }
        override fun notifyTile() { notices++ }
    }

    @Test fun readIsAuthoritativeAndNeverStartsWork() = runTest {
        val backend = Backend().apply { enabled = true }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.refresh(); runCurrent()
        assertTrue(repository.state.value.loaded)
        assertTrue(repository.state.value.enabled)
        assertEquals(0, backend.starts)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun failedReadRecoversWithoutMutation() = runTest {
        val backend = Backend().apply { failRead = true }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.refresh(); runCurrent()
        assertFalse(repository.state.value.loaded)
        assertTrue(repository.state.value.error != null)
        backend.failRead = false
        repository.refresh(); runCurrent()
        assertEquals(null, repository.state.value.error)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun partialStartFailurePreservesDurableValueAndRequestedRetry() = runTest {
        val backend = Backend().apply { failStart = true }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(true); runCurrent()
        assertTrue(repository.state.value.enabled)
        assertEquals(true, repository.state.value.failedRequestedEnabled)
        repository.refresh(); runCurrent()
        assertEquals(true, repository.state.value.failedRequestedEnabled)
        backend.failStart = false
        repository.state.value.retry(repository::setEnabled, repository::refresh); runCurrent()
        assertEquals(listOf(true, true), backend.writes)
        assertEquals(null, repository.state.value.error)
        assertFalse(repository.state.value.busy)
    }

    @Test fun readbackMismatchDoesNotClaimSuccessfulToggle() = runTest {
        val backend = Backend().apply { ignoreWrite = true }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(true); runCurrent()
        assertFalse(repository.state.value.enabled)
        assertEquals(true, repository.state.value.failedRequestedEnabled)
        assertEquals(0, backend.starts)
        assertFalse(repository.state.value.busy)
    }

    @Test fun stopInvalidatesQueuedEnableAndCannotBeDroppedByBusyGuard() = runTest {
        val backend = Backend()
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(true)
        repository.setEnabled(false)
        runCurrent()
        assertEquals(listOf(false), backend.writes)
        assertEquals(0, backend.starts)
        assertFalse(repository.state.value.busy)
        assertFalse(repository.state.value.enabled)
    }

    @Test fun stopDuringEnableIoPreventsStartAndCommitsOffAfterWrite() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { writeGate = gate }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(true); runCurrent()
        repository.setEnabled(false)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(true, false), backend.writes)
        assertEquals(0, backend.starts)
        assertFalse(backend.enabled)
        assertFalse(repository.state.value.busy)
    }

    @Test fun observerCancellationDoesNotOwnMutationLifetime() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { writeGate = gate }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        val observer = backgroundScope.launch { repository.state.collect {} }
        repository.setEnabled(true); runCurrent()
        observer.cancel()
        gate.complete(Unit); runCurrent()
        assertTrue(backend.enabled)
        assertEquals(1, backend.starts)
        assertFalse(repository.state.value.busy)
    }
    @Test fun laterExplicitEnableAfterCompletedStopCanStartAgain() = runTest {
        val backend = Backend()
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(false); runCurrent()
        repository.setEnabled(true); runCurrent()
        assertEquals(listOf(false, true), backend.writes)
        assertTrue(repository.state.value.enabled)
        assertEquals(1, backend.starts)
    }

    @Test fun queuedRefreshKeepsBusyUntilPendingStopCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backend = Backend().apply { writeGate = gate }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(true); runCurrent()
        repository.refresh()
        repository.setEnabled(false)
        runCurrent()
        assertTrue(repository.state.value.busy)
        gate.complete(Unit); runCurrent()
        assertFalse(repository.state.value.busy)
        assertFalse(repository.state.value.enabled)
    }

    @Test fun cancellationReleasesBusyWithoutPublishingBusinessError() = runTest {
        val backend = Backend().apply { cancelWrite = true }
        val repository = ApplicationBackgroundServiceRepository(backgroundScope, StandardTestDispatcher(testScheduler), backend)
        repository.setEnabled(true); runCurrent()
        assertFalse(repository.state.value.busy)
        assertEquals(null, repository.state.value.error)
        assertEquals(null, repository.state.value.failedRequestedEnabled)
        assertEquals(0, backend.starts)
        backend.cancelWrite = false
        repository.setEnabled(false); runCurrent()
        assertFalse(repository.state.value.busy)
        assertTrue(repository.state.value.loaded)
    }

}
