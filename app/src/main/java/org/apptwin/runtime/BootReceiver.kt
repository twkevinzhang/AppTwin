package org.apptwin.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lody.virtual.client.stub.DaemonService

/** Restores only durable, user-enabled background notification workloads after boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val enabledUsers = DaemonWorkloadAuthorization
            .loadEnabledVirtualUserIds(context.applicationContext)
            ?: run {
                // Unreadable durable state is not authorization to start background work.
                return
            }
        val hasEnabledProfile = enabledUsers.isNotEmpty()
        val recoveryAllowed = hasEnabledProfile &&
            DaemonService.allowsAutomaticRecovery(context.applicationContext)
        if (!BootRecoveryPolicy.shouldStart(
                action = intent?.action,
                expectedBootAction = Intent.ACTION_BOOT_COMPLETED,
                hasEnabledProfile = hasEnabledProfile,
                recoveryAllowed = recoveryAllowed,
            )
        ) {
            return
        }

        // This entry point deliberately does not clear explicit-stop suppression or record a
        // visible launch. DaemonService repeats the durable gate before starting the FGS.
        DaemonService.startupForBackgroundRecovery(context.applicationContext, enabledUsers)
    }
}

internal object BootRecoveryPolicy {
    fun shouldStart(
        action: String?,
        expectedBootAction: String,
        hasEnabledProfile: Boolean,
        recoveryAllowed: Boolean,
    ): Boolean = action == expectedBootAction && hasEnabledProfile && recoveryAllowed
}
