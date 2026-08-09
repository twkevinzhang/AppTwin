package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupAppItem
import org.apptwin.GroupItem
import org.apptwin.MainUiState
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceProductUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeShowsSpaceSummaryAndOpensSelectedSpace() {
        var openedId: String? = null
        composeRule.setContent {
            AppTwinTheme {
                HomeScreen(
                    state = defaultState,
                    onOpenSpace = { openedId = it },
                    onCreateGroup = {},
                )
            }
        }

        composeRule.onNodeWithTag("space-card-$spaceId").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(spaceId, openedId) }
    }

    @Test
    fun onboardingExplainsBoundariesBeforeContinuing() {
        var completed = false
        composeRule.setContent {
            AppTwinTheme { OnboardingDialog(onContinue = { completed = true }) }
        }

        composeRule.onNodeWithText("空間彼此隔離").assertIsDisplayed()
        composeRule.onNodeWithText("原始 App 不受影響").assertIsDisplayed()
        composeRule.onNodeWithText("相容性可能不同").assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(completed) }
        composeRule.onNodeWithTag("complete-onboarding").performClick()
        composeRule.runOnIdle { assertEquals(true, completed) }
    }

    @Test
    fun deleteSpaceSummarizesAppsAndUnaffectedData() {
        setSpaceDetail()

        composeRule.onNodeWithContentDescription("空間選單").performClick()
        composeRule.onNodeWithText("刪除空間").performClick()

        composeRule.onNodeWithTag("delete-space-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "將永久刪除此空間、1 個分身 App 的登入與所有資料。" +
                "手機上的原始 App 和其他分身空間不受影響，此操作無法復原。",
        ).assertIsDisplayed()
    }

    @Test
    fun sourceMissingAppExplainsWhyItCannotLaunch() {
        setSpaceDetail(
            state = defaultState.copy(
                groups = listOf(
                    space.copy(apps = listOf(app.copy(sourceInstalled = false))),
                ),
            ),
        )

        composeRule.onNodeWithText("原始 App 已移除").assertIsDisplayed()
        composeRule.onNodeWithTag("group-app-tile-${app.launchKey}")
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag("uninstall-app-${app.launchKey}").assertIsEnabled()
    }

    @Test
    fun clonePermissionDialogExplainsPerSpaceScope() {
        setSpaceDetail()

        composeRule.onNodeWithTag("group-app-tile-${app.launchKey}")
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("空間權限").performClick()

        composeRule.onNodeWithTag("clone-permission-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "App 所見的權限決策只套用於「工作」。手機上的原始 App 和其他分身空間不受影響；實際相機／麥克風能力仍可能因 App 與裝置而異。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("相機").assertIsDisplayed()
        composeRule.onNodeWithText("麥克風").assertIsDisplayed()
    }

    private fun setSpaceDetail(state: MainUiState = defaultState) {
        composeRule.setContent {
            AppTwinTheme {
                SpaceDetailScreen(
                    state = state,
                    space = state.groups.single(),
                    onLaunch = {},
                    onAddApp = {},
                    onRenameSpace = { _, _ -> },
                    onDeleteSpace = {},
                    onUninstallApp = {},
                    onCreateShortcut = {},
                    onRepairApp = {},
                    onSetPermission = { _, _, _ -> },
                )
            }
        }
    }

    private companion object {
        const val spaceId = "22222222-2222-2222-2222-222222222222"
        val app = GroupAppItem(
            groupId = spaceId,
            groupName = "工作",
            groupHealth = GroupHealth.HEALTHY,
            app = GroupApp(
                packageName = "com.example.mail",
                addedAtEpochMillis = 123L,
                state = GroupAppState.ENABLED,
            ),
            appLabel = "測試郵件",
            versionName = "2.0",
            sourceInstalled = true,
            launchStatus = "可啟動",
        )
        val space = GroupItem(
            groupId = spaceId,
            name = "工作",
            health = GroupHealth.HEALTHY,
            apps = listOf(app),
        )
        val defaultState = MainUiState(isRefreshing = false, groups = listOf(space))
    }
}
