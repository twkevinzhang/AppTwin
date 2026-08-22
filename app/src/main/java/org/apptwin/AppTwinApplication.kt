package org.apptwin

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.lody.virtual.client.NativeEngine
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.isolated.IsolatedWorkerProcess
import com.lody.virtual.client.stub.VASettings
import org.apptwin.crash.CrashReporting

/** Boots the GPL virtual runtime before Android creates any host or guest component. */
class AppTwinApplication : Application() {
    private var isolatedWorker = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        isolatedWorker = IsolatedWorkerProcess.isCurrentProcess(base)
        if (isolatedWorker) {
            Log.i(TAG, "Skipping VirtualCore bootstrap in isolated worker")
            return
        }
        runCatching {
            NativeEngine.disableJit(Build.VERSION.SDK_INT)
            VASettings.ENABLE_IO_REDIRECT = true
            VASettings.ENABLE_INNER_SHORTCUT = false
            VirtualCore.get().apply {
                startup(base)
                setTaskDescriptionDelegate { description ->
                    ActivityManager.TaskDescription(
                        CloneTaskDescriptionPolicy.RECENT_TASK_LABEL,
                        description.icon,
                        description.primaryColor,
                    )
                }
            }
        }.onFailure { Log.e(TAG, "VirtualCore startup failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        if (isolatedWorker) return
        CrashReporting.initialize(this, VirtualCore.get())
        runCatching {
            VirtualCore.get().initialize(object : VirtualCore.VirtualInitializer() {})
        }.onFailure { Log.e(TAG, "VirtualCore initialization failed", it) }
    }

    private companion object {
        const val TAG = "AppTwinRuntime"
    }
}

/** Keeps clone cards identifiable in Android's recent-apps overview without changing guest labels. */
internal object CloneTaskDescriptionPolicy {
    const val RECENT_TASK_LABEL = "AppTwin"
}
