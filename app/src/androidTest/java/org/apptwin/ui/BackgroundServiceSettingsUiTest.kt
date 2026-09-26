package org.apptwin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.runtime.BackgroundServiceState
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundServiceSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toggleWaitsForAuthoritativeStateAndReflectsExternalChange() {
        val state = mutableStateOf(BackgroundServiceState(enabled = true, loaded = true))
        val requests = mutableListOf<Boolean>()
        compose.setContent {
            AppTwinTheme {
                BackgroundServiceSettingsCard(state.value, requests::add, onAddTile = {})
            }
        }
        compose.onNodeWithTag("background-service-switch").performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(false), requests)
            state.value = BackgroundServiceState(enabled = false, loaded = true)
        }
        compose.onNodeWithTag("background-service-switch").assertIsOff()
        compose.runOnIdle { state.value = state.value.copy(enabled = true) }
        compose.onNodeWithTag("background-service-switch").assertIsOn()
    }

    @Test fun pendingOperationDisablesToggleAndErrorRetryDoesNotReversePolicy() {
        val state = mutableStateOf(BackgroundServiceState(enabled = false, loaded = true, busy = true))
        var retries = 0
        val requests = mutableListOf<Boolean>()
        compose.setContent {
            AppTwinTheme {
                BackgroundServiceSettingsCard(
                    state.value, requests::add, onRetry = { retries++ }, onAddTile = {},
                )
            }
        }
        compose.onNodeWithTag("background-service-switch").assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = "切換未完成") }
        compose.onNodeWithText("重試").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(emptyList<Boolean>(), requests)
        }
    }
}
