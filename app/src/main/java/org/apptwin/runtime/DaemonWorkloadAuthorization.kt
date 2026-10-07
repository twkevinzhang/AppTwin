package org.apptwin.runtime

import android.content.Context
import com.lody.virtual.client.env.BackgroundExecutionSettings
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.stub.DaemonService
import java.io.File
import java.util.UUID
import org.apptwin.gms.FileGmsProfileRepository
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.GroupHealth

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
        val healthyGroups = groups.groups.filter { it.health == GroupHealth.HEALTHY }
        val healthByGroupId = groups.groups.associate { it.id to it.health }
        val bindingsByGroupId = healthyGroups.associate { group ->
            group.id to group.environmentBinding?.internalId
        }
        resolveEnabledVirtualUserIds(
            durableProfiles
                .asSequence()
                .filter { profile -> profile.desiredState == GmsDesiredState.ENABLED && allowsGroupWorkload(profile.groupId.value, healthByGroupId) }
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
    internal fun allowsGroupWorkload(groupId: String, healthByGroupId: Map<String, GroupHealth>): Boolean =
        healthByGroupId[groupId]?.let { it == GroupHealth.HEALTHY } ?: true

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
        if (!BackgroundExecutionSettings.isEnabled(appContext)) {
            visibleAuthorization = null
            return
        }
        android.util.Log.i("AppTwinEngine", "daemon-visible-start enabled=true")
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
    /** Wait for the exact new token rather than requiring an already-open gate to reopen. */
    fun awaitVisibleSessionReady(context: Context, timeoutMillis: Long): Boolean {
        require(timeoutMillis in 0..5_000L)
        val expected = visibleAuthorization ?: return false
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (visibleAuthorization === expected) {
            if (isVisibleSessionReady(context)) return visibleAuthorization === expected
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            try { Thread.sleep(minOf(25L, remaining)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return false
    }

    fun isVisibleSessionReady(context: Context): Boolean {
        if (!BackgroundExecutionSettings.isEnabled(context)) return false
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
