package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.MainUiState
import org.apptwin.permissions.ClonePermissionAction
import org.apptwin.permissions.ClonePermissionCategory
import org.apptwin.permissions.ClonePermissionSummary
import org.apptwin.permissions.ClonePermissionTarget
import org.apptwin.permissions.ClonePermissionVirtualScope
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPermissionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsShowsAggregatedPermissionAndDispatchesItsAction() {
        var selected: ClonePermissionSummary? = null
        setContent(onPermissionAction = { selected = it })

        composeRule.onNodeWithTag("clone-permission-card").assertIsDisplayed()
        composeRule.onNodeWithText("0 / 1 項已就緒").assertIsDisplayed()
        composeRule.onNodeWithText("相機").assertIsDisplayed()
        composeRule.onNodeWithText("尚未授權").assertIsDisplayed()
        composeRule.onNodeWithTag("permission-action-${camera.permission}").performClick()

        composeRule.runOnIdle { assertEquals(camera, selected) }
    }

    @Test
    fun settingsExpandsAffectedClonesAndGroupedPermissions() {
        setContent()

        composeRule.onNodeWithText("影響 2 個分身").performClick()
        composeRule.onNodeWithText("工作 · 相機 App").assertIsDisplayed()
        composeRule.onNodeWithText("個人 · 聊天 App").assertIsDisplayed()

        composeRule.onNodeWithTag("permission-auto-section").performClick()
        composeRule.onNodeWithText("網路連線").assertIsDisplayed()
        composeRule.onNodeWithTag("permission-unsupported-section").performClick()
        composeRule.onNodeWithText("安全系統設定").assertIsDisplayed()
    }

    @Test
    fun settingsRemovesRuntimeAndIsolationCards() {
        setContent()

        composeRule.onNodeWithText("執行環境").assertDoesNotExist()
        composeRule.onNodeWithText("分身空間的隔離範圍").assertDoesNotExist()
    }

    private fun setContent(
        onPermissionAction: (ClonePermissionSummary) -> Unit = {},
    ) {
        composeRule.setContent {
            AppTwinTheme {
                SettingsScreen(
                    state = MainUiState(
                        isRefreshing = false,
                        clonePermissions = listOf(camera, internet, unsupported),
                    ),
                    onOpenStorageSettings = {},
                    onExportDiagnostics = {},
                    notificationsGranted = true,
                    onRequestNotifications = {},
                    onPermissionAction = onPermissionAction,
                )
            }
        }
    }

    private companion object {
        val workCamera = ClonePermissionTarget(
            groupId = "group-work",
            groupName = "工作",
            packageName = "com.example.camera",
            appLabel = "相機 App",
        )
        val personalChat = ClonePermissionTarget(
            groupId = "group-personal",
            groupName = "個人",
            packageName = "com.example.chat",
            appLabel = "聊天 App",
        )
        val camera = ClonePermissionSummary(
            permission = "android.permission.CAMERA",
            label = "相機",
            category = ClonePermissionCategory.RUNTIME,
            virtualScope = ClonePermissionVirtualScope.CAMERA_MIC_PER_SPACE,
            granted = false,
            action = ClonePermissionAction.REQUEST_RUNTIME,
            affectedClones = listOf(workCamera, personalChat),
        )
        val internet = ClonePermissionSummary(
            permission = "android.permission.INTERNET",
            label = "網路連線",
            category = ClonePermissionCategory.AUTOMATIC,
            virtualScope = ClonePermissionVirtualScope.HOST_SHARED,
            granted = true,
            action = ClonePermissionAction.NONE,
            affectedClones = listOf(personalChat),
        )
        val unsupported = ClonePermissionSummary(
            permission = "android.permission.WRITE_SECURE_SETTINGS",
            label = "安全系統設定",
            category = ClonePermissionCategory.UNSUPPORTED,
            virtualScope = ClonePermissionVirtualScope.NOT_SUPPORTED,
            granted = false,
            action = ClonePermissionAction.NONE,
            affectedClones = listOf(workCamera),
        )
    }
}
