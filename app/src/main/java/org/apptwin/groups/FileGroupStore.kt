package org.apptwin.groups

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import org.apptwin.runtime.DaemonAuthorizationGeneration

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
        val snapshot = loadSnapshot()
        snapshot.issues.firstOrNull()?.let { throw GroupStoreLoadException(it) }
        check(snapshot.groups.none { it.environmentBinding == environmentBinding }) {
            "Environment binding already belongs to another Group"
        }
        DaemonAuthorizationGeneration.invalidateBeforeMutation(filesRoot)
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
    override fun listAll(): List<Group> = loadSnapshot().groups

    @Synchronized
    override fun loadSnapshot(): GroupStoreSnapshot {
        val groups = mutableListOf<Group>()
        val issues = mutableListOf<GroupStoreLoadIssue>()
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".staging-") }
            .map(::readGroup)
            .forEach { result ->
                when (result) {
                    is GroupLookupResult.Found -> groups += result.group
                    is GroupLookupResult.Failed -> issues += result.issue
                    GroupLookupResult.NotFound -> Unit
                }
            }
        return GroupStoreSnapshot(
            groups = groups.sortedWith(GROUP_ORDER),
            issues = issues.sortedWith(
                compareBy(GroupStoreLoadIssue::groupId, GroupStoreLoadIssue::metadataName),
            ),
        )
    }

    @Synchronized
    override fun lookup(groupId: String): GroupLookupResult = resolveGroupDirectory(groupId)
        ?.let(::readGroup)
        ?: GroupLookupResult.NotFound

    @Synchronized
    override fun find(groupId: String): Group? = when (val result = lookup(groupId)) {
        is GroupLookupResult.Found -> result.group
        is GroupLookupResult.Failed -> throw GroupStoreLoadException(result.issue)
        GroupLookupResult.NotFound -> null
    }

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
        val current = readGroupOrThrow(directory)
        require(current.health == GroupHealth.HEALTHY) { "Group is not available" }
        require(!current.contains(packageName)) { "$packageName already exists in this Group" }
        val app = GroupApp(packageName, addedAtEpochMillis)
        writeAppAtomically(directory, app)
        return readGroupOrThrow(directory)
    }

    @Synchronized
    override fun removeApp(groupId: String, packageName: String): Group? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = readGroupOrThrow(directory)
        if (!current.contains(packageName)) return current
        val appFile = File(File(directory, APPS_DIRECTORY), "$packageName.properties")
        check(appFile.delete()) { "Unable to remove GroupApp metadata" }
        return readGroupOrThrow(directory)
    }

    @Synchronized
    override fun updateAppState(
        groupId: String,
        packageName: String,
        state: GroupAppState,
    ): Group? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val current = readGroupOrThrow(directory)
        val app = current.apps.firstOrNull { it.packageName == packageName } ?: return current
        writeAppAtomically(directory, app.copy(state = state))
        return readGroupOrThrow(directory)
    }

    @Synchronized
    override fun updateHealth(groupId: String, health: GroupHealth): Group? =
        updateGroup(groupId) { group -> group.copy(health = health) }

    @Synchronized
    override fun delete(groupId: String): Boolean {
        val directory = resolveGroupDirectory(groupId) ?: return false
        DaemonAuthorizationGeneration.invalidateBeforeMutation(filesRoot)
        check(directory.deleteRecursively()) { "Unable to delete Group directory" }
        return true
    }

    private fun updateGroup(groupId: String, transform: (Group) -> Group): Group? {
        val directory = resolveGroupDirectory(groupId) ?: return null
        val metadata = File(directory, GROUP_METADATA)
        val current = readGroupOrThrow(directory)
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

    private fun readGroup(directory: File): GroupLookupResult = try {
        GroupLookupResult.Found(readGroupMetadata(directory))
    } catch (error: MetadataLoadFailure) {
        GroupLookupResult.Failed(error.issue)
    }

    private fun readGroupOrThrow(directory: File): Group = when (val result = readGroup(directory)) {
        is GroupLookupResult.Found -> result.group
        is GroupLookupResult.Failed -> throw GroupStoreLoadException(result.issue)
        GroupLookupResult.NotFound -> error("Group directory does not exist")
    }

    private fun readGroupMetadata(directory: File): Group {
        val groupId = directory.name
        val metadata = File(directory, GROUP_METADATA)
        val properties = readPropertiesOrFail(groupId, GroupMetadataKind.GROUP, metadata)
        val schemaVersion = properties.getProperty("schemaVersion")?.toIntOrNull()
            ?: corrupt(groupId, GroupMetadataKind.GROUP, metadata, "Missing or invalid schemaVersion")
        if (schemaVersion != CURRENT_GROUP_SCHEMA_VERSION) {
            throw MetadataLoadFailure(
                GroupStoreLoadIssue.UnsupportedSchema(
                    groupId = groupId,
                    metadataKind = GroupMetadataKind.GROUP,
                    metadataName = metadata.name,
                    actualVersion = schemaVersion,
                    supportedVersion = CURRENT_GROUP_SCHEMA_VERSION,
                ),
            )
        }
        return try {
            val persistedId = requireNotNull(properties.getProperty("id"))
            require(persistedId == groupId) { "Group id does not match its directory" }
            val bindingId = properties.getProperty("environmentBindingId")?.toIntOrNull()
            val appsDirectory = File(directory, APPS_DIRECTORY)
            require(appsDirectory.isDirectory) { "Group apps directory is missing" }
            val appFiles = requireNotNull(appsDirectory.listFiles()) { "Unable to list Group apps directory" }
            val apps = appFiles
                .asSequence()
                .filter { it.isFile && !it.name.startsWith(".") && it.extension == "properties" }
                .map { readAppMetadata(groupId, it) }
                .sortedWith(compareBy(GroupApp::addedAtEpochMillis, GroupApp::packageName))
                .toList()
            Group(
                id = persistedId,
                name = requireNotNull(properties.getProperty("name")),
                createdAtEpochMillis = requireNotNull(
                    properties.getProperty("createdAtEpochMillis")?.toLongOrNull(),
                ),
                environmentBinding = bindingId?.let(::EnvironmentBinding),
                health = GroupHealth.valueOf(requireNotNull(properties.getProperty("health"))),
                apps = apps,
                schemaVersion = schemaVersion,
            )
        } catch (error: MetadataLoadFailure) {
            throw error
        } catch (error: Exception) {
            corrupt(groupId, GroupMetadataKind.GROUP, metadata, error.message ?: error.javaClass.simpleName)
        }
    }

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

    private fun readAppMetadata(groupId: String, file: File): GroupApp {
        val properties = readPropertiesOrFail(groupId, GroupMetadataKind.APP, file)
        return try {
            val packageName = requireNotNull(properties.getProperty("packageName"))
            require(file.name == "$packageName.properties") {
                "Package name does not match metadata file"
            }
            GroupApp(
                packageName = packageName,
                addedAtEpochMillis = requireNotNull(
                    properties.getProperty("addedAtEpochMillis")?.toLongOrNull(),
                ),
                state = properties.getProperty("state")
                    ?.let(GroupAppState::valueOf)
                    ?: GroupAppState.ADDED,
            )
        } catch (error: Exception) {
            corrupt(groupId, GroupMetadataKind.APP, file, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun resolveGroupDirectory(groupId: String): File? {
        val canonicalId = runCatching { UUID.fromString(groupId).toString() }
            .getOrNull()
            ?.takeIf { it == groupId }
            ?: return null
        val canonicalRoot = root.canonicalFile
        val directory = File(root, canonicalId).canonicalFile
        return directory.takeIf {
            it.isDirectory && it.parentFile == canonicalRoot
        }
    }

    private fun ensureRoot() {
        check(root.isDirectory || root.mkdirs()) { "Unable to create Groups root" }
    }

    private fun readPropertiesOrFail(
        groupId: String,
        metadataKind: GroupMetadataKind,
        file: File,
    ): Properties {
        if (!file.isFile) corrupt(groupId, metadataKind, file, "Metadata file is missing")
        return try {
            Properties().apply { FileInputStream(file).use(::load) }
        } catch (error: Exception) {
            corrupt(groupId, metadataKind, file, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun corrupt(
        groupId: String,
        metadataKind: GroupMetadataKind,
        file: File,
        reason: String,
    ): Nothing {
        throw MetadataLoadFailure(
            GroupStoreLoadIssue.CorruptMetadata(
                groupId = groupId,
                metadataKind = metadataKind,
                metadataName = file.name,
                reason = reason,
            ),
        )
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

    private class MetadataLoadFailure(val issue: GroupStoreLoadIssue) : IllegalStateException()
}
