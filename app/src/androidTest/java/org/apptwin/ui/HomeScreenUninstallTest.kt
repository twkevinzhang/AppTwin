package org.apptwin.ui

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
import org.apptwin.GroupAppItem
import org.apptwin.GroupItem
import org.apptwin.MainUiState
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme

@RunWith(AndroidJUnit4::class)
class HomeScreenUninstallTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortTapLaunchesApp() {
        var launched: GroupAppItem? = null
        setSpaceDetail(onLaunch = { launched = it })

        composeRule.onNodeWithTag(tileTag).performClick()

        composeRule.runOnIdle {
            assertEquals(appItem, launched)
        }
    }

    @Test
    fun longPressDoesNotLaunchAndCancelThenConfirmControlsUninstall() {
        var launched: GroupAppItem? = null
        var uninstalled: GroupAppItem? = null
        setSpaceDetail(
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
        setSpaceDetail(
            state = uiState.copy(uninstallingAppKey = appItem.launchKey),
            onLaunch = { launched = it },
        )

        composeRule.onNodeWithTag(tileTag).assertIsNotEnabled()
        composeRule.onNodeWithText("移除中…").assertIsDisplayed()
        composeRule.runOnIdle { assertNull(launched) }
    }

    @Test
    fun metadataWarningRemainsVisibleWhenNoValidGroupCanBeLoaded() {
        composeRule.setContent {
            AppTwinTheme {
                HomeScreen(
                    state = MainUiState(
                        isRefreshing = false,
                        dataWarnings = listOf("corrupt group metadata"),
                    ),
                    onOpenSpace = {},
                    onCreateGroup = {},
                )
            }
        }

        composeRule.onNodeWithTag("data-integrity-warning").assertIsDisplayed()
        composeRule.onNodeWithText("偵測到 1 筆空間資料問題").assertIsDisplayed()
    }

    private fun setSpaceDetail(
        state: MainUiState = uiState,
        onLaunch: (GroupAppItem) -> Unit = {},
        onUninstallApp: (GroupAppItem) -> Unit = {},
    ) {
        composeRule.setContent {
            AppTwinTheme {
                SpaceDetailScreen(
                    state = state,
                    space = state.groups.single(),
                    onLaunch = onLaunch,
                    onAddApp = {},
                    onRenameSpace = { _, _ -> },
                    onDeleteSpace = {},
                    onUninstallApp = onUninstallApp,
                    onCreateShortcut = {},
                    onRepairApp = {},
                    onSetPermission = { _, _, _ -> },
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
            app = groupApp,
            appLabel = "測試記事",
            versionName = "1.0",
            sourceInstalled = true,
            launchStatus = "可啟動",
        )
        val uiState = MainUiState(
            isRefreshing = false,
            groups = listOf(
                GroupItem(
                    groupId = groupId,
                    name = "工作",
                    health = GroupHealth.HEALTHY,
                    apps = listOf(appItem),
                ),
            ),
        )
        val tileTag = "group-app-tile-${appItem.launchKey}"
        val uninstallActionTag = "uninstall-app-${appItem.launchKey}"
    }
}
