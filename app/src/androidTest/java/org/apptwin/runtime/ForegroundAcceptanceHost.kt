package org.apptwin.runtime

import android.app.ActivityManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.apptwin.MainActivity

/** Brings the real existing host task forward; never manufactures a resumed-host registry entry. */
@Suppress("DEPRECATION")
internal fun ActivityScenario<MainActivity>.awaitRealForegroundHost() {
    var expectedTask = -1
    lateinit var expectedActivity: MainActivity
    fun isReady(activity: MainActivity): Boolean {
        val topTask = activity.getSystemService(ActivityManager::class.java).getRunningTasks(1).firstOrNull()?.id
        return activity === expectedActivity && activity.taskId == expectedTask &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            activity.hasWindowFocus() && mainActivityLaunchHosts.current() === activity && topTask == expectedTask
    }
    onActivity { activity ->
        expectedActivity = activity
        expectedTask = activity.taskId
        if (!isReady(activity)) {
            // AppTask.moveToFront (API 21) models Recents; no new Intent or delayed onNewIntent.
            activity.getSystemService(ActivityManager::class.java).appTasks
                .single { task ->
                    val info = task.taskInfo
                    val id = if (android.os.Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
                    id == expectedTask
                }.moveToFront()
        }
    }
    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
    var consecutive = 0
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
        var ready = false
        onActivity { activity -> ready = isReady(activity) }
        consecutive = if (ready) consecutive + 1 else 0
        if (consecutive >= 3) return
        Thread.sleep(100)
    }
    error("Real MainActivity did not stably resume with exact foreground task and window focus")
}

/** Finish the exact host only after restoring its real foreground lifecycle. */
internal fun ActivityScenario<MainActivity>.closeRealForegroundHost(originalFailure: Throwable? = null) {
    var cleanupFailure: Throwable? = null
    try { awaitRealForegroundHost() } catch (error: Throwable) { cleanupFailure = error }
    try { close() } catch (error: Throwable) {
        if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
    }
    cleanupFailure?.let { error ->
        if (originalFailure != null) originalFailure.addSuppressed(error) else throw error
    }
}

internal inline fun <R> ActivityScenario<MainActivity>.useForegroundAcceptanceHost(
    block: (ActivityScenario<MainActivity>) -> R,
): R {
    var primary: Throwable? = null
    try { return block(this) }
    catch (error: Throwable) { primary = error; throw error }
    finally { closeRealForegroundHost(primary) }
}
