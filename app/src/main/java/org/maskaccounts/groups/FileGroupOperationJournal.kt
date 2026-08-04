package org.maskaccounts.groups

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

class FileGroupOperationJournal(context: Context) : GroupOperationJournal {
    private val root = File(context.applicationContext.filesDir, "group-operations")

    @Synchronized
    override fun listAll(): List<GroupOperation> = root.listFiles()
        .orEmpty()
        .filter(File::isFile)
        .map(::read)
        .sortedBy(GroupOperation::startedAtEpochMillis)

    @Synchronized
    override fun write(operation: GroupOperation) {
        check(root.isDirectory || root.mkdirs()) { "Unable to create Group operation journal" }
        val destination = File(root, "${operation.groupId}.properties")
        val replacement = File(root, ".${operation.groupId}-${UUID.randomUUID()}.tmp")
        try {
            val properties = Properties().apply {
                setProperty("groupId", operation.groupId)
                setProperty("type", operation.type.name)
                setProperty("phase", operation.phase.name)
                setProperty("startedAtEpochMillis", operation.startedAtEpochMillis.toString())
                operation.groupName?.let { setProperty("groupName", it) }
                operation.environmentBinding?.let {
                    setProperty("environmentBindingId", it.internalId.toString())
                }
                operation.sourceBinding?.let {
                    setProperty("sourceBindingId", it.internalId.toString())
                }
            }
            FileOutputStream(replacement).use { output ->
                properties.store(output, "MaskAccounts Group lifecycle operation")
                output.fd.sync()
            }
            Files.move(
                replacement.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            replacement.delete()
        }
    }

    @Synchronized
    override fun remove(groupId: String) {
        val canonicalId = UUID.fromString(groupId).toString()
        val file = File(root, "$canonicalId.properties")
        check(!file.exists() || file.delete()) { "Unable to clear Group operation journal" }
    }

    private fun read(file: File): GroupOperation = runCatching {
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        GroupOperation(
            groupId = requireNotNull(properties.getProperty("groupId")),
            type = GroupOperationType.valueOf(requireNotNull(properties.getProperty("type"))),
            phase = GroupOperationPhase.valueOf(requireNotNull(properties.getProperty("phase"))),
            groupName = properties.getProperty("groupName"),
            environmentBinding = properties.getProperty("environmentBindingId")
                ?.toIntOrNull()
                ?.let(::EnvironmentBinding),
            sourceBinding = properties.getProperty("sourceBindingId")
                ?.toIntOrNull()
                ?.let(::EnvironmentBinding),
            startedAtEpochMillis = requireNotNull(
                properties.getProperty("startedAtEpochMillis")?.toLongOrNull(),
            ),
        )
    }.getOrElse { error ->
        throw IllegalStateException("Unable to read Group operation journal: ${file.name}", error)
    }
}
