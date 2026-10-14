package org.apptwin.runtime

import android.content.Context

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

/** Compatibility entry point; state ownership lives in the application container. */
internal object BackgroundServiceController {
    val state: kotlinx.coroutines.flow.StateFlow<BackgroundServiceState>
        get() = repository(com.lody.virtual.client.core.VirtualCore.get().context).state

    fun repository(context: Context): BackgroundServiceRepository =
        (context.applicationContext as org.apptwin.AppTwinApplication).mainContainer.backgroundServiceRepository

    fun refresh(context: Context) = repository(context).refresh()
    fun setEnabled(context: Context, enabled: Boolean) = repository(context).setEnabled(enabled)
}
