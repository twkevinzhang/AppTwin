package org.apptwin.gms

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityEvidence
import org.apptwin.gms.capabilities.GmsEvidenceOutcome
import org.apptwin.gms.capabilities.GmsEvidenceTier
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.operations.GmsOperation
import org.apptwin.gms.operations.GmsOperationKind
import org.apptwin.gms.operations.GmsOperationPhase
import org.apptwin.gms.operations.GmsOperationStore
import org.apptwin.gms.ports.GmsCapabilityEvidenceRepository
import org.apptwin.gms.ports.GmsProfileRepository
import org.apptwin.groups.CURRENT_GROUP_SCHEMA_VERSION

data class GmsDataWarning(
    val groupId: String,
    val metadataName: String,
)

class GmsDataCorruptionException(
    val groupId: String,
    val metadataName: String,
    cause: Throwable,
) : IllegalStateException("GMS compatibility data is unreadable: $groupId/$metadataName", cause)

internal class GmsDataIssueRecorder {
    private val values = linkedMapOf<Pair<String, String>, GmsDataWarning>()

    @Synchronized
    fun record(groupId: String, metadataName: String) {
        values[groupId to metadataName] = GmsDataWarning(groupId, metadataName)
    }

    @Synchronized
    fun list(): List<GmsDataWarning> = values.values.sortedWith(
        compareBy(GmsDataWarning::groupId, GmsDataWarning::metadataName),
    )
}

/** Per-Group durable state rooted at groups/<groupId>/data/gms. */
class FileGmsProfileRepository internal constructor(
    filesRoot: File,
    private val directorySync: (File) -> Unit,
    private val issues: GmsDataIssueRecorder = GmsDataIssueRecorder(),
) : GmsProfileRepository {
    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        DurableGmsFiles::syncDirectory,
    )

    private val files = DurableGmsFiles(filesRoot, directorySync, issues)

    @Synchronized
    override fun find(groupId: GmsGroupId): GmsProfile? = files.profileFile(groupId)
        .takeIf(File::isFile)
        ?.let { file -> files.read(groupId, file, ProfileCodec::decode) }

    @Synchronized
    override fun list(): List<GmsProfile> = files.groupDirectories()
        .mapNotNull { directory ->
            val groupId = GmsGroupId(directory.name)
            files.profileFile(groupId).takeIf(File::isFile)
                ?.let { file -> files.read(groupId, file, ProfileCodec::decode) }
        }
        .sortedBy { it.groupId.value }

    @Synchronized
    override fun save(profile: GmsProfile) {
        files.write(profile.groupId, files.profileFile(profile.groupId), ProfileCodec.encode(profile))
    }

    internal fun warnings(): List<GmsDataWarning> = issues.list()
}

class FileGmsOperationStore internal constructor(
    filesRoot: File,
    private val directorySync: (File) -> Unit,
    private val issues: GmsDataIssueRecorder = GmsDataIssueRecorder(),
) : GmsOperationStore {
    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        DurableGmsFiles::syncDirectory,
    )

    private val files = DurableGmsFiles(filesRoot, directorySync, issues)

    @Synchronized
    override fun list(): List<GmsOperation> = files.groupDirectories()
        .flatMap { groupDirectory ->
            val groupId = GmsGroupId(groupDirectory.name)
            files.operationsDirectory(groupId).listFiles().orEmpty()
                .filter { it.isFile && it.extension == "properties" && !it.name.startsWith(".") }
                .map { file -> files.read(groupId, file, OperationCodec::decode) }
        }
        .sortedWith(compareBy(GmsOperation::startedAtEpochMillis, GmsOperation::id))

    @Synchronized
    override fun find(id: String): GmsOperation? {
        val canonicalId = UUID.fromString(id).toString()
        return files.groupDirectories().asSequence()
            .map { groupDirectory ->
                val groupId = GmsGroupId(groupDirectory.name)
                groupId to File(files.operationsDirectory(groupId), "$canonicalId.properties")
            }
            .firstOrNull { (_, file) -> file.isFile }
            ?.let { (groupId, file) -> files.read(groupId, file, OperationCodec::decode) }
    }

    @Synchronized
    override fun save(operation: GmsOperation) {
        val destination = File(files.operationsDirectory(operation.groupId), "${operation.id}.properties")
        files.write(operation.groupId, destination, OperationCodec.encode(operation))
    }

    @Synchronized
    override fun remove(id: String) {
        val canonicalId = UUID.fromString(id).toString()
        files.groupDirectories().forEach { groupDirectory ->
            val groupId = GmsGroupId(groupDirectory.name)
            files.remove(File(files.operationsDirectory(groupId), "$canonicalId.properties"))
        }
    }

    internal fun warnings(): List<GmsDataWarning> = issues.list()
}

class FileGmsCapabilityEvidenceRepository internal constructor(
    filesRoot: File,
    private val directorySync: (File) -> Unit,
    private val issues: GmsDataIssueRecorder = GmsDataIssueRecorder(),
) : GmsCapabilityEvidenceRepository {
    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        DurableGmsFiles::syncDirectory,
    )

    private val files = DurableGmsFiles(filesRoot, directorySync, issues)

    @Synchronized
    override fun list(groupId: GmsGroupId): List<GmsCapabilityEvidence> =
        files.evidenceDirectory(groupId).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "properties" && !it.name.startsWith(".") }
            .map { file ->
                files.read(groupId, file, EvidenceCodec::decode) { decoded ->
                    require(file.name == evidenceFileName(decoded.evidenceId)) {
                        "evidence id does not match filename"
                    }
                }
            }
            .sortedWith(
                compareBy(
                    GmsCapabilityEvidence::observedAtEpochMillis,
                    GmsCapabilityEvidence::evidenceId,
                ),
            )

    @Synchronized
    override fun save(evidence: GmsCapabilityEvidence) {
        val destination = File(
            files.evidenceDirectory(evidence.groupId),
            evidenceFileName(evidence.evidenceId),
        )
        files.write(evidence.groupId, destination, EvidenceCodec.encode(evidence))
    }

    internal fun warnings(): List<GmsDataWarning> = issues.list()
}

private class DurableGmsFiles(
    filesRoot: File,
    private val directorySync: (File) -> Unit,
    private val issues: GmsDataIssueRecorder,
) {
    private val groupsRoot = File(filesRoot, "groups")

    fun groupDirectories(): List<File> = groupsRoot.listFiles().orEmpty()
        .filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
        .sortedBy(File::getName)

    fun profileFile(groupId: GmsGroupId): File = File(gmsDirectory(groupId), "profile.properties")

    fun operationsDirectory(groupId: GmsGroupId): File = File(gmsDirectory(groupId), "operations")

    fun evidenceDirectory(groupId: GmsGroupId): File = File(gmsDirectory(groupId), "evidence")

    fun <T> read(
        groupId: GmsGroupId,
        file: File,
        decode: (Properties) -> T,
        validate: (T) -> Unit = {},
    ): T = runCatching {
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        decode(properties).also { decoded ->
            val persistedGroupId = when (decoded) {
                is GmsProfile -> decoded.groupId
                is GmsOperation -> decoded.groupId
                is GmsCapabilityEvidence -> decoded.groupId
                else -> error("Unsupported GMS metadata type")
            }
            require(persistedGroupId == groupId) { "group id does not match directory" }
            validate(decoded)
        }
    }.getOrElse { error ->
        issues.record(groupId.value, file.name)
        throw GmsDataCorruptionException(groupId.value, file.name, error)
    }

    fun write(groupId: GmsGroupId, destination: File, fields: Map<String, String>) {
        validateExistingGroup(groupId)
        val parent = destination.parentFile ?: error("GMS metadata has no parent")
        ensureDirectory(parent)
        val replacement = File(parent, ".${destination.name}-${UUID.randomUUID()}.tmp")
        try {
            val properties = Properties().apply { fields.forEach(::setProperty) }
            FileOutputStream(replacement).use { output ->
                properties.store(output, "AppTwin GMS compatibility state")
                output.fd.sync()
            }
            Files.move(
                replacement.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            // The file bytes were fsynced before rename; fsyncing the parent makes the rename durable.
            directorySync(parent)
        } finally {
            replacement.delete()
        }
    }

    fun remove(destination: File) {
        if (!destination.exists()) return
        check(destination.delete()) { "Unable to remove GMS operation ${destination.name}" }
        destination.parentFile?.let(directorySync)
    }

    private fun gmsDirectory(groupId: GmsGroupId): File {
        validateGroup(groupId)
        return File(File(File(groupsRoot, groupId.value), "data"), "gms")
    }

    private fun validateGroup(groupId: GmsGroupId) {
        require(UUID.fromString(groupId.value).toString() == groupId.value) {
            "group id must be a canonical UUID"
        }
    }

    private fun validateExistingGroup(groupId: GmsGroupId) {
        validateGroup(groupId)
        val directory = File(groupsRoot, groupId.value)
        val metadata = File(directory, "group.properties")
        require(directory.isDirectory && metadata.isFile) {
            "group does not exist or has no canonical metadata"
        }
        val groupFields = Properties().apply { FileInputStream(metadata).use(::load) }
        require(groupFields.getProperty("id") == groupId.value) {
            "group metadata does not match directory"
        }
        require(
            groupFields.getProperty("schemaVersion")?.toIntOrNull() ==
                CURRENT_GROUP_SCHEMA_VERSION,
        ) {
            "group metadata schema is unsupported"
        }
        require(File(directory, "data").isDirectory) { "group data directory is missing" }
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        val parent = directory.parentFile ?: error("Directory has no parent")
        ensureDirectory(parent)
        check(directory.mkdir()) { "Unable to create GMS data directory ${directory.name}" }
        directorySync(parent)
        directorySync(directory)
    }

    companion object {
        fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}

private object ProfileCodec {
    fun encode(value: GmsProfile): Map<String, String> = buildMap {
        put("schemaVersion", "1")
        put("groupId", value.groupId.value)
        put("desiredState", value.desiredState.name)
        put("observedState", value.observedState.name)
        put("networkConsent", value.networkConsent.name)
        put("generation", value.generation.toString())
        value.observedReleaseId?.let { put("observedReleaseId", it) }
        value.failureCode?.let { put("failureCode", it) }
    }

    fun decode(fields: Properties): GmsProfile {
        requireSchema(fields)
        return GmsProfile(
            groupId = GmsGroupId(fields.required("groupId")),
            desiredState = GmsDesiredState.valueOf(fields.required("desiredState")),
            observedState = GmsObservedState.valueOf(fields.required("observedState")),
            networkConsent = GmsNetworkConsent.valueOf(fields.required("networkConsent")),
            observedReleaseId = fields.getProperty("observedReleaseId"),
            generation = fields.required("generation").toLong(),
            failureCode = fields.getProperty("failureCode"),
        )
    }
}

private object OperationCodec {
    fun encode(value: GmsOperation): Map<String, String> = buildMap {
        put("schemaVersion", "1")
        put("id", value.id)
        put("groupId", value.groupId.value)
        put("kind", value.kind.name)
        put("targetDesiredState", value.targetDesiredState.name)
        put("phase", value.phase.name)
        put("startedAtEpochMillis", value.startedAtEpochMillis.toString())
        put("updatedAtEpochMillis", value.updatedAtEpochMillis.toString())
        put("attempt", value.attempt.toString())
        value.targetReleaseId?.let { put("targetReleaseId", it) }
        value.failureCode?.let { put("failureCode", it) }
    }

    fun decode(fields: Properties): GmsOperation {
        requireSchema(fields)
        return GmsOperation(
            id = fields.required("id"),
            groupId = GmsGroupId(fields.required("groupId")),
            kind = GmsOperationKind.valueOf(fields.required("kind")),
            targetDesiredState = GmsDesiredState.valueOf(fields.required("targetDesiredState")),
            targetReleaseId = fields.getProperty("targetReleaseId"),
            phase = GmsOperationPhase.valueOf(fields.required("phase")),
            startedAtEpochMillis = fields.required("startedAtEpochMillis").toLong(),
            updatedAtEpochMillis = fields.required("updatedAtEpochMillis").toLong(),
            attempt = fields.required("attempt").toInt(),
            failureCode = fields.getProperty("failureCode"),
        )
    }
}

private object EvidenceCodec {
    fun encode(value: GmsCapabilityEvidence): Map<String, String> = buildMap {
        put("schemaVersion", "1")
        put("evidenceId", value.evidenceId)
        put("groupId", value.groupId.value)
        put("releaseId", value.releaseId)
        put("capability", value.capability.name)
        put("tier", value.tier.name)
        put("outcome", value.outcome.name)
        put("observedAtEpochMillis", value.observedAtEpochMillis.toString())
        put("androidApi", value.androidApi.toString())
        put("appTwinVersion", value.appTwinVersion)
        put("fixtureOrClientVersion", value.fixtureOrClientVersion)
        value.failureCode?.let { put("failureCode", it) }
    }

    fun decode(fields: Properties): GmsCapabilityEvidence {
        requireSchema(fields)
        return GmsCapabilityEvidence(
            evidenceId = fields.required("evidenceId"),
            groupId = GmsGroupId(fields.required("groupId")),
            releaseId = fields.required("releaseId"),
            capability = GmsCapability.valueOf(fields.required("capability")),
            tier = GmsEvidenceTier.valueOf(fields.required("tier")),
            outcome = GmsEvidenceOutcome.valueOf(fields.required("outcome")),
            observedAtEpochMillis = fields.required("observedAtEpochMillis").toLong(),
            androidApi = fields.required("androidApi").toInt(),
            appTwinVersion = fields.required("appTwinVersion"),
            fixtureOrClientVersion = fields.required("fixtureOrClientVersion"),
            failureCode = fields.getProperty("failureCode"),
        )
    }
}

private fun requireSchema(fields: Properties) {
    require(fields.required("schemaVersion") == "1") { "Unsupported schemaVersion" }
}

private fun Properties.required(name: String): String =
    requireNotNull(getProperty(name)) { "Missing $name" }

private fun evidenceFileName(evidenceId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(evidenceId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return "$digest.properties"
}
