package org.apptwin.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupItem
import org.apptwin.MainUiState
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStateRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createGroupDraftSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            AppTwinTheme {
                CreateGroupDialog(onDismiss = {}, onConfirm = {})
            }
        }
        composeRule.onNodeWithText("群組名稱").performTextInput("工作")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("群組名稱").assertTextContains("工作")
    }

    @Test
    fun appPickerQuerySurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            AppTwinTheme {
                AppPickerScreen(
                    state = MainUiState(isRefreshing = false),
                    group = GroupItem(
                        groupId = GROUP_ID,
                        name = "工作",
                        health = GroupHealth.HEALTHY,
                        apps = emptyList(),
                    ),
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText("搜尋 App 或套件名稱").performTextInput("LINE")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("搜尋 App 或套件名稱")
            .assertTextContains("LINE")
    }

    private companion object {
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"
    }
}
