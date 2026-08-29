package org.apptwin.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.MainUiState
import org.apptwin.archive.SpaceArchiveCompression
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

        composeRule.onNodeWithTag("clone-permission-card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 / 1 項已就緒").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("相機").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("尚未授權").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("permission-action-${camera.permission}")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals(camera, selected) }
    }

    @Test
    fun settingsExpandsAffectedClonesAndGroupedPermissions() {
        setContent()

        composeRule.onNodeWithText("影響 2 個分身").performScrollTo().performClick()
        composeRule.onNodeWithText("工作 · 相機 App").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("個人 · 聊天 App").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("permission-auto-section").performScrollTo().performClick()
        composeRule.onNodeWithText("網路連線").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("permission-unsupported-section").performScrollTo().performClick()
        composeRule.onNodeWithText("安全系統設定").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsRemovesRuntimeAndIsolationCards() {
        setContent()

        composeRule.onNodeWithText("執行環境").assertDoesNotExist()
        composeRule.onNodeWithText("分身空間的隔離範圍").assertDoesNotExist()
    }

    @Test
    fun archiveExportSettingsDefaultsToMediumAndShowsOnlyItsDescription() {
        setContent()

        composeRule.onNodeWithTag("archive-export-settings-card").assertIsDisplayed()
        composeRule.onNodeWithTag("archive-compression-high").assertIsNotSelected()
        composeRule.onNodeWithTag("archive-compression-medium").assertIsSelected()
        composeRule.onNodeWithTag("archive-compression-low").assertIsNotSelected()
        composeRule.onNodeWithText("檔案通常較小，匯出較慢").assertDoesNotExist()
        composeRule.onNodeWithText("檔案大小與匯出速度較為平衡").assertIsDisplayed()
        composeRule.onNodeWithText("匯出較快，檔案通常較大").assertDoesNotExist()
    }

    @Test
    fun archiveExportSettingsSelectsOneLevelAndDispatchesExactCallback() {
        val selected = mutableStateOf(SpaceArchiveCompression.MEDIUM)
        var callbackValue: SpaceArchiveCompression? = null
        composeRule.setContent {
            AppTwinTheme {
                SettingsScreen(
                    state = MainUiState(
                        isRefreshing = false,
                        archiveCompression = selected.value,
                    ),
                    onArchiveCompressionChange = { compression ->
                        callbackValue = compression
                        selected.value = compression
                    },
                    onOpenStorageSettings = {},
                    onExportDiagnostics = {},
                    notificationsGranted = true,
                    onRequestNotifications = {},
                    onPermissionAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("archive-compression-low").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            callbackValue == SpaceArchiveCompression.LOW
        }
        composeRule.runOnIdle {
            assertEquals(SpaceArchiveCompression.LOW, callbackValue)
            assertEquals(SpaceArchiveCompression.LOW, selected.value)
        }

        composeRule.onNodeWithTag("archive-compression-high").assertIsNotSelected()
        composeRule.onNodeWithTag("archive-compression-medium").assertIsNotSelected()
        composeRule.onNodeWithTag("archive-compression-low").assertIsSelected()
        composeRule.onNodeWithText("檔案大小與匯出速度較為平衡").assertDoesNotExist()
        composeRule.onNodeWithText("匯出較快，檔案通常較大").assertIsDisplayed()
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
