package org.apptwin.ui

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupAppItem
import org.apptwin.GroupItem
import org.apptwin.archive.SpaceBackupEntry
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaceDetailsUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val app = GroupAppItem("space-a", "工作", GroupHealth.HEALTHY,
        GroupApp("org.example.probe", 1L, GroupAppState.ENABLED), "測試 App", "1", true, "")
    private val space = GroupItem("space-a", "工作", GroupHealth.HEALTHY, listOf(app))
    private val backup = SpaceBackupEntry("backup-a", space.groupId, space.name, "content://test/backup", 2048, 1000, true)

    @Test fun permissionPageShowsHostGateAndDispatchesExactAppDecision() {
        var changed: Triple<GroupAppItem, String, Boolean>? = null
        setDetails(onPermission = { target, permission, allowed -> changed = Triple(target, permission, allowed) })
        composeRule.onNodeWithText("按權限管理 App").performScrollTo().performClick()
        composeRule.onNodeWithText("AppTwin 系統權限尚未允許").assertIsDisplayed()
        composeRule.onNodeWithTag("space-permission-${Manifest.permission.CAMERA}-${app.launchKey}").performClick()
        composeRule.runOnIdle { assertEquals(Triple(app, Manifest.permission.CAMERA, false), changed) }
    }

    @Test fun permanentBackupDeleteRequiresConfirmationAndCancelKeepsFile() {
        var deleted: SpaceBackupEntry? = null
        setDetails(onDeleteBackup = { deleted = it })
        composeRule.onNodeWithText("管理備份").performScrollTo().performClick()
        composeRule.onNodeWithTag("space-backup-delete-${backup.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("delete-backup-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(null, deleted) }
        composeRule.onNodeWithTag("space-backup-delete-${backup.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm-delete-backup").performClick()
        composeRule.runOnIdle { assertEquals(backup, deleted) }
    }

    @Test fun createFromBackupDispatchesSourceAndLocationHasNoPerSpaceOverride() {
        var created: SpaceBackupEntry? = null
        setDetails(onCreate = { created = it })
        composeRule.onNodeWithText("管理備份").performScrollTo().performClick()
        composeRule.onNodeWithText("所有空間跟隨 Global 設定。").assertIsDisplayed()
        composeRule.onNodeWithTag("space-backup-create-${backup.id}").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(backup, created) }
        composeRule.onNodeWithTag("choose-global-backup-location").assertDoesNotExist()
    }

    @Test fun generalBackupDoesNotShowLineIdentityRestrictions() {
        composeRule.setContent { AppTwinTheme { ExportSpaceArchiveDialog(space, {}, {}) } }
        composeRule.onNodeWithText("備份會包含此空間的 App、登入狀態、資料及 Google 服務相容資料。").assertIsDisplayed()
        composeRule.onNodeWithText("含 LINE 的備份", substring = true).assertDoesNotExist()
    }

    @Test fun lineBackupShowsItsDeviceAndSingleOwnerRestrictions() {
        val lineApp = app.copy(app = GroupApp("jp.naver.line.android", 1L, GroupAppState.ENABLED))
        composeRule.setContent { AppTwinTheme { ExportSpaceArchiveDialog(space.copy(apps = listOf(lineApp)), {}, {}) } }
        composeRule.onNodeWithText("含 LINE 的備份", substring = true).assertIsDisplayed()
    }

    private fun setDetails(
        onPermission: (GroupAppItem, String, Boolean) -> Unit = { _, _, _ -> },
        onDeleteBackup: (SpaceBackupEntry) -> Unit = {},
        onCreate: (SpaceBackupEntry) -> Unit = {},
    ) {
        composeRule.setContent { AppTwinTheme {
            SpaceDetailsScreen(space, listOf("App 資料" to 1000L),
                listOf(SpacePermissionRow(Manifest.permission.CAMERA, "相機", app, true, false)),
                emptyList(), emptyList(), listOf(backup), false, false, null, "content://test/global",
                {}, onPermission, {}, {}, { _, _ -> }, onCreate, onDeleteBackup, {}, {}, {}, {}, {})
        } }
    }
}
