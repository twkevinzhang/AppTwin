package org.apptwin.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.env.BackgroundExecutionSettings
import com.lody.virtual.client.ipc.VActivityManager
import org.apptwin.MainActivity
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.apptwin.runtime.BackgroundServiceController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, existing-space-only production UI wiring. No data mutation, token or payload reads. */
@RunWith(AndroidJUnit4::class)
class SpaceBackgroundServiceE2eTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun existingSpaceCardUsesExactRuntimeAndGlobalPolicy() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in required", args.getString("spaceBackgroundCardProbe") == "1")
        val id = requireNotNull(args.getString("spaceBackgroundGroupId"))
        val userId = requireNotNull(args.getString("spaceBackgroundUserId")).toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val group = requireNotNull(FileGroupStore(context).find(id))
        assertEquals(userId, group.environmentBinding?.internalId)
        val manager = VActivityManager.get()
        val original = BackgroundExecutionSettings.isEnabled(context)
        var model: MainViewModel? = null
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[MainViewModel::class.java]; model!!.openSpaceDetails(id) }
        compose.waitUntil(20_000) { model?.uiState?.selectedSpaceId == id }
        try {
            compose.runOnUiThread { BackgroundServiceController.setEnabled(context, false) }
            compose.waitUntil(20_000) { BackgroundServiceController.state.value.loaded && !BackgroundServiceController.state.value.enabled && !BackgroundServiceController.state.value.busy }
            compose.onNodeWithTag("space-background-summary").assertTextEquals("已停用")
            compose.onNodeWithTag("space-background-retry").assertIsNotEnabled()
            assertFalse("global off must reject per-space retry", manager.retryTrustedGmsCloudMessagingForUser(userId))
            compose.runOnUiThread { BackgroundServiceController.setEnabled(context, true) }
            compose.waitUntil(20_000) { BackgroundServiceController.state.value.enabled && !BackgroundServiceController.state.value.busy }
            val oldObservation = model!!.uiState.spaceBackgroundHealth?.checkedAtMillis ?: 0L
            compose.runOnUiThread { model!!.refreshSpaceBackgroundHealth() }
            compose.waitUntil(20_000) { model?.uiState?.spaceBackgroundHealth?.let { it.groupId == id && it.checkedAtMillis > oldObservation } == true }
            val health = model!!.uiState.spaceBackgroundHealth!!
            android.util.Log.i("SpaceBackgroundCardE2E", "retry_exercised=${health.retryable}")
            if (health.retryable) {
                val otherUsers = FileGroupStore(context).listAll().mapNotNull { it.environmentBinding?.internalId }.filter { it != userId }
                val before = otherUsers.associateWith { manager.getTrustedGmsCloudMessagingState(it).generation }
                compose.onNodeWithTag("space-background-retry").performClick()
                compose.waitUntil(50_000) { !model!!.uiState.isSpaceBackgroundRetrying && model!!.uiState.spaceBackgroundHealth!!.checkedAtMillis > health.checkedAtMillis }
                assertEquals(id, model!!.uiState.spaceBackgroundHealth?.groupId)
                before.forEach { (other, generation) -> assertEquals("retry must not mutate user $other", generation, manager.getTrustedGmsCloudMessagingState(other).generation) }
            }
            val state = manager.getTrustedGmsCloudMessagingState(userId)
            android.util.Log.i("SpaceBackgroundCardE2E", "user=$userId phase=${state.phase} tcp=${state.tcpState} process=${state.processAlive} binding=${state.bindingAlive}")
            if (model!!.uiState.spaceBackgroundHealth!!.healthy) {
                compose.onNodeWithTag("space-background-summary").assertTextEquals("正常")
            } else {
                compose.onNodeWithTag("space-background-summary").assert(!hasText("正常"))
            }
        } finally {
            compose.runOnUiThread { BackgroundServiceController.setEnabled(context, original) }
            compose.waitUntil(20_000) { BackgroundServiceController.state.value.enabled == original && !BackgroundServiceController.state.value.busy }
        }
    }
    @Test fun stoppedMcsSocketIsDetectedAndCardRetryRestoresExactSpace() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("disconnect probe requires separate explicit authorization", args.getString("spaceBackgroundDisconnectProbe") == "1")
        val id = requireNotNull(args.getString("spaceBackgroundGroupId"))
        val userId = requireNotNull(args.getString("spaceBackgroundUserId")).toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = FileGroupStore(context)
        assertEquals(userId, requireNotNull(store.find(id)).environmentBinding?.internalId)
        assertTrue("background must already be enabled", BackgroundExecutionSettings.isEnabled(context))
        val manager = VActivityManager.get()
        var model: MainViewModel? = null
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            model!!.openSpaceDetails(id)
            BackgroundServiceController.refresh(context)
        }
        compose.waitUntil(20_000) { BackgroundServiceController.state.value.enabled && BackgroundServiceController.state.value.loaded && !BackgroundServiceController.state.value.busy }
        val baselineDeadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        var before = manager.getTrustedGmsCloudMessagingState(userId)
        fun healthyBaseline(state: com.lody.virtual.remote.TrustedGmsCloudMessagingState) =
            state.mcsSessionVerified && state.tcpState == com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_CONNECTED &&
                state.phase == com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_CONNECTED
        while (!healthyBaseline(before) && android.os.SystemClock.elapsedRealtime() < baselineDeadline - 2_000L) {
            android.os.SystemClock.sleep(minOf(500L, (baselineDeadline - android.os.SystemClock.elapsedRealtime() - 2_000L).coerceAtLeast(1L)))
            before = manager.getTrustedGmsCloudMessagingState(userId)
        }
        android.util.Log.i("SpaceBackgroundCardE2E", "fault_injected=false baseline_phase=${before.phase} baseline_tcp=${before.tcpState} baseline_session_verified=${before.mcsSessionVerified}")
        assertTrue("baseline_not_healthy: no fault injected", healthyBaseline(before))
        val otherUsers = store.listAll().mapNotNull { it.environmentBinding?.internalId }.filter { it != userId }
        val otherGenerations = otherUsers.associateWith { manager.getTrustedGmsCloudMessagingState(it).generation }
        var faultInjected = false
        var recovered = false
        var hardDeadline = 0L
        fun remainingMillis(limit: Long): Long = minOf(limit, (hardDeadline - android.os.SystemClock.elapsedRealtime() - 2_000L).coerceAtLeast(1L))
        try {
            val service = android.content.Intent().setComponent(android.content.ComponentName("com.google.android.gms", "org.microg.gms.gcm.McsService"))
            hardDeadline = android.os.SystemClock.elapsedRealtime() + 120_000L
            val stopResult = manager.service.stopService(null, service, null, userId)
            faultInjected = true
            android.util.Log.i("SpaceBackgroundCardE2E", "exact_user=$userId stop_result=$stopResult")
            val faultDeadline = hardDeadline - 100_000L
            var fault = manager.getTrustedGmsCloudMessagingState(userId)
            while (fault.tcpState != com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_DISCONNECTED && android.os.SystemClock.elapsedRealtime() < faultDeadline - 2_000L) {
                android.os.SystemClock.sleep(minOf(500L, (faultDeadline - android.os.SystemClock.elapsedRealtime() - 2_000L).coerceAtLeast(1L)))
                fault = manager.getTrustedGmsCloudMessagingState(userId)
            }
            assertEquals("fault_not_observed: stopService may retain a bound MCS service", com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_DISCONNECTED, fault.tcpState)
            val oldObservation = model!!.uiState.spaceBackgroundHealth?.checkedAtMillis ?: 0L
            compose.runOnUiThread { model!!.refreshSpaceBackgroundHealth() }
            compose.waitUntil(minOf(remainingMillis(20_000L), (faultDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L))) { model!!.uiState.spaceBackgroundHealth?.let { it.groupId == id && it.checkedAtMillis > oldObservation && it.retryable } == true }
            compose.onNodeWithTag("space-background-summary").assert(!hasText("正常"))
            compose.onNodeWithTag("space-background-tcp").assertTextEquals("已中斷")
            compose.onNodeWithTag("space-background-retry").assertIsEnabled().performClick()
            compose.waitUntil(minOf(remainingMillis(50_000L), (hardDeadline - android.os.SystemClock.elapsedRealtime() - 42_000L).coerceAtLeast(1L))) { model!!.uiState.spaceBackgroundHealth?.healthy == true && !model!!.uiState.isSpaceBackgroundRetrying }
            val terminal = manager.getTrustedGmsCloudMessagingState(userId)
            assertTrue(terminal.mcsSessionVerified)
            assertEquals(com.lody.virtual.remote.TrustedGmsCloudMessagingState.PHASE_CONNECTED, terminal.phase)
            assertEquals(com.lody.virtual.remote.TrustedGmsCloudMessagingState.TCP_CONNECTED, terminal.tcpState)
            compose.onNodeWithTag("space-background-summary").assertTextEquals("正常")
            otherGenerations.forEach { (other, generation) -> assertEquals("exact retry must not mutate user $other", generation, manager.getTrustedGmsCloudMessagingState(other).generation) }
            recovered = true
            android.util.Log.i("SpaceBackgroundCardE2E", "retry_exercised=true exact_user=$userId terminal_connected=true session_verified=true")
        } finally {
            if (faultInjected && !recovered && hardDeadline - android.os.SystemClock.elapsedRealtime() > 4_000L) {
                val accepted = manager.retryTrustedGmsCloudMessagingForUser(userId)
                val cleanupDeadline = minOf(hardDeadline - 2_000L, android.os.SystemClock.elapsedRealtime() + 40_000L)
                var state = manager.getTrustedGmsCloudMessagingState(userId)
                while (!state.mcsSessionVerified && android.os.SystemClock.elapsedRealtime() < cleanupDeadline - 2_000L) {
                    android.os.SystemClock.sleep(minOf(1_000L, (cleanupDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
                    state = manager.getTrustedGmsCloudMessagingState(userId)
                }
                android.util.Log.i("SpaceBackgroundCardE2E", "cleanup_retry_accepted=$accepted exact_user=$userId session_verified=${state.mcsSessionVerified}")
            }
        }
    }

}
