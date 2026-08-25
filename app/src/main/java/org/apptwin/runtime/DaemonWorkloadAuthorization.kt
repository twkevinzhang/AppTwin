package org.apptwin.runtime

import android.content.Context
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.stub.DaemonService
import java.io.File
import org.apptwin.gms.FileGmsProfileRepository
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.groups.FileGroupStore

/** Exact durable GMS workload that the virtual-runtime daemon may recover. */
internal object DaemonWorkloadAuthorization {
    fun loadEnabledVirtualUserIds(context: Context): IntArray? = runCatching {
        val appContext = context.applicationContext
        val durableGroupsRoot = File(appContext.filesDir, "groups")
        if (!isListableDurableRoot(durableGroupsRoot)) return null
        val profiles = FileGmsProfileRepository(appContext)
        val durableProfiles = profiles.list()
        if (profiles.warnings().isNotEmpty()) return null

        val groups = FileGroupStore(appContext).loadSnapshot()
        if (groups.issues.isNotEmpty()) return null
        val bindingsByGroupId = groups.groups.associate { group ->
            group.id to group.environmentBinding?.internalId
        }
        resolveEnabledVirtualUserIds(
            durableProfiles
                .asSequence()
                .filter { profile -> profile.desiredState == GmsDesiredState.ENABLED }
                .map { profile -> profile.groupId.value }
                .toSet(),
            bindingsByGroupId,
        )?.takeIf { isListableDurableRoot(durableGroupsRoot) }
    }.getOrNull()

    /** Missing means a legitimate empty installation; an existing unreadable/non-directory root
     * is never authoritative evidence that all desired workloads were removed. */
    internal fun isListableDurableRoot(root: File): Boolean =
        !root.exists() || (root.isDirectory && root.listFiles() != null)

    /**
     * A missing or host-user binding makes the whole observation unreliable. Returning null keeps
     * callers from accidentally replacing a known-good runtime allowlist with partial state.
     */
    internal fun resolveEnabledVirtualUserIds(
        enabledGroupIds: Set<String>,
        bindingsByGroupId: Map<String, Int?>,
    ): IntArray? {
        val resolved = enabledGroupIds.map { groupId ->
            bindingsByGroupId[groupId]?.takeIf { userId -> userId > 0 } ?: return null
        }
        return resolved.distinct().sorted().toIntArray()
    }

    fun startFromVisibleHost(context: Context): Long {
        // Capture before requesting the service. The waiter must observe the distinct reopen made
        // by this onStartCommand, not an OPEN state left by an older foreground session.
        val observedReopenEpoch = VActivityManager.get().daemonWorkloadGateReopenEpoch
        val enabledUsers = loadEnabledVirtualUserIds(context)
        if (enabledUsers == null) {
            // Durable state could not be read completely. Preserve the runtime's last known exact
            // allowlist rather than authoritatively replacing it with an incomplete observation.
            DaemonService.startup(context)
        } else {
            DaemonService.startup(context, enabledUsers)
        }
        return observedReopenEpoch
    }
}
