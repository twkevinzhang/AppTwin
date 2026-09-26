package org.apptwin.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundServiceStateTest {
    @Test fun retryUsesFailedOffRequestEvenWhenPersistedStateIsStillOn() {
        val failed = BackgroundServiceState(
            enabled = true, loaded = true, error = "failed", failedRequestedEnabled = false,
        )
        // A durable refresh must not replace the user intent that failed to persist.
        val refreshed = failed.copy(enabled = true, loaded = true)
        val requests = mutableListOf<Boolean>()
        var refreshes = 0
        refreshed.retry(requests::add) { refreshes++ }
        assertEquals(listOf(false), requests)
        assertEquals(0, refreshes)
    }

    @Test fun retryUsesFailedOnRequestEvenWhenPersistedStateIsStillOff() {
        val requests = mutableListOf<Boolean>()
        BackgroundServiceState(
            enabled = false, loaded = true, failedRequestedEnabled = true,
        ).retry(requests::add) { error("must retry the user request") }
        assertEquals(listOf(true), requests)
    }

    @Test fun readFailureRetryOnlyRefreshesAndDoesNotChangePolicy() {
        var refreshes = 0
        BackgroundServiceState(error = "read failed").retry(
            requestChange = { error("read retry must not change policy") },
            refresh = { refreshes++ },
        )
        assertEquals(1, refreshes)
    }
}
