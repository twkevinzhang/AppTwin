package org.apptwin.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.SpaceBackgroundHealth
import org.apptwin.SpaceBackgroundStatus
import org.apptwin.runtime.BackgroundServiceState
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceBackgroundCardUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun globalOffDisablesRetryEvenWhenPreviousHealthFailed() {
        var retries = 0
        compose.setContent { AppTwinTheme { SpaceBackgroundCard(BackgroundServiceState(enabled = false, loaded = true),
            SpaceBackgroundHealth("space-2", microg = SpaceBackgroundStatus.FAILED), false, { retries++ }) } }
        compose.onNodeWithTag("space-background-summary").assertTextEquals("已停用")
        compose.onNodeWithTag("space-background-retry").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, retries) }
    }
    @Test fun connectedTcpWithoutVerifiedSessionDoesNotShowNormal() {
        compose.setContent { AppTwinTheme { SpaceBackgroundCard(BackgroundServiceState(enabled = true, loaded = true),
            SpaceBackgroundHealth("space-2", SpaceBackgroundStatus.SUCCESS, SpaceBackgroundStatus.SUCCESS, false), false, {}) } }
        compose.onNodeWithTag("space-background-summary").assertTextEquals("尚未確認")
        compose.onNodeWithText("TCP 已連線，推播登入尚未確認。").assertIsDisplayed()
        compose.onNodeWithTag("space-background-retry").assertIsEnabled()
    }
    @Test fun disconnectedTcpOffersExactRetryAndBusyPreventsDuplicates() {
        var retries = 0
        val retrying = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { AppTwinTheme { SpaceBackgroundCard(BackgroundServiceState(enabled = true, loaded = true),
            SpaceBackgroundHealth("space-2", SpaceBackgroundStatus.SUCCESS, SpaceBackgroundStatus.FAILED), retrying.value,
            { retries++; retrying.value = true }) } }
        compose.onNodeWithTag("space-background-retry").assertIsEnabled().performClick()
        compose.onNodeWithTag("space-background-retry").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun microgStartupFailureHasItsOwnRetryStory() {
        compose.setContent { AppTwinTheme { SpaceBackgroundCard(BackgroundServiceState(enabled = true, loaded = true),
            SpaceBackgroundHealth("space-2", SpaceBackgroundStatus.FAILED, SpaceBackgroundStatus.UNKNOWN), false, {}) } }
        compose.onNodeWithTag("space-background-microg").assertTextEquals("啟動失敗")
        compose.onNodeWithTag("space-background-retry").assertTextEquals("重新啟動").assertIsEnabled()
    }
    @Test fun pendingTcpConnectionUsesConnectionLabelAndNoDuplicateButton() {
        compose.setContent { AppTwinTheme { SpaceBackgroundCard(BackgroundServiceState(enabled = true, loaded = true),
            SpaceBackgroundHealth("space-2", SpaceBackgroundStatus.SUCCESS, SpaceBackgroundStatus.STARTING), false, {}) } }
        compose.onNodeWithTag("space-background-microg").assertTextEquals("已啟動")
        compose.onNodeWithTag("space-background-tcp").assertTextEquals("連線中…")
        compose.onNodeWithTag("space-background-retry").assertIsNotEnabled()
    }

    @Test fun pendingRuntimeAfterUiDeadlineKeepsRecoveryButtonDisabled() {
        compose.setContent { AppTwinTheme { SpaceBackgroundCard(BackgroundServiceState(enabled = true, loaded = true),
            SpaceBackgroundHealth("space-2", SpaceBackgroundStatus.SUCCESS, SpaceBackgroundStatus.STARTING,
                error = "連線尚未恢復，請稍後檢查"), false, {}) } }
        compose.onNodeWithTag("space-background-summary").assertTextEquals("恢復中…")
        compose.onNodeWithTag("space-background-retry").assertTextEquals("連線中…").assertIsNotEnabled()
    }

}
