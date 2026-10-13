package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupItem
import org.apptwin.HomeUiState
import androidx.compose.runtime.mutableStateOf
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenStateContractTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun initialLoadingDoesNotOfferEmptySpaceCreation() {
        setHome(HomeUiState(isRefreshing = true))
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
        composeRule.onNodeWithText("建立空間").assertDoesNotExist()
    }

    @Test
    fun successfulEmptySnapshotOffersSpaceCreation() {
        var calls = 0
        setHome(HomeUiState(isRefreshing = false), onCreate = { calls++ })
        composeRule.onNodeWithText("建立第一個分身空間").assertIsDisplayed()
        composeRule.onNodeWithText("建立空間").performClick()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun refreshingSnapshotKeepsExistingSpaceAndIntegrityWarningVisible() {
        val space = GroupItem("fixture-space", "契約測試空間", GroupHealth.HEALTHY, emptyList())
        setHome(HomeUiState(
            groups = listOf(space),
            isRefreshing = true,
            dataWarnings = listOf("fixture metadata failure"),
        ))
        composeRule.onNodeWithTag("data-integrity-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("space-manage-${space.groupId}").assertIsDisplayed()
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
    }

    @Test
    fun catalogFailureOffersRetryInsteadOfEmptyCreation() {
        var retries = 0
        composeRule.setContent {
            AppTwinTheme {
                HomeScreen(HomeUiState(isRefreshing = false, error = "讀取失敗"), onRefresh = { retries++ }, onCreateGroup = {})
            }
        }
        composeRule.onNodeWithTag("home-error").assertIsDisplayed()
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
        composeRule.onNodeWithTag("home-retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun integrityFailureWithoutValidRowsDoesNotClaimSuccessfulEmpty() {
        setHome(HomeUiState(isRefreshing = false, dataWarnings = listOf("invalid metadata")))
        composeRule.onNodeWithTag("home-error").assertIsDisplayed()
        composeRule.onNodeWithTag("data-integrity-warning").assertIsDisplayed()
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
    }

    @Test
    fun retryRetainsExistingCardsThroughLoadingAndFailure() {
        val space = GroupItem("fixture-space", "契約測試空間", GroupHealth.HEALTHY, emptyList())
        val state = mutableStateOf(HomeUiState(groups = listOf(space), isRefreshing = false, error = "讀取失敗"))
        composeRule.setContent {
            AppTwinTheme { HomeScreen(state.value, onCreateGroup = {}, onRefresh = { state.value = state.value.copy(isRefreshing = true, error = null) }) }
        }
        composeRule.onNodeWithTag("home-retry").performClick()
        composeRule.onNodeWithTag("space-manage-${space.groupId}").assertIsDisplayed()
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
        composeRule.runOnIdle { state.value = state.value.copy(isRefreshing = false, error = "再次失敗") }
        composeRule.onNodeWithTag("home-error").assertIsDisplayed()
        composeRule.onNodeWithTag("space-manage-${space.groupId}").assertIsDisplayed()
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
    }

    private fun setHome(state: HomeUiState, onCreate: () -> Unit = {}) {
        composeRule.setContent {
            AppTwinTheme { HomeScreen(state = state, onCreateGroup = onCreate) }
        }
    }
}
