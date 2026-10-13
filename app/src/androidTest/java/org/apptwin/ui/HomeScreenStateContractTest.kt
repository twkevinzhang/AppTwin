package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupItem
import org.apptwin.MainUiState
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
        setHome(MainUiState(isRefreshing = true))
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
        composeRule.onNodeWithText("建立空間").assertDoesNotExist()
    }

    @Test
    fun successfulEmptySnapshotOffersSpaceCreation() {
        var calls = 0
        setHome(MainUiState(isRefreshing = false), onCreate = { calls++ })
        composeRule.onNodeWithText("建立第一個分身空間").assertIsDisplayed()
        composeRule.onNodeWithText("建立空間").performClick()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun refreshingSnapshotKeepsExistingSpaceAndIntegrityWarningVisible() {
        val space = GroupItem("fixture-space", "契約測試空間", GroupHealth.HEALTHY, emptyList())
        setHome(MainUiState(
            groups = listOf(space),
            isRefreshing = true,
            dataWarnings = listOf("fixture metadata failure"),
        ))
        composeRule.onNodeWithTag("data-integrity-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("space-manage-${space.groupId}").assertIsDisplayed()
        composeRule.onNodeWithText("建立第一個分身空間").assertDoesNotExist()
    }

    private fun setHome(state: MainUiState, onCreate: () -> Unit = {}) {
        composeRule.setContent {
            AppTwinTheme { HomeScreen(state = state, onCreateGroup = onCreate) }
        }
    }
}
