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
    private val repository by lazy { BackgroundServiceController.repository(this) }

    override fun onStartListening() {
        super.onStartListening()
        observation?.cancel()
        observation = scope.launch {
            repository.state.collect { state ->
                qsTile?.apply {
                    label = "AppTwin 背景服務"
                    this.state = when {
                        !state.tileInteractive -> Tile.STATE_UNAVAILABLE
                        state.loaded && state.enabled -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = state.tileDescription
                    }
                    contentDescription = "$label，${state.tileDescription}"
                    updateTile()
                }
            }
        }
        repository.refresh()
    }

    override fun onClick() {
        super.onClick()
        val toggle = Runnable {
            val state = repository.state.value
            state.performTileClick(
                requestChange = { repository.setEnabled(it) },
                refresh = { repository.refresh() },
            )
            if (!state.loaded && !state.busy && state.error == null) {
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
