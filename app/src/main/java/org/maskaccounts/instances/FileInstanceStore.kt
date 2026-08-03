package org.maskaccounts.instances

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Persists instance identity and reserves an isolated data root without touching source app data. */
class FileInstanceStore(context: Context) : InstanceStore {
    private val root = File(context.applicationContext.filesDir, "instances")

    @Synchronized
    override fun create(
        packageName: String,
        displayName: String,
        createdAtEpochMillis: Long,
    ): VirtualInstance {
        val instance = VirtualInstance(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            displayName = displayName.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
        val packageRoot = File(root, packageName)
        check(packageRoot.exists() || packageRoot.mkdirs()) { "Unable to create instance package root" }
        val staging = File(packageRoot, ".staging-${instance.id}")
        check(staging.mkdirs()) { "Unable to create instance staging root" }
        return try {
            check(File(staging, "data").mkdir()) { "Unable to create isolated data root" }
            writeMetadata(File(staging, METADATA), instance)
            check(staging.renameTo(File(packageRoot, instance.id))) {
                "Unable to atomically activate instance"
            }
            instance
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    override fun listAll(): List<VirtualInstance> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory }
        .flatMap { packageRoot ->
            packageRoot.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isDirectory && !it.name.startsWith(".staging-") }
                .mapNotNull { readMetadata(File(it, METADATA)) }
        }
        .sortedWith(INSTANCE_ORDER)
        .toList()

    @Synchronized
    override fun list(packageName: String): List<VirtualInstance> =
        File(root, packageName).listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".staging-") }
            .mapNotNull { readMetadata(File(it, METADATA)) }
            .sortedWith(INSTANCE_ORDER)
            .toList()

    @Synchronized
    override fun find(instanceId: String): VirtualInstance? = resolveInstanceDirectory(instanceId)
        ?.let { readMetadata(File(it, METADATA)) }

    @Synchronized
    override fun rename(instanceId: String, displayName: String): VirtualInstance? {
        val trimmedDisplayName = displayName.trim()
        require(trimmedDisplayName.isNotBlank()) { "Instance display name must not be blank" }
        val instanceDirectory = resolveInstanceDirectory(instanceId) ?: return null
        val metadata = File(instanceDirectory, METADATA)
        val current = readMetadata(metadata) ?: return null
        val renamed = current.copy(displayName = trimmedDisplayName)
        val replacement = File(instanceDirectory, ".$METADATA-${UUID.randomUUID()}.tmp")
        return try {
            writeMetadata(replacement, renamed)
            Files.move(
                replacement.toPath(),
                metadata.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            renamed
        } finally {
            replacement.delete()
        }
    }

    @Synchronized
    override fun delete(instanceId: String): Boolean {
        val instanceDirectory = resolveInstanceDirectory(instanceId) ?: return false
        check(instanceDirectory.deleteRecursively()) { "Unable to delete instance directory" }
        return true
    }

    private fun resolveInstanceDirectory(instanceId: String): File? {
        val canonicalInstanceId = runCatching { UUID.fromString(instanceId).toString() }
            .getOrNull()
            ?.takeIf { it == instanceId }
            ?: return null
        val canonicalRoot = root.canonicalFile
        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.canonicalFile.parentFile == canonicalRoot }
            .map { packageRoot -> packageRoot.canonicalFile to File(packageRoot, canonicalInstanceId).canonicalFile }
            .filter { (packageRoot, instanceDirectory) ->
                instanceDirectory.isDirectory && instanceDirectory.parentFile == packageRoot
            }
            .mapNotNull { (packageRoot, instanceDirectory) ->
                val instance = readMetadata(File(instanceDirectory, METADATA))
                instanceDirectory.takeIf {
                    instance?.id == canonicalInstanceId && instance.packageName == packageRoot.name
                }
            }
            .firstOrNull()
    }

    private fun writeMetadata(file: File, instance: VirtualInstance) {
        val properties = Properties().apply {
            setProperty("id", instance.id)
            setProperty("packageName", instance.packageName)
            setProperty("displayName", instance.displayName)
            setProperty("createdAtEpochMillis", instance.createdAtEpochMillis.toString())
            setProperty("state", instance.state.name)
        }
        FileOutputStream(file).use { output ->
            properties.store(output, "MaskAccounts virtual instance")
            output.fd.sync()
        }
    }

    private fun readMetadata(file: File): VirtualInstance? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        VirtualInstance(
            id = requireNotNull(properties.getProperty("id")),
            packageName = requireNotNull(properties.getProperty("packageName")),
            displayName = requireNotNull(properties.getProperty("displayName")),
            createdAtEpochMillis = requireNotNull(
                properties.getProperty("createdAtEpochMillis")?.toLongOrNull(),
            ),
            state = InstanceState.valueOf(requireNotNull(properties.getProperty("state"))),
        )
    }.getOrNull()

    private companion object {
        const val METADATA = "instance.properties"
        val INSTANCE_ORDER = compareBy(VirtualInstance::createdAtEpochMillis, VirtualInstance::id)
    }
}
