package org.maskaccounts.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maskaccounts.GroupAppItem
import org.maskaccounts.GroupItem
import org.maskaccounts.MainUiState
import org.maskaccounts.groups.GoogleServicesState
import org.maskaccounts.groups.GroupApp
import org.maskaccounts.groups.GroupAppState
import org.maskaccounts.groups.GroupHealth
import org.maskaccounts.ui.theme.MaskAccountsTheme

@RunWith(AndroidJUnit4::class)
class HomeScreenUninstallTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortTapLaunchesApp() {
        var launched: GroupAppItem? = null
        setHome(onLaunch = { launched = it })

        composeRule.onNodeWithTag(tileTag).performClick()

        composeRule.runOnIdle {
            assertEquals(appItem, launched)
        }
    }

    @Test
    fun longPressDoesNotLaunchAndCancelThenConfirmControlsUninstall() {
        var launched: GroupAppItem? = null
        var uninstalled: GroupAppItem? = null
        setHome(
            onLaunch = { launched = it },
            onUninstallApp = { uninstalled = it },
        )

        composeRule.onNodeWithTag(tileTag).performTouchInput { longClick() }

        composeRule.runOnIdle { assertNull(launched) }
        composeRule.onNodeWithTag(uninstallActionTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("uninstall-app-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel-uninstall-app").performClick()

        composeRule.runOnIdle { assertNull(uninstalled) }
        composeRule.onNodeWithTag("uninstall-app-dialog").assertDoesNotExist()

        composeRule.onNodeWithTag(tileTag).performTouchInput { longClick() }
        composeRule.onNodeWithTag(uninstallActionTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("confirm-uninstall-app").performClick()

        composeRule.runOnIdle {
            assertEquals(appItem, uninstalled)
            assertNull(launched)
        }
    }

    @Test
    fun uninstallBusyDisablesTileAndShowsProgressLabel() {
        var launched: GroupAppItem? = null
        setHome(
            state = uiState.copy(uninstallingAppKey = appItem.launchKey),
            onLaunch = { launched = it },
        )

        composeRule.onNodeWithTag(tileTag).assertIsNotEnabled()
        composeRule.onNodeWithText("解除安裝中…").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(launched) }
    }

    private fun setHome(
        state: MainUiState = uiState,
        onLaunch: (GroupAppItem) -> Unit = {},
        onUninstallApp: (GroupAppItem) -> Unit = {},
    ) {
        composeRule.setContent {
            MaskAccountsTheme {
                HomeScreen(
                    state = state,
                    onLaunch = onLaunch,
                    onLaunchPlayStore = {},
                    onAddApp = {},
                    onPrepareGroup = {},
                    onRenameGroup = { _, _ -> },
                    onDeleteGroup = {},
                    onUninstallApp = onUninstallApp,
                    onCreateGroup = {},
                )
            }
        }
    }

    private companion object {
        const val groupId = "11111111-1111-1111-1111-111111111111"
        val groupApp = GroupApp(
            packageName = "com.example.notes",
            addedAtEpochMillis = 123L,
            state = GroupAppState.ENABLED,
        )
        val appItem = GroupAppItem(
            groupId = groupId,
            groupName = "工作",
            groupHealth = GroupHealth.HEALTHY,
            googleServicesState = GoogleServicesState.READY,
            app = groupApp,
            appLabel = "測試記事",
            versionName = "1.0",
            sourceInstalled = true,
            launchSupported = true,
            launchStatus = "可啟動",
        )
        val uiState = MainUiState(
            isRefreshing = false,
            groups = listOf(
                GroupItem(
                    groupId = groupId,
                    name = "工作",
                    health = GroupHealth.HEALTHY,
                    googleServicesState = GoogleServicesState.READY,
                    apps = listOf(appItem),
                ),
            ),
        )
        val tileTag = "group-app-tile-${appItem.launchKey}"
        val uninstallActionTag = "uninstall-app-${appItem.launchKey}"
    }
}
