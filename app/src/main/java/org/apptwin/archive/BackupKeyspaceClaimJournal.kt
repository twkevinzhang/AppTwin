package org.apptwin.archive

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Host-private write-ahead record; never populated from archive payload metadata. */
internal data class BackupKeyspaceClaim(
    val operationId: String,
    val archiveId: String,
    val originalOwner: String,
    val destinationOwner: String,
    val keyspaceId: String,
    val archiveSha256: String,
) {
    init {
        listOf(operationId, archiveId, originalOwner, destinationOwner, keyspaceId).forEach { value ->
            require(java.util.UUID.fromString(value).toString() == value) { "備份金鑰交易識別損毀" }
        }
        require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) { "備份金鑰交易摘要損毀" }
        require(originalOwner != destinationOwner) { "備份金鑰交易目的空間無效" }
    }
}

internal class BackupKeyspaceClaimJournal(private val root: File) {
    @Synchronized fun write(record: BackupKeyspaceClaim) {
        check(root.isDirectory || root.mkdirs()) { "無法建立備份金鑰交易紀錄" }
        val target = File(root, "${record.operationId}.properties")
        val temporary = File(root, "${record.operationId}.tmp")
        val values = Properties().apply {
            setProperty("operationId", record.operationId)
            setProperty("archiveId", record.archiveId)
            setProperty("originalOwner", record.originalOwner)
            setProperty("destinationOwner", record.destinationOwner)
            setProperty("keyspaceId", record.keyspaceId)
            setProperty("archiveSha256", record.archiveSha256)
        }
        FileOutputStream(temporary).use { output -> values.store(output, null); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
        syncDirectory()
    }

    @Synchronized fun records(): List<BackupKeyspaceClaim> = root.listFiles().orEmpty()
        .filter { it.name.endsWith(".properties") }.map { file ->
            val values = Properties().apply { FileInputStream(file).use(::load) }
            fun value(key: String) = requireNotNull(values.getProperty(key)) { "備份金鑰交易紀錄損毀" }
            BackupKeyspaceClaim(value("operationId"), value("archiveId"), value("originalOwner"),
                value("destinationOwner"), value("keyspaceId"), value("archiveSha256"))
        }

    private fun syncDirectory() {
        FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    @Synchronized fun remove(record: BackupKeyspaceClaim) {
        val file = File(root, "${record.operationId}.properties")
        check(!file.exists() || file.delete()) { "無法清理備份金鑰交易紀錄" }
        syncDirectory()
    }
}
