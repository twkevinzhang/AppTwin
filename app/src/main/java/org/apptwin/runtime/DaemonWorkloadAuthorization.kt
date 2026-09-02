package org.apptwin.runtime

import android.content.Context
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.stub.DaemonService
import java.io.File
import java.util.UUID
import org.apptwin.gms.FileGmsProfileRepository
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.groups.FileGroupStore

/** Exact durable GMS workload that the virtual-runtime daemon may recover. */
internal object DaemonWorkloadAuthorization {
    private data class VisibleAuthorization(
        val generation: String,
        val desiredUserIds: IntArray,
        val sessionToken: String,
    )

    @Volatile
    private var visibleAuthorization: VisibleAuthorization? = null

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

    fun observeReopenEpoch(): Long = VActivityManager.get().daemonWorkloadGateReopenEpoch

    @Synchronized
    fun startFromVisibleHost(context: Context) {
        val appContext = context.applicationContext
        val generationBefore = DaemonAuthorizationGeneration.current(appContext.filesDir)
        val enabledUsers = loadEnabledVirtualUserIds(context)
        val generationAfter = DaemonAuthorizationGeneration.current(appContext.filesDir)
        visibleAuthorization = null
        if (enabledUsers == null) {
            // Durable state could not be read completely. Preserve the runtime's last known exact
            // allowlist rather than authoritatively replacing it with an incomplete observation.
            DaemonService.startup(context)
        } else {
            val sessionToken = UUID.randomUUID().toString()
            DaemonService.startup(context, enabledUsers, sessionToken)
            if (
                generationBefore != null &&
                generationBefore == generationAfter &&
                DaemonAuthorizationGeneration.current(appContext.filesDir) == generationAfter
            ) {
                visibleAuthorization = VisibleAuthorization(
                    generation = generationAfter,
                    desiredUserIds = enabledUsers.clone(),
                    sessionToken = sessionToken,
                )
            }
        }
    }

    /**
     * Reuses only the exact authorization published by the current visible host session.
     * Any repository mutation, engine restart, closed gate, in-flight reconciliation, or changed
     * desired-user set makes the runtime reject the token and falls back to the full handshake.
     */
    fun isVisibleSessionReady(context: Context): Boolean {
        val authorization = visibleAuthorization ?: return false
        val appContext = context.applicationContext
        if (DaemonAuthorizationGeneration.current(appContext.filesDir) != authorization.generation) {
            visibleAuthorization = null
            return false
        }
        val ready = runCatching {
            VActivityManager.get().isDaemonLaunchReady(
                authorization.sessionToken,
                authorization.desiredUserIds.clone(),
            )
        }.getOrDefault(false)
        if (DaemonAuthorizationGeneration.current(appContext.filesDir) != authorization.generation) {
            visibleAuthorization = null
            return false
        }
        return ready
    }
}
