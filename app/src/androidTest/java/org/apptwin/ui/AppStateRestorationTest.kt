package org.apptwin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
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
        composeRule.onNodeWithText("空間名稱").performTextInput("工作")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("空間名稱").assertTextContains("工作")
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

    @Test
    fun renameSpaceDialogAndDraftSurviveSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val space = GroupItem(
            groupId = GROUP_ID,
            name = "工作",
            health = GroupHealth.HEALTHY,
            apps = emptyList(),
        )
        restorationTester.setContent {
            var selectedSpaceId by rememberSaveable { mutableStateOf<String?>(null) }
            AppTwinTheme {
                if (selectedSpaceId == space.groupId) {
                    SpaceDetailsScreen(
                        group = space,
                        storage = emptyList(),
                        permissions = emptyList(),
                        accounts = emptyList(),
                        authenticators = emptyList(),
                        backups = emptyList(),
                        loading = false,
                        busy = false,
                        error = null,
                        globalBackupLocation = null,
                        onRefresh = {},
                        onSetPermission = { _, _, _ -> },
                        onAddAccount = {},
                        onRemoveAccount = {},
                        onSynchronizeAccount = { _, _ -> },
                        onCreateFromBackup = {},
                        onDeleteBackup = {},
                        onExport = {},
                        onOpenGlobalSettings = {},
                        onRename = {},
                        onClearAllAppData = {},
                        onDeleteSpace = {},
                    )
                } else {
                    HomeScreen(
                        state = MainUiState(isRefreshing = false, groups = listOf(space)),
                        onCreateGroup = {},
                        onOpenSpaceDetails = { selectedSpaceId = it },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("space-manage-$GROUP_ID").performClick()
        composeRule.onNodeWithTag("space-details")
            .performScrollToNode(hasTestTag("space-rename"))
        composeRule.onNodeWithTag("space-rename").performClick()
        composeRule.onNodeWithText("空間名稱").performTextClearance()
        composeRule.onNodeWithText("空間名稱").performTextInput("專案 A")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("重新命名空間").assertExists()
        composeRule.onNodeWithText("空間名稱").assertTextContains("專案 A")
    }

    private companion object {
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"
    }
}
