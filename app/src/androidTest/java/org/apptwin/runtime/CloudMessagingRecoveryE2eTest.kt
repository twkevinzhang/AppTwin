package org.apptwin.runtime

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.remote.TrustedGmsCloudMessagingState
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
 * or handles a message payload. Killing the existing guest GmsCore process is separately opt-in:
 * pass `-e cloudMessagingKillRecovery true` to exercise bounded process-death recovery.
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
        val manager = VActivityManager.get()

        val accepted = manager.ensureTrustedGmsCloudMessagingForUser(existingUserId)
        val ensured = awaitState(existingUserId, ENSURED_PHASES, ENSURE_TIMEOUT_MS)
        assertTrue(
            "ensure must be accepted or expose a truthful degraded state",
            accepted || ensured.phase == TrustedGmsCloudMessagingState.PHASE_DEGRADED,
        )
        assertTrue("ensure must leave Cloud Messaging in an observable active phase",
            ensured.phase in ENSURED_PHASES)

        val arguments = InstrumentationRegistry.getArguments()
        if (!arguments.getString(KILL_RECOVERY_ARGUMENT).toBoolean()) return

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
        const val KILL_RECOVERY_ARGUMENT = "cloudMessagingKillRecovery"
        const val ENSURE_TIMEOUT_MS = 5_000L
        const val RECOVERY_TIMEOUT_MS = 25_000L
        const val POLL_INTERVAL_MS = 250L
        val ENSURED_PHASES = setOf(
            TrustedGmsCloudMessagingState.PHASE_STARTING,
            TrustedGmsCloudMessagingState.PHASE_CONNECTED,
            TrustedGmsCloudMessagingState.PHASE_DEGRADED,
        )
        val RECOVERING_PHASES = ENSURED_PHASES
    }
}
