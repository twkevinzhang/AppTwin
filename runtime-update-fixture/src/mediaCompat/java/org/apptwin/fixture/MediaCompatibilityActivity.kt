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
    private var sharedWriteOptIn = false
    private fun logSharedStage(stage: String) {
        if (sharedWriteOptIn) android.util.Log.i("SharedWriteFixture",
            "stage=$stage pid=${android.os.Process.myPid()} task=$taskId")
    }
    override fun onResume() {
        super.onResume()
        logSharedStage("resume")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Media compatibility acceptance fixture" })
        val configFile = File(filesDir, "media-compat-config.json")
        if (!configFile.isFile) return
        sharedWriteOptIn = runCatching { JSONObject(configFile.readText()).optString("mode") == "shared-write" }.getOrDefault(false)
        logSharedStage("create")
        val processId = android.os.Process.myPid()
        val activityTaskId = taskId
        Thread {
            val config = JSONObject(configFile.readText())
            val result = JSONObject().put("phase", config.getString("phase"))
                .put("pid", processId).put("taskId", activityTaskId)
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE, "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO").forEach {
                result.put(it.substringAfterLast('.'), checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED)
            }
            fun probe(key: String, operation: () -> Boolean) {
                result.put(key, try { if (operation()) "allowed" else "empty" } catch (failure: Exception) { "blocked:${failure.javaClass.simpleName}" })
            }
            if (config.optString("mode") == "selected-file-isolation") {
                collectSelectedFileIsolation(config, result)
            } else if (config.optString("mode") == "shared-write") {
                logSharedStage("probe-start")
                collectSharedWrites(config, result)
            } else listOf("image", "video").forEach { kind ->
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
            logSharedStage("probe-end")
        }.start()
    }
    private fun collectSelectedFileIsolation(config: JSONObject, result: JSONObject) {
        require(config.getString("name") == "apptwin-file-probe-20261013.txt")
        val uri = android.net.Uri.parse(config.getString("uri"))
        require(uri.scheme == "content")
        fun rejected(key: String, operation: () -> Unit) {
            try {
                operation()
                result.put("${key}Rejected", false).put("${key}Exception", "none")
            } catch (failure: Exception) {
                result.put("${key}Rejected", failure is SecurityException)
                    .put("${key}Exception", failure.javaClass.simpleName)
            }
        }
        rejected("query") {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() }
        }
        rejected("open") {
            contentResolver.openInputStream(uri)?.use { it.read() }
        }
    }
    private fun collectSharedWrites(config: JSONObject, result: JSONObject) {
        val expected = android.util.Base64.decode(config.getString("original"), android.util.Base64.NO_WRAP)
        val changed = android.util.Base64.decode(config.getString("changed"), android.util.Base64.NO_WRAP)
        fun probe(key: String, operation: () -> Boolean) {
            result.put(key, try { if (operation()) "allowed" else "empty" }
                catch (failure: Exception) { "blocked:${failure.javaClass.simpleName}" })
        }
        fun ownedPath(key: String): File {
            val file = File(config.getJSONObject(key).getString("path"))
            require(file.name.matches(Regex("apptwin-write-[a-zA-Z0-9-]+\\.png")))
            return file
        }
        val read = config.getJSONObject("read")
        val readUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, read.getLong("id"))
        probe("sharedQuery") { contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("_id"),
            "_display_name = ?", arrayOf(read.getString("name")), null)?.use { it.moveToFirst() } ?: false }
        probe("sharedOpen") { contentResolver.openInputStream(readUri)?.use { it.readBytes().contentEquals(expected) } ?: false }
        probe("sharedDirectRead") { ownedPath("read").readBytes().contentEquals(expected) }
        probe("providerWrite") {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, config.getJSONObject("providerWrite").getLong("id"))
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(changed) } ?: return@probe false
            true
        }
        probe("directTruncate") { ownedPath("directTruncate").outputStream().use { it.write(changed) }; true }
        probe("relativeWrite") { SharedWriteNativeProbe.writeRelative(ownedPath("directTruncate").absolutePath,
            android.util.Base64.decode(config.getString("relativeChanged"), android.util.Base64.NO_WRAP)) }
        probe("directRename") { ownedPath("directRename").renameTo(ownedPath("renameDestination")) }
        probe("directDelete") { ownedPath("directDelete").delete() }
        probe("directCreate") { ownedPath("directCreate").outputStream().use { it.write(changed) }; true }
        probe("privateWrite") { File(filesDir, "shared-write-private-sentinel").apply { writeBytes(changed) }.readBytes().contentEquals(changed) }
        probe("virtualWrite") {
            File(getExternalFilesDir(null), "shared-write-virtual-sentinel").apply { writeBytes(changed) }.readBytes().contentEquals(changed)
        }
    }

}
