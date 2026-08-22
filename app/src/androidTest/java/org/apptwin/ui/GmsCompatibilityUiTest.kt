package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apptwin.GroupItem
import org.apptwin.MainUiState
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityAssessment
import org.apptwin.gms.capabilities.GmsCapabilityStatus
import org.apptwin.gms.capabilities.GmsEvidenceTier
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.ports.CloudMessagingHealth
import org.apptwin.gms.ports.CloudMessagingState
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GmsCompatibilityUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun consentExplainsProviderAndScopesEnableToSelectedGroup() {
        var enabled: Pair<String, Boolean>? = null
        setContent(onEnable = { id, consent -> enabled = id to consent })

        composeRule.onNodeWithTag("gms-compatibility-card").assertIsDisplayed()
        composeRule.onNodeWithText("Google 服務相容功能").assertIsDisplayed()
        composeRule.onNodeWithText("啟用").performClick()
        composeRule.onNodeWithTag("gms-consent-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("同意並啟用").performClick()

        composeRule.runOnIdle { assertEquals(groupId to true, enabled) }
    }

    @Test
    fun cardShowsOverallStateAndActionsWithoutTechnicalCapabilityList() {
        setContent(spaceOverride = enabledSpace)

        composeRule.onNodeWithText("由 microG 提供，並非 Google 官方服務")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Google 服務").assertIsDisplayed()
        composeRule.onNodeWithText("已啟用").assertIsDisplayed()
        composeRule.onNodeWithText("背景通知").assertIsDisplayed()
        composeRule.onNodeWithText("已連線").assertIsDisplayed()
        composeRule.onNodeWithText("停用").assertIsDisplayed()
        composeRule.onNodeWithText("重設資料").assertIsDisplayed()

        listOf(
            "Play services availability",
            "FCM token",
            "FCM 訊息接收",
            "通知與空間路由",
            "Fused Location",
            "Maps SDK v2",
            "Google Sign-In (legacy)",
            "Google Identity Services",
            "Cast sender",
            "Nearby",
            "Play Billing",
            "Play Integrity",
            "尚未驗證",
            "不支援",
            "外部待驗",
        ).forEach { hiddenText ->
            composeRule.onNodeWithText(hiddenText, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun disabledStateActionsAreHorizontalAndOrderedFromLeftToRight() {
        setContent()

        assertActionsAreHorizontalAndOrdered()
        composeRule.onNodeWithText("啟用").assertIsDisplayed()
    }

    @Test
    fun enabledStateActionsAreHorizontalAndOrderedFromLeftToRight() {
        setContent(spaceOverride = enabledSpace)

        assertActionsAreHorizontalAndOrdered()
        composeRule.onNodeWithText("停用").assertIsDisplayed()
    }

    @Test
    fun busyIndicatorStaysCenteredAtTheEndOfTheActionsRow() {
        setContent(isGmsBusy = true)

        assertActionsAreHorizontalAndOrdered()
        val resetBounds = composeRule.onNodeWithTag("gms-reset-button")
            .fetchSemanticsNode().boundsInRoot
        val busyBounds = composeRule.onNodeWithTag("gms-busy-indicator")
            .fetchSemanticsNode().boundsInRoot

        assertTrue("Busy indicator should be after the reset button", resetBounds.right < busyBounds.left)
        assertTrue(
            "Busy indicator should be vertically centered with the action buttons",
            abs(resetBounds.center.y - busyBounds.center.y) <= 1f,
        )
    }

    @Test
    fun resetRequiresDestructiveConfirmation() {
        var reset: Pair<String, Boolean>? = null
        setContent(onReset = { id, reenable -> reset = id to reenable })

        composeRule.onNodeWithText("重設資料").performClick()
        composeRule.onNodeWithTag("gms-reset-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("永久重設").performClick()

        composeRule.runOnIdle { assertEquals(groupId to false, reset) }
    }

    @Test
    fun backgroundNotificationShowsDisabledState() {
        assertCloudMessagingStatus(CloudMessagingState.DISABLED, "已停用")
    }

    @Test
    fun backgroundNotificationShowsStartingState() {
        assertCloudMessagingStatus(CloudMessagingState.STARTING, "連線中")
    }

    @Test
    fun backgroundNotificationShowsConnectedState() {
        assertCloudMessagingStatus(CloudMessagingState.CONNECTED, "已連線")
    }

    @Test
    fun backgroundNotificationShowsDegradedState() {
        assertCloudMessagingStatus(CloudMessagingState.DEGRADED, "需要處理")
    }

    @Test
    fun backgroundNotificationShowsUnknownState() {
        assertCloudMessagingStatus(CloudMessagingState.UNKNOWN, "未知")
    }

    private fun assertCloudMessagingStatus(state: CloudMessagingState, expected: String) {
        setContent(spaceOverride = enabledSpaceWithCloudMessaging(state))

        composeRule.onNodeWithTag("gms-services-status").assertIsDisplayed()
        composeRule.onNodeWithTag("gms-cloud-messaging-status").assertIsDisplayed()
        composeRule.onNodeWithText("背景通知").assertIsDisplayed()
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    private fun setContent(
        onEnable: (String, Boolean) -> Unit = { _, _ -> },
        onReset: (String, Boolean) -> Unit = { _, _ -> },
        spaceOverride: GroupItem = space,
        isGmsBusy: Boolean = false,
    ) {
        val state = MainUiState(
            isRefreshing = false,
            groups = listOf(spaceOverride),
            gmsBusyGroupId = groupId.takeIf { isGmsBusy },
        )
        composeRule.setContent {
            AppTwinTheme {
                SpaceDetailScreen(
                    state = state,
                    space = spaceOverride,
                    onLaunch = {},
                    onAddApp = {},
                    onRenameSpace = { _, _ -> },
                    onDeleteSpace = {},
                    onUninstallApp = {},
                    onCreateShortcut = {},
                    onRepairApp = {},
                    onSetPermission = { _, _, _ -> },
                    onEnableGms = onEnable,
                    onResetGms = onReset,
                )
            }
        }
    }

    private fun assertActionsAreHorizontalAndOrdered() {
        val toggleBounds = composeRule.onNodeWithTag("gms-toggle-button")
            .fetchSemanticsNode().boundsInRoot
        val resetBounds = composeRule.onNodeWithTag("gms-reset-button")
            .fetchSemanticsNode().boundsInRoot

        assertTrue("Toggle button should be before the reset button", toggleBounds.right < resetBounds.left)
        assertTrue(
            "Action buttons should share the same vertical center",
            abs(toggleBounds.center.y - resetBounds.center.y) <= 1f,
        )
    }

    private companion object {
        const val groupId = "33333333-3333-3333-3333-333333333333"
        val product = GmsGroupProductState(
            profile = GmsProfile(
                groupId = GmsGroupId(groupId),
                desiredState = GmsDesiredState.DISABLED,
                observedState = GmsObservedState.ABSENT,
                networkConsent = GmsNetworkConsent.NOT_GRANTED,
            ),
            capabilities = GmsCapability.entries.map { capability ->
                GmsCapabilityAssessment(
                    capability = capability,
                    status = when (capability) {
                        GmsCapability.PLAY_BILLING,
                        GmsCapability.PLAY_INTEGRITY,
                        -> GmsCapabilityStatus.UNSUPPORTED
                        GmsCapability.FCM_MESSAGE ->
                            GmsCapabilityStatus.FIXTURE_PASSED_EXTERNAL_UNTESTED
                        else -> GmsCapabilityStatus.UNTESTED
                    },
                    evidenceTier = if (capability == GmsCapability.FCM_MESSAGE) {
                        GmsEvidenceTier.ASUS_FIXTURE
                    } else {
                        null
                    },
                )
            },
            cloudMessaging = CloudMessagingHealth(CloudMessagingState.DISABLED),
        )
        val space = GroupItem(
            groupId = groupId,
            name = "工作",
            health = GroupHealth.HEALTHY,
            apps = emptyList(),
            gmsCompatibility = product,
        )
        val enabledSpace = space.copy(
            gmsCompatibility = product.copy(
                profile = product.profile.copy(
                    desiredState = GmsDesiredState.ENABLED,
                    observedState = GmsObservedState.READY_PARTIAL,
                    networkConsent = GmsNetworkConsent.GRANTED,
                    observedReleaseId = "microg-v0.3.15.250932",
                ),
                cloudMessaging = CloudMessagingHealth(CloudMessagingState.CONNECTED),
            ),
        )

        fun enabledSpaceWithCloudMessaging(state: CloudMessagingState): GroupItem =
            enabledSpace.copy(
                gmsCompatibility = requireNotNull(enabledSpace.gmsCompatibility).copy(
                    cloudMessaging = CloudMessagingHealth(state),
                ),
            )
    }
}
