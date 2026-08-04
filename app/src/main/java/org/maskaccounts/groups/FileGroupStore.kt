package org.maskaccounts.groups

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Persists Group identity and GroupApp membership in host-private storage. */
class FileGroupStore(context: Context) : GroupStore {
    private val filesRoot = context.applicationContext.filesDir
    private val root = File(filesRoot, "groups")
    private val legacyRoot = File(filesRoot, "instances")

    init {
        migrateLegacyInstances()
    }

    @Synchronized
    override fun create(name: String, createdAtEpochMillis: Long): AppGroup {
        val group = AppGroup(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
        ensureRoot()
        val staging = File(root, ".staging-${group.id}")
        check(staging.mkdirs()) { "Unable to create group staging root" }
        return try {
            check(File(staging, DATA_DIRECTORY).mkdir()) { "Unable to create group data root" }
            check(File(staging, APPS_DIRECTORY).mkdir()) { "Unable to create group apps root" }
            writeGroupMetadata(File(staging, GROUP_METADATA), group)
            check(staging.renameTo(File(root, group.id))) { "Unable to atomically activate group" }
            group
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    override fun listAll(): List<AppGroup> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory && !it.name.startsWith(".staging-") }
        .mapNotNull(::readGroup)
        .sortedWith(GROUP_ORDER)
        .toList()

    @Synchronized
    override fun find(groupId: String): AppGroup? = resolveGroupDirectory(groupId)?.let(::readGroup)

    @Synchronized
    override fun rename(groupId: String, name: String): AppGroup? = updateGroup(groupId) { group ->
        group.copy(name = name.trim())
    }

    @Synchronized
    override fun addApp(
        groupId: String,
        packageName: String,
        addedAtEpochMillis: Long,
    ): AppGroup? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = requireNotNull(readGroup(directory)) { "Unable to read group" }
        require(!current.contains(packageName)) { "$packageName already exists in this group" }
        val app = GroupApp(packageName, addedAtEpochMillis)
        val destination = File(File(directory, APPS_DIRECTORY), "$packageName.properties")
        val replacement = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}.tmp")
        return try {
            writeAppMetadata(replacement, app)
            atomicReplace(replacement, destination)
            readGroup(directory)
        } finally {
            replacement.delete()
        }
    }

    @Synchronized
    override fun removeApp(groupId: String, packageName: String): AppGroup? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = requireNotNull(readGroup(directory)) { "Unable to read group" }
        if (!current.contains(packageName)) return current
        val appFile = File(File(directory, APPS_DIRECTORY), "$packageName.properties")
        check(appFile.delete()) { "Unable to remove GroupApp metadata" }
        return readGroup(directory)
    }

    @Synchronized
    override fun updateRuntimeState(groupId: String, state: GroupRuntimeState): AppGroup? =
        updateGroup(groupId) { group -> group.copy(runtimeState = state) }

    @Synchronized
    override fun delete(groupId: String): Boolean {
        val directory = resolveGroupDirectory(groupId) ?: return false
        check(directory.deleteRecursively()) { "Unable to delete group directory" }
        return true
    }

    private fun updateGroup(groupId: String, transform: (AppGroup) -> AppGroup): AppGroup? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val metadata = File(directory, GROUP_METADATA)
        val current = readGroup(directory) ?: return null
        val updated = transform(current)
        val replacement = File(directory, ".$GROUP_METADATA-${UUID.randomUUID()}.tmp")
        return try {
            writeGroupMetadata(replacement, updated)
            atomicReplace(replacement, metadata)
            updated
        } finally {
            replacement.delete()
        }
    }

    private fun readGroup(directory: File): AppGroup? = runCatching {
        val properties = readProperties(File(directory, GROUP_METADATA)) ?: return null
        val apps = File(directory, APPS_DIRECTORY).listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .mapNotNull(::readAppMetadata)
            .sortedWith(compareBy(GroupApp::addedAtEpochMillis, GroupApp::packageName))
            .toList()
        AppGroup(
            id = requireNotNull(properties.getProperty("id")),
            name = requireNotNull(properties.getProperty("name")),
            createdAtEpochMillis = requireNotNull(
                properties.getProperty("createdAtEpochMillis")?.toLongOrNull(),
            ),
            runtimeState = GroupRuntimeState.valueOf(
                requireNotNull(properties.getProperty("runtimeState")),
            ),
            apps = apps,
        )
    }.getOrNull()

    private fun writeGroupMetadata(file: File, group: AppGroup) {
        val properties = Properties().apply {
            setProperty("id", group.id)
            setProperty("name", group.name)
            setProperty("createdAtEpochMillis", group.createdAtEpochMillis.toString())
            setProperty("runtimeState", group.runtimeState.name)
        }
        writeProperties(file, properties, "MaskAccounts app group")
    }

    private fun writeAppMetadata(file: File, app: GroupApp) {
        val properties = Properties().apply {
            setProperty("packageName", app.packageName)
            setProperty("addedAtEpochMillis", app.addedAtEpochMillis.toString())
        }
        writeProperties(file, properties, "MaskAccounts GroupApp")
    }

    private fun readAppMetadata(file: File): GroupApp? = runCatching {
        val properties = readProperties(file) ?: return null
        GroupApp(
            packageName = requireNotNull(properties.getProperty("packageName")),
            addedAtEpochMillis = requireNotNull(
                properties.getProperty("addedAtEpochMillis")?.toLongOrNull(),
            ),
        )
    }.getOrNull()

    private fun resolveGroupDirectory(groupId: String): File? {
        val canonicalId = runCatching { UUID.fromString(groupId).toString() }
            .getOrNull()
            ?.takeIf { it == groupId }
            ?: return null
        val canonicalRoot = root.canonicalFile
        val directory = File(root, canonicalId).canonicalFile
        return directory.takeIf {
            it.isDirectory && it.parentFile == canonicalRoot && readGroup(it)?.id == canonicalId
        }
    }

    /**
     * Migrate each legacy instance to a one-App group. The legacy tree remains as a rollback copy;
     * runtime.properties is copied so an existing virtual user and its login data stay reachable.
     */
    private fun migrateLegacyInstances() {
        if (!legacyRoot.isDirectory) return
        ensureRoot()
        legacyRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { packageRoot ->
            packageRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { instanceRoot ->
                val legacy = readProperties(File(instanceRoot, LEGACY_METADATA)) ?: return@forEach
                val id = legacy.getProperty("id") ?: return@forEach
                val packageName = legacy.getProperty("packageName") ?: return@forEach
                val name = legacy.getProperty("displayName") ?: packageName
                val createdAt = legacy.getProperty("createdAtEpochMillis")?.toLongOrNull()
                    ?: instanceRoot.lastModified().coerceAtLeast(0)
                if (File(root, id).isDirectory) return@forEach
                runCatching {
                    val group = AppGroup(id, name, createdAt)
                    val staging = File(root, ".staging-$id")
                    staging.deleteRecursively()
                    check(staging.mkdirs()) { "Unable to stage legacy group" }
                    val legacyData = File(instanceRoot, DATA_DIRECTORY)
                    val targetData = File(staging, DATA_DIRECTORY)
                    if (legacyData.isDirectory) {
                        check(legacyData.copyRecursively(targetData, overwrite = false)) {
                            "Unable to preserve legacy runtime mapping"
                        }
                    } else {
                        check(targetData.mkdir()) { "Unable to create migrated data root" }
                    }
                    val apps = File(staging, APPS_DIRECTORY)
                    check(apps.mkdir()) { "Unable to create migrated apps root" }
                    writeGroupMetadata(File(staging, GROUP_METADATA), group)
                    writeAppMetadata(
                        File(apps, "$packageName.properties"),
                        GroupApp(packageName, createdAt),
                    )
                    check(staging.renameTo(File(root, id))) { "Unable to activate migrated group" }
                }
            }
        }
    }

    private fun ensureRoot() {
        check(root.isDirectory || root.mkdirs()) { "Unable to create groups root" }
    }

    private fun readProperties(file: File): Properties? {
        if (!file.isFile) return null
        return Properties().apply { FileInputStream(file).use(::load) }
    }

    private fun writeProperties(file: File, properties: Properties, comment: String) {
        FileOutputStream(file).use { output ->
            properties.store(output, comment)
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    companion object {
        const val DATA_DIRECTORY = "data"
        const val RUNTIME_METADATA = "runtime.properties"
        private const val GROUP_METADATA = "group.properties"
        private const val APPS_DIRECTORY = "apps"
        private const val LEGACY_METADATA = "instance.properties"
        private val GROUP_ORDER = compareBy(AppGroup::createdAtEpochMillis, AppGroup::id)
    }
}
