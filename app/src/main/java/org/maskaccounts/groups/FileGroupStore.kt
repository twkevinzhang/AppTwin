package org.maskaccounts.groups

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Persists Group identity, immutable environment ownership, and GroupApp membership. */
class FileGroupStore(context: Context) : GroupStore {
    private val filesRoot = context.applicationContext.filesDir
    private val root = File(filesRoot, "groups")
    private val legacyRoot = File(filesRoot, "instances")

    init {
        migrateLegacyInstances()
    }

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
    override fun updateGoogleServicesState(
        groupId: String,
        state: GoogleServicesState,
    ): Group? = updateGroup(groupId) { group -> group.copy(googleServicesState = state) }

    @Synchronized
    override fun updateHealth(groupId: String, health: GroupHealth): Group? =
        updateGroup(groupId) { group -> group.copy(health = health) }

    @Synchronized
    override fun completeProvisioning(
        groupId: String,
        environmentBinding: EnvironmentBinding,
    ): Group? {
        val current = find(groupId) ?: return null
        require(current.health == GroupHealth.PROVISIONING) { "Group is not provisioning" }
        check(
            listAll().none {
                it.id != groupId &&
                    it.health != GroupHealth.PROVISIONING &&
                    it.environmentBinding == environmentBinding
            },
        ) { "Environment binding already belongs to another Group" }
        return updateGroup(groupId) { group ->
            group.copy(
                environmentBinding = environmentBinding,
                health = GroupHealth.HEALTHY,
                schemaVersion = CURRENT_GROUP_SCHEMA_VERSION,
            )
        }
    }

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
        val schemaVersion = properties.getProperty("schemaVersion")?.toIntOrNull() ?: 1
        val legacyRuntime = readProperties(
            File(File(directory, DATA_DIRECTORY), RUNTIME_METADATA),
        )
        val bindingId = properties.getProperty("environmentBindingId")?.toIntOrNull()
            ?: legacyRuntime?.getProperty("virtualUserId")?.toIntOrNull()
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
            health = if (schemaVersion >= CURRENT_GROUP_SCHEMA_VERSION) {
                GroupHealth.valueOf(requireNotNull(properties.getProperty("health")))
            } else {
                GroupHealth.PROVISIONING
            },
            googleServicesState = readGoogleServicesState(properties),
            apps = apps,
            schemaVersion = schemaVersion.coerceAtMost(CURRENT_GROUP_SCHEMA_VERSION),
        )
    }.getOrNull()

    private fun readGoogleServicesState(properties: Properties): GoogleServicesState {
        properties.getProperty("googleServicesState")?.let {
            return GoogleServicesState.valueOf(it)
        }
        return when (properties.getProperty("runtimeState")) {
            "READY" -> GoogleServicesState.READY
            "PREPARING" -> GoogleServicesState.PREPARING
            "FAILED" -> GoogleServicesState.FAILED
            else -> GoogleServicesState.NOT_PREPARED
        }
    }

    private fun writeGroupMetadata(file: File, group: Group) {
        val properties = Properties().apply {
            setProperty("schemaVersion", CURRENT_GROUP_SCHEMA_VERSION.toString())
            setProperty("id", group.id)
            setProperty("name", group.name)
            setProperty("createdAtEpochMillis", group.createdAtEpochMillis.toString())
            setProperty("health", group.health.name)
            setProperty("googleServicesState", group.googleServicesState.name)
            group.environmentBinding?.let { binding ->
                setProperty("environmentBindingId", binding.internalId.toString())
            }
        }
        writeProperties(file, properties, "MaskAccounts Group")
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
        writeProperties(file, properties, "MaskAccounts GroupApp")
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

    /** Imports the pre-Group M0 tree as provisioning records; lifecycle reconciliation binds it. */
    private fun migrateLegacyInstances() {
        if (!legacyRoot.isDirectory) return
        ensureRoot()
        legacyRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { packageRoot ->
            packageRoot.listFiles().orEmpty().filter(File::isDirectory).forEach inner@{ instanceRoot ->
                val legacy = readProperties(File(instanceRoot, LEGACY_METADATA)) ?: return@inner
                val id = legacy.getProperty("id") ?: return@inner
                val packageName = legacy.getProperty("packageName") ?: return@inner
                if (File(root, id).isDirectory) return@inner
                val createdAt = legacy.getProperty("createdAtEpochMillis")?.toLongOrNull()
                    ?: instanceRoot.lastModified().coerceAtLeast(0)
                runCatching {
                    val group = Group(
                        id = id,
                        name = legacy.getProperty("displayName") ?: packageName,
                        createdAtEpochMillis = createdAt,
                        environmentBinding = null,
                        health = GroupHealth.PROVISIONING,
                    )
                    val staging = File(root, ".staging-$id")
                    staging.deleteRecursively()
                    check(staging.mkdirs()) { "Unable to stage legacy Group" }
                    val targetData = File(staging, DATA_DIRECTORY)
                    val legacyData = File(instanceRoot, DATA_DIRECTORY)
                    if (legacyData.isDirectory) {
                        check(legacyData.copyRecursively(targetData, overwrite = false)) {
                            "Unable to preserve legacy runtime metadata"
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
                    check(staging.renameTo(File(root, id))) { "Unable to activate migrated Group" }
                }
            }
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
        private const val LEGACY_METADATA = "instance.properties"
        private val GROUP_ORDER = compareBy(Group::createdAtEpochMillis, Group::id)
    }
}
