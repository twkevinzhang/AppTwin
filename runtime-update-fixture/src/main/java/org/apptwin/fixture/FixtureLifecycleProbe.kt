package org.apptwin.fixture

import android.app.Activity
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Public lifecycle metadata only; no application content or identifiers from real accounts. */
class FixtureLifecycleProbe(private val activity: Activity) {
    private val instance = UUID.randomUUID().toString()
    private var resumed = false
    private var focused = false
    private var saved = false
    private var count = 0
    fun created(savedInstance: Boolean, launchCount: Int) {
        saved = savedInstance; count = launchCount; write("CREATED")
    }
    fun resumed() { resumed = true; write("RESUMED") }
    fun paused() { resumed = false; write("PAUSED") }
    fun stopped() { write("STOPPED") }
    fun destroyed() { resumed = false; focused = false; write("DESTROYED") }
    fun focus(hasFocus: Boolean) { focused = hasFocus; write(if (resumed) "RESUMED" else "PAUSED") }
    @Synchronized private fun write(phase: String) {
        val config = activity.resources.configuration
        val metadata = JSONObject().put("instance", instance).put("pid", Process.myPid())
            .put("task", activity.taskId).put("launchCount", count).put("savedInstance", saved)
            .put("orientation", config.orientation).put("density", config.densityDpi)
            .put("uiMode", config.uiMode).put("phase", phase).put("resumed", resumed)
            .put("focused", focused).put("changingConfigurations", activity.isChangingConfigurations)
            .put("finishing", activity.isFinishing)
        val target = File(activity.filesDir, FILE_NAME)
        val temporary = File(activity.filesDir, "$FILE_NAME.tmp")
        temporary.writeText(metadata.toString())
        check(temporary.renameTo(target)) { "Cannot replace lifecycle metadata" }
    }
    companion object { const val FILE_NAME = "fixture-lifecycle.json" }
}
