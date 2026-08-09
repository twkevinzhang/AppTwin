package org.apptwin.operations

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Durable application-operation store used to restore user-visible progress after process death. */
class FileOperationRecordStore internal constructor(
    filesRoot: File,
    private val directorySync: (File) -> Unit,
) : OperationRecordStore {
    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        ::syncDirectory,
    )

    private val root = File(filesRoot, DIRECTORY_NAME)

    @Synchronized
    override fun listPending(): List<OperationRecord> = root.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "properties" && !it.name.startsWith(".") }
        .map(::read)
        .sortedWith(compareBy(OperationRecord::startedAtEpochMillis, OperationRecord::id))

    @Synchronized
    override fun find(id: String): OperationRecord? {
        val file = fileFor(id)
        return file.takeIf(File::isFile)?.let(::read)
    }

    @Synchronized
    override fun save(record: OperationRecord) {
        check(root.isDirectory || root.mkdirs()) { "Unable to create operation directory" }
        directorySync(root.parentFile ?: error("Operation root has no parent"))
        val destination = fileFor(record.id)
        val replacement = File(root, ".${record.id}-${UUID.randomUUID()}.tmp")
        try {
            val fields = OperationRecordCodec.encode(record)
            val properties = Properties().apply { fields.forEach(::setProperty) }
            FileOutputStream(replacement).use { output ->
                properties.store(output, "AppTwin durable application operation")
                output.fd.sync()
            }
            Files.move(
                replacement.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            directorySync(root)
        } finally {
            replacement.delete()
        }
    }

    @Synchronized
    override fun remove(id: String) {
        val destination = fileFor(id)
        check(!destination.exists() || destination.delete()) {
            "Unable to remove operation ${destination.name}"
        }
        if (root.isDirectory) directorySync(root)
    }

    private fun read(file: File): OperationRecord = runCatching {
        val fields = Properties().apply { FileInputStream(file).use(::load) }
            .entries
            .associate { (key, value) -> key.toString() to value.toString() }
        OperationRecordCodec.decode(fields)
    }.getOrElse { error ->
        throw IllegalStateException("Unable to read durable operation ${file.name}", error)
    }

    private fun fileFor(id: String): File = File(root, "${UUID.fromString(id)}.properties")

    private companion object {
        const val DIRECTORY_NAME = "application-operations"

        fun syncDirectory(directory: File) {
            val descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}
