package org.apptwin.groups

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

class FileGroupAppRemovalJournal internal constructor(filesRoot: File) : GroupAppRemovalJournal {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val root = File(filesRoot, "group-app-removals")

    @Synchronized
    override fun listAll(): List<GroupAppRemovalOperation> = root.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile && !file.name.startsWith(".") && file.extension == "properties"
        }
        .map(::read)
        .sortedWith(compareBy(GroupAppRemovalOperation::startedAtEpochMillis, ::key))

    @Synchronized
    override fun write(operation: GroupAppRemovalOperation) {
        check(root.isDirectory || root.mkdirs()) {
            "Unable to create GroupApp removal journal"
        }
        val destination = fileFor(operation.groupId, operation.packageName)
        val replacement = File(root, ".${destination.name}-${UUID.randomUUID()}.tmp")
        try {
            val properties = Properties().apply {
                setProperty("groupId", operation.groupId)
                setProperty("packageName", operation.packageName)
                setProperty("environmentBindingId", operation.environmentBinding.internalId.toString())
                setProperty(
                    "membershipAddedAtEpochMillis",
                    operation.membershipAddedAtEpochMillis.toString(),
                )
                setProperty("phase", operation.phase.name)
                setProperty("startedAtEpochMillis", operation.startedAtEpochMillis.toString())
            }
            FileOutputStream(replacement).use { output ->
                properties.store(output, "AppTwin GroupApp removal operation")
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
    override fun remove(groupId: String, packageName: String) {
        val file = fileFor(groupId, packageName)
        check(!file.exists() || file.delete()) { "Unable to clear GroupApp removal journal" }
    }

    private fun read(file: File): GroupAppRemovalOperation = runCatching {
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        GroupAppRemovalOperation(
            groupId = requireNotNull(properties.getProperty("groupId")),
            packageName = requireNotNull(properties.getProperty("packageName")),
            environmentBinding = EnvironmentBinding(
                requireNotNull(properties.getProperty("environmentBindingId")?.toIntOrNull()),
            ),
            membershipAddedAtEpochMillis = requireNotNull(
                properties.getProperty("membershipAddedAtEpochMillis")?.toLongOrNull(),
            ),
            phase = GroupAppRemovalPhase.valueOf(
                requireNotNull(properties.getProperty("phase")),
            ),
            startedAtEpochMillis = requireNotNull(
                properties.getProperty("startedAtEpochMillis")?.toLongOrNull(),
            ),
        )
    }.getOrElse { error ->
        throw IllegalStateException("Unable to read GroupApp removal journal: ${file.name}", error)
    }

    private fun fileFor(groupId: String, packageName: String): File {
        val canonicalGroupId = UUID.fromString(groupId).toString()
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        return File(root, "$canonicalGroupId--$packageName.properties")
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        fun key(operation: GroupAppRemovalOperation): String =
            "${operation.groupId}/${operation.packageName}"
    }
}
