package org.apptwin.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceHealthObservationTest {
    @Test
    fun `cancelling UI observation stops polling and invalidates exactly once`() = runTest {
        var reads = 0
        var invalidations = 0
        val observer = launch { observeSpaceHealth({ reads++ }, { invalidations++ }, 100L) }
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(2, reads)
        observer.cancelAndJoin()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(1, invalidations)
        // Starting a new observer is independent of the completed UI job.
        val resumed = launch { observeSpaceHealth({ reads++ }, { invalidations++ }, 100L) }
        runCurrent()
        assertEquals(3, reads)
        resumed.cancelAndJoin()
        assertEquals(2, invalidations)
    }
}
