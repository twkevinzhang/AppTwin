package org.apptwin.runtime

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.lody.virtual.client.env.BackgroundExecutionSettings
import com.lody.virtual.client.ipc.VActivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Both entry points render the same durable runtime policy, never a local optimistic toggle. */
data class BackgroundServiceState(
    val enabled: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val failedRequestedEnabled: Boolean? = null,
) {
    fun withLoadedSetting(enabled: Boolean): BackgroundServiceState = copy(
        enabled = enabled,
        loaded = true,
        // A successful read recovers a read error, but cannot prove a failed toggle recovered.
        error = if (failedRequestedEnabled != null) error else null,
    )

    val tileInteractive: Boolean get() = !busy && (loaded || error != null)
    val tileDescription: String get() = when {
        busy -> "切換中…"
        error != null && failedRequestedEnabled != null -> "切換失敗，點擊重試"
        error != null -> "讀取失敗，點擊重試"
        !loaded -> "讀取中…"
        enabled -> "已開啟"
        else -> "已關閉"
    }

    fun performTileClick(requestChange: (Boolean) -> Unit, refresh: () -> Unit) {
        when {
            busy -> Unit
            error != null -> retry(requestChange, refresh)
            loaded -> requestChange(!enabled)
            else -> refresh()
        }
    }

    fun retry(requestChange: (Boolean) -> Unit, refresh: () -> Unit) {
        val requested = failedRequestedEnabled
        if (requested == null) refresh() else requestChange(requested)
    }
}

internal object BackgroundServiceController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(BackgroundServiceState())
    val state = mutableState.asStateFlow()

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            mutations.withLock {
                runCatching { withContext(Dispatchers.IO) { BackgroundExecutionSettings.isEnabled(app) } }
                    .onSuccess { enabled ->
                        val previous = mutableState.value
                        mutableState.value = previous.withLoadedSetting(enabled)
                    }
                    .onFailure {
                        mutableState.value = mutableState.value.copy(error = "無法讀取背景服務設定，請重試。")
                    }
            }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (mutableState.value.busy) return
        val app = context.applicationContext
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        // App-owned scope lets an explicit tile action complete even when its TileService unbinds.
        scope.launch {
            mutations.withLock {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        VActivityManager.get().setBackgroundExecutionEnabled(enabled)
                        check(BackgroundExecutionSettings.isEnabled(app) == enabled)
                        if (enabled) DaemonWorkloadAuthorization.startFromVisibleHost(app)
                    }
                }
                val actual = runCatching {
                    withContext(Dispatchers.IO) { BackgroundExecutionSettings.isEnabled(app) }
                }
                val failed = result.isFailure || actual.isFailure
                mutableState.value = BackgroundServiceState(
                    enabled = actual.getOrDefault(mutableState.value.enabled),
                    loaded = actual.isSuccess,
                    failedRequestedEnabled = if (failed) enabled else null,
                    error = if (failed) {
                        "背景服務切換未完整完成，請重試；開關顯示目前儲存的設定。"
                    } else null,
                )
                runCatching {
                    TileService.requestListeningState(app, ComponentName(app, BackgroundServiceTile::class.java))
                }
            }
        }
    }
}
