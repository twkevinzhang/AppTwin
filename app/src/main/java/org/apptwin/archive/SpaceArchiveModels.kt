package org.apptwin.archive

import java.io.File

const val SPACE_ARCHIVE_SCHEMA_VERSION = 1

enum class SpaceArchiveAppState {
    ADDED,
    INSTALLING,
    ENABLED,
    DISABLED,
    SOURCE_MISSING,
    FAILED,
}

data class SpaceArchiveApp(
    val packageName: String,
    val addedAt: Long,
    val state: SpaceArchiveAppState,
    val revisionId: String,
)

data class SpaceArchiveManifest(
    val archiveId: String,
    val sourceSpaceId: String,
    val name: String,
    val createdAt: Long,
    val apps: List<SpaceArchiveApp>,
    val gmsEnabled: Boolean,
    val custodianKeyspaceId: String,
    val schemaVersion: Int = SPACE_ARCHIVE_SCHEMA_VERSION,
)

/** A named directory tree included beneath `payload/<label>/` in the archive. */
data class SpaceArchiveSource(
    val label: String,
    val root: File,
)

data class SpaceArchiveFile(
    val path: String,
    val size: Long,
    val sha256: String,
)

data class SpaceArchiveWriteResult(
    val manifest: SpaceArchiveManifest,
    val files: List<SpaceArchiveFile>,
)

data class SpaceArchiveReadResult(
    val manifest: SpaceArchiveManifest,
    val files: List<SpaceArchiveFile>,
    val stagingRoot: File,
)

data class SpaceArchiveLimits(
    val maxPathLength: Int = 512,
    val maxEntryCount: Int = 20_000,
    val maxSingleFileBytes: Long = 512L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 4L * 1024L * 1024L * 1024L,
    val maxManifestBytes: Long = 1024L * 1024L,
    val maxIndexBytes: Long = 8L * 1024L * 1024L,
    val maxArchiveBytes: Long = 8L * 1024L * 1024L * 1024L,
) {
    init {
        require(maxPathLength in 1..4096) { "Invalid archive path length limit" }
        require(maxEntryCount >= 2) { "Archive entry limit must allow metadata entries" }
        require(maxSingleFileBytes >= 0) { "Single-file limit must not be negative" }
        require(maxTotalUncompressedBytes >= 0) { "Total-size limit must not be negative" }
        require(maxManifestBytes > 0) { "Manifest limit must be positive" }
        require(maxIndexBytes > 0) { "Index limit must be positive" }
        require(maxArchiveBytes > 0) { "Archive-size limit must be positive" }
    }
}

class SpaceArchiveException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
