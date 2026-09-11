package org.apptwin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutPinStateTest {
    @Test
    fun `request remains pending until its bounded lease expires`() {
        var now = 1_000L
        val state = state(now = { now }, requestTtl = 100L, confirmedGrace = 20L)

        state.markRequested("space:line")

        assertEquals(setOf("space:line"), state.pendingIds())
        now = 1_100L
        assertTrue(state.pendingIds().isEmpty())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `confirmation replaces the request lease with a short placement grace period`() {
        var now = 2_000L
        val state = state(now = { now }, requestTtl = 500L, confirmedGrace = 40L)
        val nonce = state.markRequested("space:line")

        now = 2_010L
        assertTrue(state.markConfirmed("space:line", nonce))

        now = 2_049L
        assertEquals(setOf("space:line"), state.pendingIds())
        now = 2_050L
        assertTrue(state.pendingIds().isEmpty())
    }

    @Test
    fun `rejected platform request can clear its pending lease immediately`() {
        val state = state(now = { 3_000L }, requestTtl = 500L, confirmedGrace = 40L)
        val nonce = state.markRequested("space:line")

        state.cancel("space:line", nonce)

        assertTrue(state.pendingIds().isEmpty())
    }

    @Test
    fun `confirmation requires the active request nonce`() {
        val state = state(now = { 4_000L }, requestTtl = 500L, confirmedGrace = 40L)
        val nonce = state.markRequested("space:line")

        assertTrue(!state.markConfirmed("space:line", "forged"))
        assertEquals(setOf("space:line"), state.pendingIds())
        assertTrue(state.markConfirmed("space:line", nonce))
        assertTrue(!state.markConfirmed("unknown", nonce))
    }

    @Test
    fun `stale cancellation cannot remove a newer request`() {
        var nextNonce = 0
        val state = state(
            now = { 5_000L },
            requestTtl = 500L,
            confirmedGrace = 40L,
            nonceFactory = { "nonce-${++nextNonce}" },
        )
        val staleNonce = state.markRequested("space:line")
        val currentNonce = state.markRequested("space:line")

        state.cancel("space:line", staleNonce)

        assertEquals(setOf("space:line"), state.pendingIds())
        assertTrue(state.markConfirmed("space:line", currentNonce))
    }

    @Test
    fun `clock rollback invalidates rather than extending a lease`() {
        var now = 6_000L
        val state = state(now = { now }, requestTtl = 500L, confirmedGrace = 40L)
        state.markRequested("space:line")

        now = 5_999L

        assertTrue(state.pendingIds().isEmpty())
    }

    @Test
    fun `pending requests remain bounded and refreshing one does not evict another`() {
        var nextNonce = 0
        val state = state(
            now = { 7_000L },
            requestTtl = 500L,
            confirmedGrace = 40L,
            nonceFactory = { "nonce-${++nextNonce}" },
            maxPendingRequests = 2,
        )
        state.markRequested("space:line")
        state.markRequested("space:firefox")

        state.markRequested("space:line")

        assertEquals(setOf("space:line", "space:firefox"), state.pendingIds())
        state.markRequested("space:third")
        assertEquals(2, state.pendingIds().size)
        assertTrue("space:third" in state.pendingIds())
    }

    private val store = MemoryShortcutPinLeaseStore()

    private fun state(
        now: () -> Long,
        requestTtl: Long,
        confirmedGrace: Long,
        nonceFactory: () -> String = { "test-nonce" },
        maxPendingRequests: Int = 32,
    ) = ShortcutPinState(
        store = store,
        nowEpochMillis = now,
        requestTtlMillis = requestTtl,
        confirmedGraceMillis = confirmedGrace,
        nonceFactory = nonceFactory,
        maxPendingRequests = maxPendingRequests,
    )

    private class MemoryShortcutPinLeaseStore : ShortcutPinLeaseStore {
        private val values = linkedMapOf<String, ShortcutPinLease>()

        override fun entries(): Map<String, ShortcutPinLease> = values.toMap()

        override fun put(shortcutId: String, lease: ShortcutPinLease) {
            values[shortcutId] = lease
        }

        override fun remove(shortcutIds: Set<String>) {
            shortcutIds.forEach(values::remove)
        }
    }
}
