package org.apptwin.fixture

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Reads only the exact names/bytes supplied in this guest's private opt-in configuration. */
class MediaCompatibilityActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Media compatibility acceptance fixture" })
        val configFile = File(filesDir, "media-compat-config.json")
        if (!configFile.isFile) return
        val processId = android.os.Process.myPid()
        val activityTaskId = taskId
        Thread {
            val config = JSONObject(configFile.readText())
            val result = JSONObject().put("phase", config.getString("phase"))
                .put("pid", processId).put("taskId", activityTaskId)
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO").forEach {
                result.put(it.substringAfterLast('.'), checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
            }
            fun probe(key: String, operation: () -> Boolean) {
                result.put(key, try { if (operation()) "allowed" else "empty" } catch (failure: Exception) { "blocked:${failure.javaClass.simpleName}" })
            }
            listOf("image", "video").forEach { kind ->
                val entry = config.getJSONObject(kind)
                val name = entry.getString("name")
                require(name.matches(Regex("apptwin-media-[a-zA-Z0-9-]+\\.(png|mp4)")))
                val expected = android.util.Base64.decode(entry.getString("bytes"), android.util.Base64.NO_WRAP)
                val collection = if (kind == "image") MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                probe("${kind}Query") {
                    contentResolver.query(collection, arrayOf("_id"), "_display_name = ?", arrayOf(name), null)?.use { it.moveToFirst() } ?: false
                }
                // Known test-owned URI opens independently of query visibility; no selected-URI grant.
                probe("${kind}Open") {
                    contentResolver.openInputStream(ContentUris.withAppendedId(collection, entry.getLong("id")))?.use { it.readBytes().contentEquals(expected) } ?: false
                }
                probe("${kind}Direct") {
                    File(entry.getString("path")).inputStream().use { it.readBytes().contentEquals(expected) }
                }
            }
            val temporary = File(filesDir, "media-compat-result.tmp")
            temporary.writeText(result.toString())
            check(temporary.renameTo(File(filesDir, "media-compat-result.json")))
        }.start()
    }
}
