package org.apptwin.runtime

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** An explicit user control; listening or adding the tile must never enable background work. */
class BackgroundServiceTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observation?.cancel()
        observation = scope.launch {
            BackgroundServiceController.state.collect { state ->
                qsTile?.apply {
                    label = "AppTwin 背景服務"
                    this.state = when {
                        !state.loaded || state.busy -> Tile.STATE_UNAVAILABLE
                        state.enabled -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = when {
                            state.busy -> "切換中…"
                            state.error != null -> "切換失敗，請重試"
                            !state.loaded -> "讀取中…"
                            state.enabled -> "已開啟"
                            else -> "已關閉"
                        }
                    }
                    contentDescription = "$label，${if (state.enabled) "已開啟" else "已關閉"}"
                    updateTile()
                }
            }
        }
        BackgroundServiceController.refresh(this)
    }

    override fun onClick() {
        super.onClick()
        val toggle = Runnable {
            val state = BackgroundServiceController.state.value
            if (state.loaded && !state.busy) {
                BackgroundServiceController.setEnabled(this, !state.enabled)
            } else if (!state.busy) {
                BackgroundServiceController.refresh(this)
                Toast.makeText(this, "正在讀取設定，請稍後再試。", Toast.LENGTH_SHORT).show()
            }
        }
        if (isLocked) unlockAndRun(toggle) else toggle.run()
    }

    override fun onStopListening() {
        observation?.cancel()
        observation = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
