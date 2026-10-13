package org.apptwin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import org.apptwin.groups.GroupHealth
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.runtime.RuntimeLaunchResult

/** One coordinator per ViewModel scope owns catalog, launch serialization and recovery fences. */
internal class MainSessionCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val launchDispatcher: CoroutineDispatcher,
    private val cloneOperations: CloneOperations,
    private val backgroundOperations: BackgroundRecoveryOperations,
    private val showMessage: (String) -> Unit,
    private val appendMessage: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(MainSessionUiState())
    val states = mutableState.asStateFlow()
    val state: MainSessionUiState get() = mutableState.value
    fun update(transform: (MainSessionUiState) -> MainSessionUiState) { mutableState.update(transform) }
    var refreshGeneration = 0L
    var launchBlockedByRecovery = true
    var recoveryInProgress = false
    val appLaunchMutex = Mutex()
    var pendingLaunch: PendingLaunch? = null
    fun invalidateRefresh() { ++refreshGeneration }

    fun launchGroupApp(item: GroupAppItem, refresh: () -> Unit) {
        tryLaunchGroupApp(item, refresh)
    }

    fun launchGroupApp(groupId: String, packageName: String, isOnboardingVisible: Boolean, isRefreshing: Boolean, refresh: () -> Unit) {
        if (groupId.isBlank() || packageName.isBlank()) {
            showMessage("捷徑資料無效；請從 AppTwin 重新建立捷徑")
            return
        }
        if (isOnboardingVisible) {
            pendingLaunch = PendingLaunch(groupId, packageName)
            return
        }
        // Recovery may mutate install state, so shortcuts wait for that bounded phase. Once
        // it finishes, exact-package launch may proceed without waiting for enrichment.
        if (launchBlockedByRecovery) {
            pendingLaunch = PendingLaunch(groupId, packageName)
            return
        }
        val item = state.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.groupId == groupId && it.app.packageName == packageName }
        if (item != null) {
            if (item.lifecycle in setOf(CloneLifecycleState.READY, CloneLifecycleState.PREPARING)) {
                tryLaunchGroupApp(item, refresh)
            } else {
                showUnavailableShortcutMessage(item)
            }
        } else {
            pendingLaunch = PendingLaunch(groupId, packageName)
            if (!isRefreshing) refresh()
        }
    }

    fun tryLaunchGroupApp(item: GroupAppItem, refresh: () -> Unit): LaunchAttempt {
        if (isLaunchBlocked(item.groupId)) return LaunchAttempt.BUSY
        if (item.groupHealth != GroupHealth.HEALTHY) {
            showMessage(
                if (item.groupHealth == GroupHealth.DAMAGED) {
                    "「${item.groupName}」的隔離環境已損毀"
                } else {
                    "「${item.groupName}」目前無法啟動 App"
                },
            )
            return LaunchAttempt.REJECTED
        }
        if (!item.canAttemptLaunch) {
            showMessage("來源 App 已移除，暫時無法啟動")
            return LaunchAttempt.REJECTED
        }
        if (!appLaunchMutex.tryLock()) return LaunchAttempt.BUSY
        // A launch owns the foreground path. Older enrichment may keep running on its
        // background lane, but its snapshot must not overwrite state after launch begins.
        refreshGeneration += 1
        update { it.copy(launchingAppKey = item.launchKey) }
        scope.launch {
            var operationCompleted = false
            try {
                val result = operationResult {
                    withContext(launchDispatcher) { cloneOperations.launchGroupApp(item) }
                }.getOrElse { error -> RuntimeLaunchResult.Failed(error.operationMessage(), error) }
                operationCompleted = true
                when (result) {
                    is RuntimeLaunchResult.Started ->
                        showMessage("${item.appLabel} 已從「${item.groupName}」啟動")
                    RuntimeLaunchResult.PackageRepairRequired ->
                        showMessage("分身套件需要重新同步，請稍後重試")
                    is RuntimeLaunchResult.Failed -> showMessage("啟動失敗：${result.reason}")
                }
            } finally {
                update { it.copy(launchingAppKey = null) }
                appLaunchMutex.unlock()
                if (operationCompleted && currentCoroutineContext().isActive) refresh()
            }
        }
        return LaunchAttempt.STARTED
    }

    fun clearMatchingPendingLaunch(groupId: String, packageName: String) {
        pendingLaunch = pendingLaunch?.takeUnless { pending ->
            pending.packageName == packageName &&
                (pending.groupId == null || pending.groupId == groupId)
        }
    }

    fun launchFirst(packageName: String, refresh: () -> Unit) {
        val item = state.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.app.packageName == packageName }
        if (item != null && !launchBlockedByRecovery) {
            tryLaunchGroupApp(item, refresh)
        } else {
            pendingLaunch = PendingLaunch(null, packageName)
        }
    }

    fun showUnavailableShortcutMessage(item: GroupAppItem) {
        showMessage(
            "「${item.groupName}」的 ${item.appLabel} 分身目前不可使用；" +
                "請在 AppTwin 中修復",
        )
    }

    fun resolvePendingLaunch(groupItems: List<GroupItem>, refresh: () -> Unit) {
        val target = pendingLaunch ?: return
        val pending = groupItems.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { item ->
                item.app.packageName == target.packageName &&
                    (target.groupId == null || item.groupId == target.groupId)
            }
        when {
            pending == null && target.groupId != null -> {
                pendingLaunch = null
                showMessage("此捷徑對應的分身已不存在；請從 AppTwin 重新建立捷徑")
            }
            pending != null &&
                target.groupId != null &&
                pending.lifecycle !in setOf(
                    CloneLifecycleState.READY,
                    CloneLifecycleState.PREPARING,
                ) -> {
                pendingLaunch = null
                showUnavailableShortcutMessage(pending)
            }
            pending != null && tryLaunchGroupApp(pending, refresh) != LaunchAttempt.BUSY -> {
                pendingLaunch = null
            }
        }
    }

    fun isLaunchBlocked(groupId: String): Boolean =
        launchBlockedByRecovery ||
            state.launchingAppKey != null ||
            state.uninstallingAppKey != null ||
            state.repairingAppKey != null ||
            state.clearingStorageAppKey != null ||
            state.clearingStorageGroupId != null ||
            state.busyGroupId != null ||
            state.busyPackageName != null

    fun executeAddAndLaunch(groupId: String, app: AppItem, onAdded: () -> Unit, refresh: () -> Unit) {
        if (
            state.uninstallingAppKey != null ||
            state.busyPackageName != null ||
            state.clearingStorageGroupId != null ||
            state.launchingAppKey != null
        ) return
        if (!appLaunchMutex.tryLock()) return
        update { it.copy(busyPackageName = app.entry.packageName) }
        scope.launch {
            var appWasAdded = false
            var cancelled = false
            try {
                val group = operationResult {
                    withContext(ioDispatcher) {
                        cloneOperations.addAppToGroup(groupId, app.entry.packageName)
                    }
                }.getOrElse { error ->
                    showMessage(error.operationMessage())
                    return@launch
                }
                appWasAdded = true
                onAdded()
                clearMatchingPendingLaunch(groupId, app.entry.packageName)
                val groupApp = group.apps.firstOrNull {
                    it.packageName == app.entry.packageName
                }
                if (groupApp == null) {
                    showMessage(
                        "已將 ${app.entry.label} 加入「${group.name}」，但找不到分身資料；" +
                            "分身已保留，請重新整理後再試",
                    )
                    return@launch
                }
                val item = GroupAppItem(
                    groupId = group.id,
                    groupName = group.name,
                    groupHealth = group.health,
                    app = groupApp,
                    appLabel = app.entry.label,
                    versionName = app.entry.versionName,
                    sourceInstalled = true,
                    launchStatus = "準備中",
                )
                update { it.copy(launchingAppKey = item.launchKey) }
                val launchResult = operationResult {
                    withContext(launchDispatcher) { cloneOperations.launchGroupApp(item) }
                }.getOrElse { error -> RuntimeLaunchResult.Failed(error.operationMessage(), error) }
                clearMatchingPendingLaunch(groupId, app.entry.packageName)
                when (launchResult) {
                    is RuntimeLaunchResult.Started ->
                        showMessage("已將 ${app.entry.label} 加入「${group.name}」並啟動")
                    RuntimeLaunchResult.PackageRepairRequired ->
                        showMessage("分身套件需要重新同步，請稍後重試")
                    is RuntimeLaunchResult.Failed -> showMessage(
                        "已將 ${app.entry.label} 加入「${group.name}」，但自動啟動失敗：" +
                            "${launchResult.reason}；分身已保留，可稍後重試",
                    )
                }
            } catch (cancellation: CancellationException) {
                cancelled = true
                throw cancellation
            } finally {
                update { it.copy(busyPackageName = null, launchingAppKey = null) }
                appLaunchMutex.unlock()
                if (appWasAdded && !cancelled && currentCoroutineContext().isActive) refresh()
            }
        }
    }

    fun reconcile(
        onStart: () -> Unit,
        loadAndPublishGroups: suspend (Long) -> MainGroupSnapshot?,
        applyFullRefresh: suspend (Long, MainGroupSnapshot) -> Unit,
        validatePickerSelection: (List<GroupItem>) -> Unit,
        refresh: () -> Unit,
    ) {
        if (recoveryInProgress) return
        recoveryInProgress = true
        val generation = ++refreshGeneration
        onStart()
        scope.launch {
            try {
                // Do not make the home screen wait for recovery work or GMS network reconciliation.
                // The index is durable local metadata and is safe to present while enrichment continues.
                loadAndPublishGroups(generation) ?: return@launch
                val groupResult = withContext(ioDispatcher) {
                    operationResult { backgroundOperations.reconcileGroups() }
                }
                val (appRemovalResult, applicationOperationResult) = withContext(ioDispatcher) {
                    operationResult { backgroundOperations.reconcileAppRemovals() } to
                        operationResult { backgroundOperations.reconcileApplicationOperations() }
                }
                val notices = buildList {
                    groupResult.onSuccess { result ->
                        if (result.loadIssues.isNotEmpty()) {
                            add("偵測到 ${result.loadIssues.size} 筆群組資料無法讀取；原始資料已保留")
                        }
                    }.onFailure { error ->
                        add("部分群組環境需要處理：${error.operationMessage()}")
                    }
                    appRemovalResult.onFailure { error ->
                        add("部分 App 解除安裝作業需要處理：${error.operationMessage()}")
                    }
                    applicationOperationResult.onFailure { error ->
                        add("部分分身空間作業需要處理：${error.operationMessage()}")
                    }
                }
                if (notices.isNotEmpty()) appendMessage(notices.joinToString("\n"))
                val refreshedGroups = loadAndPublishGroups(generation)
                if (refreshedGroups != null) {
                    // Reconciliation may change clone membership. Only expose the launch lane
                    // after its post-recovery index has been loaded and published successfully.
                    launchBlockedByRecovery = false
                    resolvePendingLaunch(state.groups, refresh)
                    applyFullRefresh(generation, refreshedGroups)
                }
                val gmsResult = withContext(ioDispatcher) { operationResult { backgroundOperations.reconcileGms() } }
                gmsResult.onFailure { error ->
                    appendMessage("Google 服務相容資料需要處理：${error.operationMessage()}")
                }.onSuccess { result ->
                    if (result.productStates.isNotEmpty()) {
                        val updatedGroups = state.groups.map { group ->
                            result.productStates[group.groupId]?.let { productState ->
                                group.copy(gmsCompatibility = productState)
                            } ?: group
                        }
                        validatePickerSelection(updatedGroups)
                        update { it.copy(groups = updatedGroups) }
                    }
                    if (result.cloudMessagingRepairFailures.isNotEmpty()) {
                        appendMessage("Google 背景通知服務需要重試")
                    }
                }
            } finally {
                recoveryInProgress = false
            }
        }
    }
    fun launchDeepLink(item: GroupAppItem, uri: String, beforeLaunch: () -> Unit, refresh: () -> Unit) {
        if (isLaunchBlocked(item.groupId) || !item.sourceInstalled) return
        if (!appLaunchMutex.tryLock()) return
        beforeLaunch()
        invalidateRefresh()
        update { it.copy(launchingAppKey = item.launchKey) }
        scope.launch {
            var operationCompleted = false
            try {
                val result = operationResult {
                    withContext(launchDispatcher) { cloneOperations.launchDeepLink(item, uri) }
                }.getOrElse { error -> RuntimeLaunchResult.Failed(error.operationMessage(), error) }
                operationCompleted = true
                when (result) {
                    is RuntimeLaunchResult.Started -> Unit
                    RuntimeLaunchResult.PackageRepairRequired -> showMessage("分身套件需要重新同步，請稍後重試")
                    is RuntimeLaunchResult.Failed -> showMessage("連結開啟失敗：${result.reason}")
                }
            } finally {
                update { it.copy(launchingAppKey = null) }
                appLaunchMutex.unlock()
                if (operationCompleted && currentCoroutineContext().isActive) refresh()
            }
        }
    }

}

internal data class PendingLaunch(val groupId: String?, val packageName: String)
internal enum class LaunchAttempt { STARTED, BUSY, REJECTED }
internal fun Throwable.operationMessage(): String = message ?: javaClass.simpleName

/** Cancellation is control flow, never a user-visible operation failure or recovery notice. */
internal inline fun <T> operationResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    Result.failure(error)
}
