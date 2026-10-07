package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupAppItem
import org.apptwin.GroupItem
import org.apptwin.MainUiState
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
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
    fun homeInitiallyExpandsEverySpace() {
        setHome(state = multiSpaceState)

        composeRule.onNodeWithTag("home-app-tile-${app.launchKey}").assertIsDisplayed()
        composeRule.onNodeWithTag("home-app-tile-${secondApp.launchKey}").assertIsDisplayed()
    }

    @Test
    fun homeCanCollapseAndExpandOneSpaceWithoutHidingOtherSpaces() {
        setHome(state = multiSpaceState)

        composeRule.onNodeWithTag("space-expand-$spaceId").performClick()

        composeRule.onNodeWithTag("home-app-tile-${app.launchKey}").assertDoesNotExist()
        composeRule.onNodeWithTag("home-app-tile-${secondApp.launchKey}").assertIsDisplayed()

        composeRule.onNodeWithTag("space-expand-$spaceId").performClick()

        composeRule.onNodeWithTag("home-app-tile-${app.launchKey}").assertIsDisplayed()
    }

    @Test
    fun homeCanCollapseAndExpandAllSpaces() {
        setHome(state = multiSpaceState)

        composeRule.onNodeWithTag("expand-all-spaces").performClick()

        composeRule.onNodeWithTag("home-app-tile-${app.launchKey}").assertDoesNotExist()
        composeRule.onNodeWithTag("home-app-tile-${secondApp.launchKey}").assertDoesNotExist()

        composeRule.onNodeWithTag("expand-all-spaces").performClick()

        composeRule.onNodeWithTag("home-app-tile-${app.launchKey}").assertIsDisplayed()
        composeRule.onNodeWithTag("home-app-tile-${secondApp.launchKey}").assertIsDisplayed()
    }

    @Test
    fun homeLaunchesTheExactGroupAppFromItsSpace() {
        var launched: GroupAppItem? = null
        setHome(state = multiSpaceState, onLaunch = { launched = it })

        composeRule.onNodeWithTag("home-app-tile-${secondApp.launchKey}").performClick()

        composeRule.runOnIdle { assertEquals(secondApp, launched) }
    }

    @Test
    fun homeEmptySpaceAddsAppToThatSpace() {
        var addAppGroupId: String? = null
        val emptySpace = space.copy(
            groupId = emptySpaceId,
            name = "空白空間",
            apps = emptyList(),
        )
        setHome(
            state = MainUiState(isRefreshing = false, groups = listOf(emptySpace)),
            onAddApp = { addAppGroupId = it },
        )

        composeRule.onNodeWithTag("home-add-app-$emptySpaceId").performClick()

        composeRule.runOnIdle { assertEquals(emptySpaceId, addAppGroupId) }
    }

    @Test
    fun homePopulatedSpaceAddsAppToThatExactSpace() {
        var addAppGroupId: String? = null
        setHome(
            state = multiSpaceState,
            onAddApp = { addAppGroupId = it },
        )

        composeRule.onNodeWithTag("home-add-app-$secondSpaceId")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle { assertEquals(secondSpaceId, addAppGroupId) }
    }

    @Test
    fun homeBusyPopulatedSpaceDoesNotAddApp() {
        var addAppGroupId: String? = null
        setHome(
            state = multiSpaceState.copy(busyGroupId = spaceId),
            onAddApp = { addAppGroupId = it },
        )

        composeRule.onNodeWithTag("home-add-app-$spaceId")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()

        composeRule.runOnIdle { assertEquals(null, addAppGroupId) }
    }

    @Test
    fun homeAppLongPressShowsTheSameAppActionsWithoutLaunching() {
        var launched: GroupAppItem? = null
        setHome(state = defaultState, onLaunch = { launched = it })

        composeRule.onNodeWithTag("home-app-tile-${app.launchKey}")
            .performTouchInput { longClick() }

        composeRule.runOnIdle { assertEquals(null, launched) }
        composeRule.onNodeWithText("空間權限").assertIsDisplayed()
        composeRule.onNodeWithText("重新同步 App").assertIsDisplayed()
        composeRule.onNodeWithText("建立桌面捷徑").assertIsDisplayed()
        composeRule.onNodeWithText("清除儲存空間").assertIsDisplayed()
        composeRule.onNodeWithText("解除安裝").assertIsDisplayed()
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
    fun sourceMissingAppExplainsWhyItCannotLaunch() {
        setHome(
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
    fun homeMenuNavigatesToExactSpaceWithoutGoogleToggle() {
        var selected: String? = null
        setHome(multiSpaceState, onOpenSpaceDetails = { selected = it })
        composeRule.onNodeWithTag("space-manage-$secondSpaceId").performClick()
        composeRule.runOnIdle { assertEquals(secondSpaceId, selected) }
        composeRule.onNodeWithText("啟用 Google 服務").assertDoesNotExist()
        composeRule.onNodeWithText("停用 Google 服務").assertDoesNotExist()
    }

    private fun setHome(
        state: MainUiState,
        onLaunch: (GroupAppItem) -> Unit = {},
        onAddApp: (String) -> Unit = {},
        onClearAllAppData: (String) -> Unit = {},
        onDeleteSpace: (String) -> Unit = {},
        onOpenSpaceDetails: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            AppTwinTheme {
                HomeScreen(
                    state = state,
                    onCreateGroup = {},
                    onLaunch = onLaunch,
                    onAddApp = onAddApp,
                    onClearAllAppData = onClearAllAppData,
                    onDeleteSpace = onDeleteSpace,
                    onOpenSpaceDetails = onOpenSpaceDetails,
                )
            }
        }
    }

    private companion object {
        const val spaceId = "22222222-2222-2222-2222-222222222222"
        const val secondSpaceId = "33333333-3333-3333-3333-333333333333"
        const val emptySpaceId = "44444444-4444-4444-4444-444444444444"
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
        val disabledGms = GmsGroupProductState(
            profile = GmsProfile.disabled(GmsGroupId(spaceId)),
            capabilities = emptyList(),
        )
        val enabledGms = disabledGms.copy(
            profile = disabledGms.profile.copy(
                desiredState = GmsDesiredState.ENABLED,
                observedState = GmsObservedState.READY_PARTIAL,
                networkConsent = GmsNetworkConsent.GRANTED,
                observedReleaseId = "microg-v1",
            ),
        )
        val secondApp = GroupAppItem(
            groupId = secondSpaceId,
            groupName = "私人",
            groupHealth = GroupHealth.HEALTHY,
            app = GroupApp(
                packageName = "com.example.chat",
                addedAtEpochMillis = 456L,
                state = GroupAppState.ENABLED,
            ),
            appLabel = "測試聊天",
            versionName = "3.0",
            sourceInstalled = true,
            launchStatus = "可啟動",
        )
        val secondSpace = GroupItem(
            groupId = secondSpaceId,
            name = "私人",
            health = GroupHealth.HEALTHY,
            apps = listOf(secondApp),
        )
        val multiSpaceState = MainUiState(
            isRefreshing = false,
            groups = listOf(space, secondSpace),
        )
    }
}
