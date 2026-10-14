package org.apptwin.runtime

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.lody.virtual.client.env.BackgroundExecutionSettings
import com.lody.virtual.client.ipc.VActivityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable policy shared by UI and Tile; observing it never authorizes background work. */
internal interface BackgroundServiceRepository {
    val state: StateFlow<BackgroundServiceState>
    fun refresh()
    fun setEnabled(enabled: Boolean)
}

internal interface BackgroundServiceBackend {
    suspend fun readEnabled(): Boolean
    suspend fun writeEnabled(enabled: Boolean)
    suspend fun startFromVisibleHost()
    fun notifyTile()
}

/** Its scope belongs to the application, so observer destruction cannot cancel explicit actions. */
internal class ApplicationBackgroundServiceRepository(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val backend: BackgroundServiceBackend,
) : BackgroundServiceRepository {
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(BackgroundServiceState())
    override val state = mutableState.asStateFlow()
    // Entry points run on Main. Stop invalidates queued enables before acquiring the mutation lock.
    @Volatile private var stopGeneration = 0L
    private var pendingMutations = 0

    override fun refresh() {
        scope.launch {
            mutations.withLock {
                attempt { withContext(ioDispatcher) { backend.readEnabled() } }
                    .onSuccess { mutableState.value = mutableState.value.withLoadedSetting(it) }
                    .onFailure { mutableState.value = mutableState.value.copy(error = "無法讀取背景服務設定，請重試。") }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled && mutableState.value.busy) return
        if (!enabled) stopGeneration++
        val generation = stopGeneration
        pendingMutations++
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        scope.launch {
            try {
                mutations.withLock {
                    if (enabled && generation != stopGeneration) return@withLock
                    val result = attempt {
                        withContext(ioDispatcher) {
                            backend.writeEnabled(enabled)
                            check(backend.readEnabled() == enabled)
                            // A stop arriving during I/O must not authorize a subsequent start.
                            if (enabled && generation == stopGeneration) backend.startFromVisibleHost()
                        }
                    }
                    val actual = attempt { withContext(ioDispatcher) { backend.readEnabled() } }
                    val failed = result.isFailure || actual.isFailure
                    mutableState.value = BackgroundServiceState(
                        enabled = actual.getOrDefault(mutableState.value.enabled),
                        loaded = actual.isSuccess,
                        busy = true,
                        failedRequestedEnabled = if (failed) enabled else null,
                        error = if (failed) "背景服務切換未完整完成，請重試；開關顯示目前儲存的設定。" else null,
                    )
                    attempt { backend.notifyTile() }
                }
            } finally {
                pendingMutations--
                mutableState.value = mutableState.value.copy(busy = pendingMutations > 0)
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

internal class AndroidBackgroundServiceBackend(context: Context) : BackgroundServiceBackend {
    private val app = context.applicationContext
    override suspend fun readEnabled(): Boolean = BackgroundExecutionSettings.isEnabled(app)
    override suspend fun writeEnabled(enabled: Boolean) { VActivityManager.get().setBackgroundExecutionEnabled(enabled) }
    override suspend fun startFromVisibleHost() { DaemonWorkloadAuthorization.startFromVisibleHost(app) }
    override fun notifyTile() { TileService.requestListeningState(app, ComponentName(app, BackgroundServiceTile::class.java)) }
}
