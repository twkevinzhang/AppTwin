package org.apptwin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundServiceStateTest {
    @Test fun successfulReadClearsPreviouslyLoadedReadErrorAndRestoresToggle() {
        val recovered = BackgroundServiceState(enabled = false, loaded = true, error = "read failed")
            .withLoadedSetting(true)
        assertEquals(null, recovered.error)
        assertEquals("已開啟", recovered.tileDescription)
        val requests = mutableListOf<Boolean>()
        recovered.performTileClick(requests::add) { error("recovered read must toggle") }
        assertEquals(listOf(false), requests)
    }

    @Test fun successfulReadKeepsPartialEnableFailureAndRetriesEnable() {
        val refreshed = BackgroundServiceState(
            enabled = true, loaded = true, error = "start failed", failedRequestedEnabled = true,
        ).withLoadedSetting(true)
        assertEquals("start failed", refreshed.error)
        assertEquals(true, refreshed.failedRequestedEnabled)
        val requests = mutableListOf<Boolean>()
        refreshed.performTileClick(requests::add) { error("read must not hide failed enable") }
        assertEquals(listOf(true), requests)
    }

    @Test fun tileRetriesPartialEnableRatherThanDisablingPersistedOnSetting() {
        val requests = mutableListOf<Boolean>()
        BackgroundServiceState(enabled = true, loaded = true, error = "start failed", failedRequestedEnabled = true)
            .performTileClick(requests::add) { error("must retry enable") }
        assertEquals(listOf(true), requests)
    }

    @Test fun tileRetriesFailedDisableRatherThanEnablingPersistedOffSetting() {
        val requests = mutableListOf<Boolean>()
        BackgroundServiceState(enabled = false, loaded = true, error = "failed", failedRequestedEnabled = false)
            .performTileClick(requests::add) { error("must retry disable") }
        assertEquals(listOf(false), requests)
    }

    @Test fun tileReadFailureRefreshesEvenWithPreviouslyLoadedSetting() {
        var refreshes = 0
        BackgroundServiceState(enabled = true, loaded = true, error = "read failed")
            .performTileClick({ error("read retry must not toggle") }) { refreshes++ }
        assertEquals(1, refreshes)
    }

    @Test fun firstReadFailureIsClickableAndDoesNotClaimSettingIsOff() {
        val state = BackgroundServiceState(error = "read failed")
        assertTrue(state.tileInteractive)
        assertEquals("讀取失敗，點擊重試", state.tileDescription)
        var refreshes = 0
        state.performTileClick({ error("unknown setting must not toggle") }) { refreshes++ }
        assertEquals(1, refreshes)
    }

    @Test fun busyTilePreventsDuplicateActionsIncludingRetry() {
        val state = BackgroundServiceState(loaded = true, busy = true, error = "failed", failedRequestedEnabled = true)
        assertFalse(state.tileInteractive)
        state.performTileClick({ error("duplicate mutation") }) { error("duplicate refresh") }
    }

    @Test fun normalTileTogglesActualLoadedSetting() {
        val requests = mutableListOf<Boolean>()
        listOf(false, true).forEach { enabled ->
            BackgroundServiceState(enabled = enabled, loaded = true)
                .performTileClick(requests::add) { error("known setting must toggle") }
        }
        assertEquals(listOf(true, false), requests)
    }

    @Test fun loadingTileDoesNotClaimUnknownSettingIsOff() {
        val state = BackgroundServiceState()
        assertFalse(state.tileInteractive)
        assertEquals("讀取中…", state.tileDescription)
        var refreshes = 0
        state.performTileClick({ error("unknown setting must not toggle") }) { refreshes++ }
        assertEquals(1, refreshes)
    }

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
