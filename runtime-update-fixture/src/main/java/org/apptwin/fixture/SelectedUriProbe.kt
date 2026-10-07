package org.apptwin.fixture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Opt-in system picker test. Reads only the named synthetic fixture, never records file content. */
class SelectedUriProbe(private val activity: Activity) {
    private val saved = File(activity.filesDir, "selected-uri-private.txt")
    private val output = File(activity.filesDir, "selected-uri-probe.json")
    private lateinit var status: TextView

    fun show() {
        status = TextView(activity).apply { text = "Selected URI permission probe"; textSize = 18f }
        activity.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(Button(activity).apply {
                text = "選取測試圖片"
                setOnClickListener {
                    activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "image/png"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }, REQUEST)
                }
            })
            addView(Button(activity).apply {
                text = "重讀已選圖片"
                setOnClickListener { readSaved("reread") }
            })
        })
        if (saved.exists()) readSaved("relaunch")
    }

    fun onResult(request: Int, result: Int, intent: Intent?): Boolean {
        if (request != REQUEST) return false
        if (result != Activity.RESULT_OK || intent?.data == null) {
            record("selection", false, false, "cancelled")
            return true
        }
        val uri = requireNotNull(intent.data)
        try {
            val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            check(name == SENTINEL) { "NotSyntheticSentinel" }
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            saved.writeText(uri.toString())
            readSaved("selection")
        } catch (failure: Throwable) {
            record("selection", false, false, failure.javaClass.simpleName)
        }
        return true
    }

    private fun readSaved(phase: String) {
        try {
            val uri = Uri.parse(saved.readText())
            val persisted = activity.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            val readable = activity.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
            record(phase, readable, persisted, null)
        } catch (failure: Throwable) {
            record(phase, false, false, failure.javaClass.simpleName)
        }
    }

    private fun record(phase: String, readable: Boolean, persisted: Boolean, error: String?) {
        val metadata = JSONObject().put("phase", phase).put("readable", readable).put("persisted", persisted)
            .put("error", error ?: JSONObject.NULL)
        output.writeText(metadata.toString())
        status.text = metadata.toString()
    }

    companion object {
        const val REQUEST = 7419
        const val ENABLE_FILE = "enable-selected-uri-probe"
        const val SENTINEL = "apptwin-permission-sentinel.png"
    }
}
