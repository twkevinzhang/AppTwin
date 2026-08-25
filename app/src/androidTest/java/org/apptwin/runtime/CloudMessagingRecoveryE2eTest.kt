package org.apptwin.runtime

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.remote.TrustedGmsCloudMessagingState
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Non-destructive Cloud Messaging recovery probe for an existing AppTwin Group.
 *
 * This test never creates/deletes a Group, clears app data, reinstalls microG/LINE, reads a token,
 * or handles a message payload. Killing the guest GmsCore process is separately opt-in via
 * `-e cloudMessagingKillRecovery true`. The non-destructive healthy-session probe is separately
 * opt-in via `-e cloudMessagingReconnectProbe true`; it asks microG's own TriggerReceiver to
 * evaluate the current MCS session and requires the supervisor to remain CONNECTED throughout.
 */
@RunWith(AndroidJUnit4::class)
class CloudMessagingRecoveryE2eTest {
    @Test
    fun ensureAndObserveExistingTrustedCloudMessaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val userId = FileGroupStore(context).loadSnapshot().groups
            .asSequence()
            .mapNotNull { it.environmentBinding?.internalId }
            .firstOrNull { virtualUserId ->
                virtualUserId > 0 &&
                    VirtualCore.get().isAppInstalledAsUser(virtualUserId, GMS_PACKAGE)
            }
        assumeTrue("requires an existing Group with the trusted microG package", userId != null)
        val existingUserId = requireNotNull(userId)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            startVisibleDaemonSession(scenario)
            val manager = VActivityManager.get()

            val accepted = manager.ensureTrustedGmsCloudMessagingForUser(existingUserId)
            val ensured = awaitState(existingUserId, ENSURED_PHASES, ENSURE_TIMEOUT_MS)
            assertTrue(
                "ensure must be accepted or expose a truthful degraded state",
                accepted || ensured.phase == TrustedGmsCloudMessagingState.PHASE_DEGRADED,
            )
            assertTrue(
                "ensure must leave Cloud Messaging in an observable active phase",
                ensured.phase in ENSURED_PHASES,
            )

            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString(RECONNECT_PROBE_ARGUMENT).toBoolean()) {
                observeMicrogReconnectDecision(manager, existingUserId)
            }
            if (!arguments.getString(KILL_RECOVERY_ARGUMENT).toBoolean()) return@use

            VirtualCore.get().killApp(GMS_PACKAGE, existingUserId)
            val recovered = awaitState(
                existingUserId,
                RECOVERING_PHASES,
                RECOVERY_TIMEOUT_MS,
            ) { state ->
                state.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED ||
                    state.processAlive || state.bindingAlive || state.retryAttempt > 0
            }
            assertNotEquals(
                "process death recovery must not disable the per-user supervisor",
                TrustedGmsCloudMessagingState.PHASE_DISABLED,
                recovered.phase,
            )
            assertTrue(
                "recovery must restart, reconnect, or schedule a bounded retry",
                recovered.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED ||
                    recovered.processAlive || recovered.bindingAlive || recovered.retryAttempt > 0,
            )
        }
    }

    private fun observeMicrogReconnectDecision(manager: VActivityManager, userId: Int) {
        awaitState(
            userId,
            setOf(TrustedGmsCloudMessagingState.PHASE_CONNECTED),
            RECONNECT_READY_TIMEOUT_MS,
        )

        manager.sendBroadcast(
            Intent(MCS_RECONNECT_ACTION).setComponent(
                ComponentName(GMS_PACKAGE, GMS_TRIGGER_RECEIVER),
            ),
            userId,
        )

        val deadline = SystemClock.uptimeMillis() + RECONNECT_OBSERVE_MS
        var latest = manager.getTrustedGmsCloudMessagingState(userId)
        while (SystemClock.uptimeMillis() < deadline) {
            assertTrue(
                "healthy microG reconnect evaluation must not downgrade the supervisor; " +
                    "phase=${latest.phase}, failure=${latest.failureCode}",
                latest.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED,
            )
            SystemClock.sleep(POLL_INTERVAL_MS)
            latest = manager.getTrustedGmsCloudMessagingState(userId)
        }
        assertTrue(
            "healthy microG reconnect evaluation must finish CONNECTED; " +
                "phase=${latest.phase}, failure=${latest.failureCode}",
            latest.phase == TrustedGmsCloudMessagingState.PHASE_CONNECTED,
        )
    }

    private fun startVisibleDaemonSession(scenario: ActivityScenario<MainActivity>) {
        var observedReopenEpoch = -1L
        scenario.onActivity { activity ->
            observedReopenEpoch = DaemonWorkloadAuthorization.startFromVisibleHost(activity)
        }
        assertTrue(
            "MainActivity must establish and reopen the visible foreground daemon session",
            VActivityManager.get().awaitDaemonWorkloadGateOpenAfter(
                observedReopenEpoch,
                FOREGROUND_SESSION_TIMEOUT_MS,
            ),
        )
    }

    private fun awaitState(
        userId: Int,
        acceptedPhases: Set<Int>,
        timeoutMillis: Long,
        additionalCondition: (TrustedGmsCloudMessagingState) -> Boolean = { true },
    ): TrustedGmsCloudMessagingState {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var latest = VActivityManager.get().getTrustedGmsCloudMessagingState(userId)
        while (
            SystemClock.uptimeMillis() < deadline &&
            (latest.phase !in acceptedPhases || !additionalCondition(latest))
        ) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            latest = VActivityManager.get().getTrustedGmsCloudMessagingState(userId)
        }
        assertTrue(
            "timed out waiting for an accepted Cloud Messaging phase; latest=${latest.phase}",
            latest.phase in acceptedPhases && additionalCondition(latest),
        )
        return latest
    }

    private companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val GMS_TRIGGER_RECEIVER = "org.microg.gms.gcm.TriggerReceiver"
        const val MCS_RECONNECT_ACTION = "org.microg.gms.gcm.mcs.RECONNECT"
        const val KILL_RECOVERY_ARGUMENT = "cloudMessagingKillRecovery"
        const val RECONNECT_PROBE_ARGUMENT = "cloudMessagingReconnectProbe"
        const val ENSURE_TIMEOUT_MS = 5_000L
        const val FOREGROUND_SESSION_TIMEOUT_MS = 5_000L
        const val RECOVERY_TIMEOUT_MS = 25_000L
        const val RECONNECT_READY_TIMEOUT_MS = 30_000L
        const val RECONNECT_OBSERVE_MS = 35_000L
        const val POLL_INTERVAL_MS = 250L
        val ENSURED_PHASES = setOf(
            TrustedGmsCloudMessagingState.PHASE_STARTING,
            TrustedGmsCloudMessagingState.PHASE_CONNECTED,
            TrustedGmsCloudMessagingState.PHASE_DEGRADED,
        )
        val RECOVERING_PHASES = ENSURED_PHASES
    }
}
