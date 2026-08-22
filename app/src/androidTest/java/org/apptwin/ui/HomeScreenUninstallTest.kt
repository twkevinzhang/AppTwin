package org.apptwin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    fun clearStorageDialogPreservesSharedFilesAndLocksActionsWhileClearing() {
        var cleared: GroupAppItem? = null
        composeRule.setContent {
            var clearingStorageAppKey by remember { mutableStateOf<String?>(null) }
            AppTwinTheme {
                SpaceDetailScreen(
                    state = uiState,
                    space = uiState.groups.single(),
                    onLaunch = {},
                    onAddApp = {},
                    onRenameSpace = { _, _ -> },
                    onDeleteSpace = {},
                    onUninstallApp = {},
                    onCreateShortcut = {},
                    onRepairApp = {},
                    clearingStorageAppKey = clearingStorageAppKey,
                    onClearStorage = {
                        cleared = it
                        clearingStorageAppKey = it.launchKey
                    },
                    onSetPermission = { _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag(tileTag).performTouchInput { longClick() }
        composeRule.onNodeWithTag(clearStorageActionTag, useUnmergedTree = true).performClick()

        composeRule.onNodeWithTag("clear-storage-dialog").assertIsDisplayed()
        composeRule.onNodeWithText(
            "將先停止此分身 App，接著永久刪除登入、App 資料、快取及該分身可歸屬的私有外部檔案。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "同一空間內與其他分身共用的檔案不會清除。手機上的原始 App 和其他分身空間不受影響；此操作無法復原。",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-clear-storage").performClick()

        composeRule.runOnIdle { assertEquals(appItem, cleared) }
        composeRule.onNodeWithTag("clear-storage-progress").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-clear-storage").assertIsNotEnabled()
        composeRule.onNodeWithTag("cancel-clear-storage").assertIsNotEnabled()
        composeRule.onNodeWithTag(tileTag).assertIsNotEnabled()
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
        val clearStorageActionTag = "clear-storage-app-${appItem.launchKey}"
    }
}
