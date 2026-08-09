package org.apptwin.groups

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Persists Group identity, immutable environment ownership, and GroupApp membership. */
class FileGroupStore internal constructor(private val filesRoot: File) : GroupStore {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val root = File(filesRoot, "groups")
    @Synchronized
    override fun create(
        id: String,
        name: String,
        environmentBinding: EnvironmentBinding,
        createdAtEpochMillis: Long,
    ): Group {
        val group = Group(
            id = UUID.fromString(id).toString(),
            name = name.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
            environmentBinding = environmentBinding,
            health = GroupHealth.HEALTHY,
        )
        check(listAll().none { it.environmentBinding == environmentBinding }) {
            "Environment binding already belongs to another Group"
        }
        ensureRoot()
        val staging = File(root, ".staging-${group.id}")
        check(staging.mkdirs()) { "Unable to create Group staging root" }
        return try {
            check(File(staging, DATA_DIRECTORY).mkdir()) { "Unable to create Group data root" }
            check(File(staging, APPS_DIRECTORY).mkdir()) { "Unable to create Group apps root" }
            writeGroupMetadata(File(staging, GROUP_METADATA), group)
            check(staging.renameTo(File(root, group.id))) { "Unable to activate Group" }
            group
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    override fun listAll(): List<Group> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory && !it.name.startsWith(".staging-") }
        .mapNotNull(::readGroup)
        .sortedWith(GROUP_ORDER)
        .toList()

    @Synchronized
    override fun find(groupId: String): Group? = resolveGroupDirectory(groupId)?.let(::readGroup)

    @Synchronized
    override fun rename(groupId: String, name: String): Group? = updateGroup(groupId) { group ->
        group.copy(name = name.trim())
    }

    @Synchronized
    override fun addApp(
        groupId: String,
        packageName: String,
        addedAtEpochMillis: Long,
    ): Group? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = requireNotNull(readGroup(directory)) { "Unable to read Group" }
        require(current.health == GroupHealth.HEALTHY) { "Group is not available" }
        require(!current.contains(packageName)) { "$packageName already exists in this Group" }
        val app = GroupApp(packageName, addedAtEpochMillis)
        writeAppAtomically(directory, app)
        return readGroup(directory)
    }

    @Synchronized
    override fun removeApp(groupId: String, packageName: String): Group? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = requireNotNull(readGroup(directory)) { "Unable to read Group" }
        if (!current.contains(packageName)) return current
        val appFile = File(File(directory, APPS_DIRECTORY), "$packageName.properties")
        check(appFile.delete()) { "Unable to remove GroupApp metadata" }
        return readGroup(directory)
    }

    @Synchronized
    override fun updateAppState(
        groupId: String,
        packageName: String,
        state: GroupAppState,
    ): Group? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = requireNotNull(readGroup(directory)) { "Unable to read Group" }
        val app = current.apps.firstOrNull { it.packageName == packageName } ?: return current
        writeAppAtomically(directory, app.copy(state = state))
        return readGroup(directory)
    }

    @Synchronized
    override fun updateHealth(groupId: String, health: GroupHealth): Group? =
        updateGroup(groupId) { group -> group.copy(health = health) }

    @Synchronized
    override fun delete(groupId: String): Boolean {
        val directory = resolveGroupDirectory(groupId) ?: return false
        check(directory.deleteRecursively()) { "Unable to delete Group directory" }
        return true
    }

    private fun updateGroup(groupId: String, transform: (Group) -> Group): Group? {
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

    private fun readGroup(directory: File): Group? = runCatching {
        val properties = readProperties(File(directory, GROUP_METADATA)) ?: return null
        val schemaVersion = requireNotNull(
            properties.getProperty("schemaVersion")?.toIntOrNull(),
        )
        require(schemaVersion == CURRENT_GROUP_SCHEMA_VERSION) { "Unsupported Group schema" }
        val bindingId = properties.getProperty("environmentBindingId")?.toIntOrNull()
        val apps = File(directory, APPS_DIRECTORY).listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .mapNotNull(::readAppMetadata)
            .sortedWith(compareBy(GroupApp::addedAtEpochMillis, GroupApp::packageName))
            .toList()
        Group(
            id = requireNotNull(properties.getProperty("id")),
            name = requireNotNull(properties.getProperty("name")),
            createdAtEpochMillis = requireNotNull(
                properties.getProperty("createdAtEpochMillis")?.toLongOrNull(),
            ),
            environmentBinding = bindingId?.let(::EnvironmentBinding),
            health = GroupHealth.valueOf(requireNotNull(properties.getProperty("health"))),
            apps = apps,
            schemaVersion = schemaVersion,
        )
    }.getOrNull()

    private fun writeGroupMetadata(file: File, group: Group) {
        val properties = Properties().apply {
            setProperty("schemaVersion", CURRENT_GROUP_SCHEMA_VERSION.toString())
            setProperty("id", group.id)
            setProperty("name", group.name)
            setProperty("createdAtEpochMillis", group.createdAtEpochMillis.toString())
            setProperty("health", group.health.name)
            group.environmentBinding?.let { binding ->
                setProperty("environmentBindingId", binding.internalId.toString())
            }
        }
        writeProperties(file, properties, "AppTwin Group")
    }

    private fun writeAppAtomically(directory: File, app: GroupApp) {
        val destination = File(File(directory, APPS_DIRECTORY), "${app.packageName}.properties")
        val replacement = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}.tmp")
        try {
            writeAppMetadata(replacement, app)
            atomicReplace(replacement, destination)
        } finally {
            replacement.delete()
        }
    }

    private fun writeAppMetadata(file: File, app: GroupApp) {
        val properties = Properties().apply {
            setProperty("packageName", app.packageName)
            setProperty("addedAtEpochMillis", app.addedAtEpochMillis.toString())
            setProperty("state", app.state.name)
        }
        writeProperties(file, properties, "AppTwin GroupApp")
    }

    private fun readAppMetadata(file: File): GroupApp? = runCatching {
        val properties = readProperties(file) ?: return null
        GroupApp(
            packageName = requireNotNull(properties.getProperty("packageName")),
            addedAtEpochMillis = requireNotNull(
                properties.getProperty("addedAtEpochMillis")?.toLongOrNull(),
            ),
            state = properties.getProperty("state")
                ?.let(GroupAppState::valueOf)
                ?: GroupAppState.ADDED,
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

    private fun ensureRoot() {
        check(root.isDirectory || root.mkdirs()) { "Unable to create Groups root" }
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
        private val GROUP_ORDER = compareBy(Group::createdAtEpochMillis, Group::id)
    }
}
