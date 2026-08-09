package org.apptwin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import org.apptwin.groups.GroupHealth
import org.apptwin.ui.theme.AppTwinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    fun evidenceClaimsStayWithinFixtureBoundaryAndUnsupportedIsExplicit() {
        setContent()

        composeRule.onNodeWithText("ASUS fixture 通過／外部待驗").assertIsDisplayed()
        composeRule.onNodeWithTag("gms-capability-status-PLAY_BILLING")
            .assertTextEquals("不支援")
        composeRule.onNodeWithTag("gms-capability-status-PLAY_INTEGRITY")
            .assertTextEquals("不支援")
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

    private fun setContent(
        onEnable: (String, Boolean) -> Unit = { _, _ -> },
        onReset: (String, Boolean) -> Unit = { _, _ -> },
    ) {
        val state = MainUiState(isRefreshing = false, groups = listOf(space))
        composeRule.setContent {
            AppTwinTheme {
                SpaceDetailScreen(
                    state = state,
                    space = space,
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
        )
        val space = GroupItem(
            groupId = groupId,
            name = "工作",
            health = GroupHealth.HEALTHY,
            apps = emptyList(),
            gmsCompatibility = product,
        )
    }
}
