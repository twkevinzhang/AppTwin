package org.apptwin.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.nio.file.Files

/** Global location is shared by every space; existing entries retain their original URI. */
data class SpaceBackupEntry(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val uri: String,
    val bytes: Long,
    val createdAt: Long,
    val accessible: Boolean,
)

internal class SpaceBackupRegistry(private val context: Context) {
    private val preferences = context.getSharedPreferences("space-backups-v2", Context.MODE_PRIVATE)

    fun globalLocation(): String? = preferences.getString("globalTree", null)

    fun setGlobalLocation(tree: Uri) {
        require(DocumentsContract.isTreeUri(tree)) { "請選擇備份資料夾" }
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(preferences.edit().putString("globalTree", tree.toString()).commit()) {
            "無法儲存 Global 備份位置"
        }
    }

    fun createDestination(name: String): Uri {
        val tree = globalLocation()?.let(Uri::parse) ?: error("請先設定 Global 備份位置")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val safeName = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(60)
        return requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent,
            "application/octet-stream", "$safeName-${System.currentTimeMillis()}.apptwin-space")) {
            "無法在 Global 位置建立備份檔案"
        }
    }

    @Synchronized fun record(spaceId: String, name: String, uri: Uri, bytes: Long) {
        val entries = read().toMutableList()
        entries.add(SpaceBackupEntry(UUID.randomUUID().toString(), spaceId, name, uri.toString(),
            bytes, System.currentTimeMillis(), true))
        save(entries)
    }

    @Synchronized fun list(spaceId: String): List<SpaceBackupEntry> = read()
        .filter { it.spaceId == spaceId }.sortedByDescending { it.createdAt }.map { entry ->
            entry.copy(accessible = runCatching {
                if (Uri.parse(entry.uri).scheme == "file") {
                    privateFile(Uri.parse(entry.uri)).isFile
                } else context.contentResolver.query(Uri.parse(entry.uri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null)?.use { it.moveToFirst() } == true
            }.getOrDefault(false))
        }

    @Synchronized fun delete(id: String) {
        val entries = read()
        val entry = entries.firstOrNull { it.id == id } ?: error("找不到備份")
        val uri = Uri.parse(entry.uri)
        deleteBackupIdempotently(
            deleteFile = { if (uri.scheme == "file") privateFile(uri).delete()
                else DocumentsContract.deleteDocument(context.contentResolver, uri) },
            presence = { presence(uri) },
            removeRecord = { save(entries.filterNot { it.id == id }) },
        )
    }

    private fun presence(uri: Uri): BackupPresence = runCatching {
        if (uri.scheme == "file") {
            val path = privateFile(uri).toPath()
            when {
                Files.exists(path) -> BackupPresence.PRESENT
                Files.notExists(path) -> BackupPresence.CONFIRMED_MISSING
                else -> BackupPresence.UNAVAILABLE
            }
        } else if (DocumentsContract.isTreeUri(uri)) {
            // A moved but still accessible document must remain registered.
            val directQuery = runCatching {
                context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null)?.use { it.moveToFirst() }
            }
            if (directQuery.getOrNull() == true) return@runCatching BackupPresence.PRESENT
            if (directQuery.exceptionOrNull() is SecurityException) return@runCatching BackupPresence.UNAVAILABLE
            // A successful parent enumeration proves absence at the recorded location.
            val documentId = DocumentsContract.getDocumentId(uri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri))
            val cursor = context.contentResolver.query(children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?: return@runCatching BackupPresence.UNAVAILABLE
            cursor.use {
                while (it.moveToNext()) {
                    if (it.getString(0) == documentId) return@runCatching BackupPresence.PRESENT
                }
                BackupPresence.CONFIRMED_MISSING
            }
        } else {
            val cursor = context.contentResolver.query(uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?: return@runCatching BackupPresence.UNAVAILABLE
            cursor.use { if (it.moveToFirst()) BackupPresence.PRESENT else BackupPresence.CONFIRMED_MISSING }
        }
    }.getOrDefault(BackupPresence.UNAVAILABLE)

    /** File URIs are supported for host-private exports and instrumentation fixtures only. */
    private fun privateFile(uri: Uri): File {
        val file = File(requireNotNull(uri.path)).canonicalFile
        require(listOf(context.filesDir, context.cacheDir).any { root ->
            file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile
        }) { "檔案位置不屬於 AppTwin 私有儲存空間" }
        return file
    }

    private fun read(): List<SpaceBackupEntry> {
        val array = JSONArray(preferences.getString("entries", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SpaceBackupEntry(item.getString("id"), item.getString("spaceId"), item.getString("spaceName"),
                item.getString("uri"), item.getLong("bytes"), item.getLong("createdAt"), false)
        }
    }

    private fun save(entries: List<SpaceBackupEntry>) {
        val array = JSONArray()
        entries.forEach { entry -> array.put(JSONObject().put("id", entry.id).put("spaceId", entry.spaceId)
            .put("spaceName", entry.spaceName).put("uri", entry.uri).put("bytes", entry.bytes)
            .put("createdAt", entry.createdAt)) }
        check(preferences.edit().putString("entries", array.toString()).commit()) { "無法儲存備份清單" }
    }
}
