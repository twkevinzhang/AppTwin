package org.apptwin.revision

import java.io.File
import java.io.FileInputStream
import java.util.Properties

internal sealed interface ActiveRevisionLookupResult {
    data object Absent : ActiveRevisionLookupResult

    data class Found(
        val summary: ActiveRevisionSummary,
        val directory: File,
        val metadata: Properties,
    ) : ActiveRevisionLookupResult

    data class Corrupt(val reason: String) : ActiveRevisionLookupResult
}

internal class ActiveRevisionMetadataException(message: String) : IllegalStateException(message)

/** Distinguishes a never-imported package from a damaged active pointer or revision. */
internal class FileActiveRevisionLookup(private val revisionsRoot: File) {
    fun read(packageName: String): ActiveRevisionLookupResult {
        val packageRoot = File(revisionsRoot, packageName)
        if (!packageRoot.exists()) return ActiveRevisionLookupResult.Absent
        if (!packageRoot.isDirectory) return corrupt("revision root is not a directory")

        val pointer = File(packageRoot, ACTIVE_POINTER)
        if (!pointer.exists()) return ActiveRevisionLookupResult.Absent
        if (!pointer.isFile) return corrupt("active pointer is not a file")
        val revisionId = runCatching { pointer.readText().trim() }
            .getOrElse { return corrupt("unable to read active pointer: ${it.safeMessage()}") }
        if (revisionId.isEmpty()) return corrupt("active pointer is empty")

        val directory = File(packageRoot, revisionId)
        val canonicalRoot = runCatching { packageRoot.canonicalFile }
            .getOrElse { return corrupt("unable to resolve revision root: ${it.safeMessage()}") }
        val canonicalDirectory = runCatching { directory.canonicalFile }
            .getOrElse { return corrupt("unable to resolve active revision: ${it.safeMessage()}") }
        if (canonicalDirectory.parentFile != canonicalRoot || !canonicalDirectory.isDirectory) {
            return corrupt("active revision directory is missing or outside its package root")
        }
        if (!File(canonicalDirectory, BASE_APK).isFile) return corrupt("active base APK is missing")

        val metadataFile = File(canonicalDirectory, METADATA)
        if (!metadataFile.isFile) return corrupt("active revision metadata is missing")
        val metadata = runCatching {
            Properties().apply { FileInputStream(metadataFile).use(::load) }
        }.getOrElse { return corrupt("unable to read active revision metadata: ${it.safeMessage()}") }
        val storedPackage = metadata.getProperty("packageName")
        if (storedPackage != packageName) return corrupt("active metadata package does not match")
        if (metadata.getProperty("revisionId") != revisionId) {
            return corrupt("active metadata revision does not match pointer")
        }
        val versionCode = metadata.getProperty("versionCode")?.toLongOrNull()
            ?: return corrupt("active metadata versionCode is missing or invalid")
        if (metadata.getProperty("currentSigner").isNullOrBlank()) {
            return corrupt("active metadata currentSigner is missing")
        }
        return ActiveRevisionLookupResult.Found(
            summary = ActiveRevisionSummary(packageName, versionCode, revisionId),
            directory = canonicalDirectory,
            metadata = metadata,
        )
    }

    private fun corrupt(reason: String) = ActiveRevisionLookupResult.Corrupt(reason)

    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    private companion object {
        const val ACTIVE_POINTER = "active"
        const val BASE_APK = "base.apk"
        const val METADATA = "metadata.properties"
    }
}
