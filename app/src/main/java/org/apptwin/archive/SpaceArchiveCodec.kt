package org.apptwin.archive

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SpaceArchiveWriter {
    fun write(
        output: OutputStream,
        manifest: SpaceArchiveManifest,
        sources: List<SpaceArchiveSource>,
    ): SpaceArchiveWriteResult {
        validateManifest(manifest)
        val prepared = prepareSources(sources)
        val files = mutableListOf<SpaceArchiveFile>()
        try {
            val zip = ZipOutputStream(BufferedOutputStream(output))
            writeEntry(zip, MANIFEST_ENTRY, encodeManifest(manifest))
            prepared.forEach { source ->
                source.files.forEach { file ->
                    val archivePath = "payload/${source.label}/${source.root.relativize(file).toArchivePath()}"
                    validateArchivePath(archivePath, DEFAULT_WRITER_PATH_LIMIT)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.putNextEntry(newEntry(archivePath))
                    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            zip.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size = Math.addExact(size, count.toLong())
                        }
                    }
                    zip.closeEntry()
                    ensureUnchangedRegularFile(file, size)
                    files += SpaceArchiveFile(archivePath, size, digest.digest().toHex())
                }
            }
            val sorted = files.sortedBy(SpaceArchiveFile::path)
            writeEntry(zip, INDEX_ENTRY, encodeIndex(sorted))
            zip.finish()
            zip.flush()
            return SpaceArchiveWriteResult(manifest, sorted)
        } catch (failure: SpaceArchiveException) {
            throw failure
        } catch (failure: Exception) {
            throw SpaceArchiveException("Space archive could not be written", failure)
        }
    }

    fun write(
        destination: File,
        manifest: SpaceArchiveManifest,
        sources: List<SpaceArchiveSource>,
    ): SpaceArchiveWriteResult {
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw SpaceArchiveException("Archive destination directory is unavailable")
            }
        }
        return FileOutputStream(destination).use { write(it, manifest, sources) }
    }

    private fun prepareSources(sources: List<SpaceArchiveSource>): List<PreparedSource> {
        if (sources.map(SpaceArchiveSource::label).distinct().size != sources.size) {
            throw SpaceArchiveException("Archive source labels must be unique")
        }
        return sources.map { source ->
            validateLabel(source.label)
            val root = source.root.toPath()
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw SpaceArchiveException("Archive source root must be a regular directory: ${source.label}")
            }
            val files = mutableListOf<Path>()
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(dir) || !attrs.isDirectory) {
                        throw SpaceArchiveException("Archive source contains an invalid directory")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Guest launches create generated `lib` links back to the active revision.
                    // They contain no Space-owned state and are rebuilt by the runtime, so do not
                    // encode host-specific link targets in an otherwise portable archive.
                    if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                    if (!attrs.isRegularFile) {
                        throw SpaceArchiveException("Archive source contains a non-regular file")
                    }
                    files.add(file)
                    return FileVisitResult.CONTINUE
                }
            })
            PreparedSource(source.label, root, files.sortedBy { root.relativize(it).toArchivePath() })
        }
    }

    private fun ensureUnchangedRegularFile(path: Path, bytesRead: Long) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() != bytesRead) {
            throw SpaceArchiveException("Archive source changed while it was being read")
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(newEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun newEntry(name: String) = ZipEntry(name).apply { time = 0L }

    private data class PreparedSource(val label: String, val root: Path, val files: List<Path>)
}

class SpaceArchiveReader(private val limits: SpaceArchiveLimits = SpaceArchiveLimits()) {
    fun readAndExtract(input: InputStream, stagingRoot: File): SpaceArchiveReadResult {
        if (Files.exists(stagingRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SpaceArchiveException("Archive staging root must not already exist")
        }
        val parent = stagingRoot.absoluteFile.parentFile
            ?: throw SpaceArchiveException("Archive staging root needs a parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw SpaceArchiveException("Archive staging parent is unavailable")
        }
        val spool = Files.createTempFile(parent.toPath(), ".apptwin-archive-", ".zip").toFile()
        var stagingCreated = false
        try {
            spoolInput(input, spool)
            val verified = verify(spool)
            if (!stagingRoot.mkdir()) throw SpaceArchiveException("Archive staging root cannot be created")
            stagingCreated = true
            extractVerified(spool, stagingRoot, verified)
            return SpaceArchiveReadResult(verified.manifest, verified.files.values.sortedBy { it.path }, stagingRoot)
        } catch (failure: SpaceArchiveException) {
            if (stagingCreated) {
                runCatching { deleteTree(stagingRoot.toPath()) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        } catch (failure: Exception) {
            val wrapped = SpaceArchiveException("Space archive could not be read", failure)
            if (stagingCreated) {
                runCatching { deleteTree(stagingRoot.toPath()) }
                    .exceptionOrNull()
                    ?.let(wrapped::addSuppressed)
            }
            throw wrapped
        } finally {
            spool.delete()
        }
    }

    fun readAndExtract(source: File, stagingRoot: File): SpaceArchiveReadResult =
        FileInputStream(source).use { readAndExtract(it, stagingRoot) }

    private fun spoolInput(input: InputStream, destination: File) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = checkedTotal(total, count.toLong(), limits.maxArchiveBytes, "Compressed archive")
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }

    private fun verify(archive: File): VerifiedArchive {
        val seen = mutableSetOf<String>()
        val actualFiles = linkedMapOf<String, SpaceArchiveFile>()
        var manifestBytes: ByteArray? = null
        var indexBytes: ByteArray? = null
        var entryCount = 0
        var totalUncompressed = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > limits.maxEntryCount) {
                    throw SpaceArchiveException("Archive has too many entries")
                }
                val name = entry.name ?: throw SpaceArchiveException("Archive entry has no name")
                validateArchivePath(name, limits.maxPathLength)
                if (!seen.add(name)) throw SpaceArchiveException("Duplicate archive entry: $name")
                if (entry.isDirectory) throw SpaceArchiveException("Archive directory entries are not permitted")
                when (name) {
                    MANIFEST_ENTRY -> {
                        val bytes = readBounded(zip, limits.maxManifestBytes, "Archive manifest")
                        totalUncompressed = checkedTotal(
                            totalUncompressed,
                            bytes.size.toLong(),
                            limits.maxTotalUncompressedBytes,
                            "Archive",
                        )
                        manifestBytes = bytes
                    }
                    INDEX_ENTRY -> {
                        val bytes = readBounded(zip, limits.maxIndexBytes, "Archive index")
                        totalUncompressed = checkedTotal(
                            totalUncompressed,
                            bytes.size.toLong(),
                            limits.maxTotalUncompressedBytes,
                            "Archive",
                        )
                        indexBytes = bytes
                    }
                    else -> {
                        validatePayloadPath(name)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size = checkedTotal(size, count.toLong(), limits.maxSingleFileBytes, "Archive file")
                            totalUncompressed = checkedTotal(
                                totalUncompressed,
                                count.toLong(),
                                limits.maxTotalUncompressedBytes,
                                "Archive",
                            )
                            digest.update(buffer, 0, count)
                        }
                        actualFiles[name] = SpaceArchiveFile(name, size, digest.digest().toHex())
                    }
                }
                zip.closeEntry()
            }
        }
        val encodedManifest = manifestBytes ?: throw SpaceArchiveException("Archive manifest is missing")
        val encodedIndex = indexBytes ?: throw SpaceArchiveException("Archive index is missing")
        val manifest = decodeManifest(encodedManifest)
        val expectedFiles = decodeIndex(encodedIndex)
        if (expectedFiles.size + 2 > limits.maxEntryCount) {
            throw SpaceArchiveException("Archive index has too many files")
        }
        if (expectedFiles.keys != actualFiles.keys) {
            throw SpaceArchiveException("Archive index does not match payload entries")
        }
        expectedFiles.forEach { (path, expected) ->
            val actual = actualFiles.getValue(path)
            if (expected.size != actual.size || expected.sha256 != actual.sha256) {
                throw SpaceArchiveException("Archive payload verification failed: $path")
            }
        }
        return VerifiedArchive(manifest, expectedFiles, encodedManifest, encodedIndex)
    }

    private fun extractVerified(archive: File, stagingRoot: File, verified: VerifiedArchive) {
        val stagingPath = stagingRoot.toPath().toAbsolutePath().normalize()
        val extracted = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when (name) {
                    MANIFEST_ENTRY -> if (!readBounded(
                            zip,
                            limits.maxManifestBytes,
                            "Archive manifest",
                        ).contentEquals(verified.manifestBytes)) {
                        throw SpaceArchiveException("Archive changed after verification")
                    }
                    INDEX_ENTRY -> if (!readBounded(
                            zip,
                            limits.maxIndexBytes,
                            "Archive index",
                        ).contentEquals(verified.indexBytes)) {
                        throw SpaceArchiveException("Archive changed after verification")
                    }
                    else -> {
                        val expected = verified.files[name]
                            ?: throw SpaceArchiveException("Archive changed after verification")
                        val destination = stagingPath.resolve(name).normalize()
                        if (!destination.startsWith(stagingPath)) {
                            throw SpaceArchiveException("Archive entry escapes staging root")
                        }
                        Files.createDirectories(destination.parent)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        Files.newOutputStream(destination).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                size = checkedTotal(
                                    size,
                                    count.toLong(),
                                    limits.maxSingleFileBytes,
                                    "Archive file",
                                )
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                        }
                        if (size != expected.size || digest.digest().toHex() != expected.sha256) {
                            throw SpaceArchiveException("Archive changed after verification")
                        }
                        extracted += name
                    }
                }
                zip.closeEntry()
            }
        }
        if (extracted != verified.files.keys) {
            throw SpaceArchiveException("Archive extraction is incomplete")
        }
    }

    private fun readBounded(input: InputStream, maximum: Long, label: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size = checkedTotal(size, count.toLong(), maximum, label)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class VerifiedArchive(
        val manifest: SpaceArchiveManifest,
        val files: Map<String, SpaceArchiveFile>,
        val manifestBytes: ByteArray,
        val indexBytes: ByteArray,
    )
}

private fun encodeManifest(manifest: SpaceArchiveManifest): ByteArray = StrictJson.encode(
    JsonValue.Object(linkedMapOf(
        "schemaVersion" to JsonValue.LongValue(manifest.schemaVersion.toLong()),
        "archiveId" to JsonValue.StringValue(manifest.archiveId),
        "sourceSpaceId" to JsonValue.StringValue(manifest.sourceSpaceId),
        "name" to JsonValue.StringValue(manifest.name),
        "createdAt" to JsonValue.LongValue(manifest.createdAt),
        "apps" to JsonValue.Array(manifest.apps.map { app ->
            JsonValue.Object(linkedMapOf(
                "packageName" to JsonValue.StringValue(app.packageName),
                "addedAt" to JsonValue.LongValue(app.addedAt),
                "state" to JsonValue.StringValue(app.state.name),
                "revisionId" to JsonValue.StringValue(app.revisionId),
            ))
        }),
        "gmsEnabled" to JsonValue.BooleanValue(manifest.gmsEnabled),
        "custodianKeyspaceId" to JsonValue.StringValue(manifest.custodianKeyspaceId),
    )),
)

private fun decodeManifest(bytes: ByteArray): SpaceArchiveManifest {
    val fields = StrictJson.parse(bytes).requireObject("manifest")
    fields.requireExactKeys(
        "manifest",
        "schemaVersion",
        "archiveId",
        "sourceSpaceId",
        "name",
        "createdAt",
        "apps",
        "gmsEnabled",
        "custodianKeyspaceId",
    )
    val apps = fields.getValue("apps").requireArray("apps").mapIndexed { index, value ->
        val app = value.requireObject("apps[$index]")
        app.requireExactKeys("apps[$index]", "packageName", "addedAt", "state", "revisionId")
        val stateName = app.getValue("state").requireString("apps[$index].state")
        val state = runCatching { SpaceArchiveAppState.valueOf(stateName) }.getOrNull()
            ?: throw SpaceArchiveException("apps[$index].state is invalid")
        SpaceArchiveApp(
            packageName = app.getValue("packageName").requireString("apps[$index].packageName"),
            addedAt = app.getValue("addedAt").requireLong("apps[$index].addedAt"),
            state = state,
            revisionId = app.getValue("revisionId").requireString("apps[$index].revisionId"),
        )
    }
    val schema = fields.getValue("schemaVersion").requireLong("schemaVersion")
    if (schema != SPACE_ARCHIVE_SCHEMA_VERSION.toLong()) {
        throw SpaceArchiveException("Unsupported Space archive schema: $schema")
    }
    return SpaceArchiveManifest(
        archiveId = fields.getValue("archiveId").requireString("archiveId"),
        sourceSpaceId = fields.getValue("sourceSpaceId").requireString("sourceSpaceId"),
        name = fields.getValue("name").requireString("name"),
        createdAt = fields.getValue("createdAt").requireLong("createdAt"),
        apps = apps,
        gmsEnabled = fields.getValue("gmsEnabled").requireBoolean("gmsEnabled"),
        custodianKeyspaceId = fields.getValue("custodianKeyspaceId")
            .requireString("custodianKeyspaceId"),
        schemaVersion = schema.toInt(),
    ).also(::validateManifest)
}

private fun encodeIndex(files: List<SpaceArchiveFile>): ByteArray = StrictJson.encode(
    JsonValue.Object(linkedMapOf(
        "schemaVersion" to JsonValue.LongValue(SPACE_ARCHIVE_SCHEMA_VERSION.toLong()),
        "files" to JsonValue.Array(files.map { file ->
            JsonValue.Object(linkedMapOf(
                "path" to JsonValue.StringValue(file.path),
                "size" to JsonValue.LongValue(file.size),
                "sha256" to JsonValue.StringValue(file.sha256),
            ))
        }),
    )),
)

private fun decodeIndex(bytes: ByteArray): Map<String, SpaceArchiveFile> {
    val fields = StrictJson.parse(bytes).requireObject("index")
    fields.requireExactKeys("index", "schemaVersion", "files")
    val schema = fields.getValue("schemaVersion").requireLong("index.schemaVersion")
    if (schema != SPACE_ARCHIVE_SCHEMA_VERSION.toLong()) {
        throw SpaceArchiveException("Unsupported Space archive index schema: $schema")
    }
    val files = linkedMapOf<String, SpaceArchiveFile>()
    fields.getValue("files").requireArray("index.files").forEachIndexed { index, value ->
        val item = value.requireObject("index.files[$index]")
        item.requireExactKeys("index.files[$index]", "path", "size", "sha256")
        val path = item.getValue("path").requireString("index.files[$index].path")
        validatePayloadPath(path)
        val size = item.getValue("size").requireLong("index.files[$index].size")
        if (size < 0) throw SpaceArchiveException("index.files[$index].size is invalid")
        val digest = item.getValue("sha256").requireString("index.files[$index].sha256")
        if (!SHA256.matches(digest)) {
            throw SpaceArchiveException("index.files[$index].sha256 is invalid")
        }
        if (files.put(path, SpaceArchiveFile(path, size, digest)) != null) {
            throw SpaceArchiveException("Duplicate path in archive index: $path")
        }
    }
    return files
}

private fun validateManifest(manifest: SpaceArchiveManifest) {
    if (manifest.schemaVersion != SPACE_ARCHIVE_SCHEMA_VERSION) {
        throw SpaceArchiveException("Unsupported Space archive schema: ${manifest.schemaVersion}")
    }
    requireCanonicalUuid(manifest.archiveId, "archiveId")
    requireCanonicalUuid(manifest.sourceSpaceId, "sourceSpaceId")
    requireCanonicalUuid(manifest.custodianKeyspaceId, "custodianKeyspaceId")
    if (manifest.name.isBlank() || manifest.name.length > 200 || manifest.name.any { it.code < 0x20 }) {
        throw SpaceArchiveException("Space archive name is invalid")
    }
    if (manifest.createdAt < 0) throw SpaceArchiveException("Space archive creation time is invalid")
    if (manifest.apps.map(SpaceArchiveApp::packageName).distinct().size != manifest.apps.size) {
        throw SpaceArchiveException("Space archive contains a duplicate package")
    }
    manifest.apps.forEachIndexed { index, app ->
        if (!PACKAGE_NAME.matches(app.packageName)) {
            throw SpaceArchiveException("apps[$index].packageName is invalid")
        }
        if (app.addedAt < 0) throw SpaceArchiveException("apps[$index].addedAt is invalid")
        if (!REVISION_ID.matches(app.revisionId)) {
            throw SpaceArchiveException("apps[$index].revisionId is invalid")
        }
    }
}

private fun requireCanonicalUuid(value: String, label: String) {
    val canonical = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw SpaceArchiveException("$label is not a UUID")
    }
    if (value != canonical) throw SpaceArchiveException("$label is not a canonical UUID")
}

private fun validateLabel(label: String) {
    if (!SOURCE_LABEL.matches(label)) throw SpaceArchiveException("Archive source label is invalid")
}

private fun validatePayloadPath(path: String) {
    validateArchivePath(path, DEFAULT_WRITER_PATH_LIMIT)
    val parts = path.split('/')
    if (parts.size < 3 || parts[0] != "payload") {
        throw SpaceArchiveException("Archive payload path is invalid: $path")
    }
    validateLabel(parts[1])
}

private fun validateArchivePath(path: String, maxLength: Int) {
    if (path.isEmpty() || path.length > maxLength) {
        throw SpaceArchiveException("Archive entry path length is invalid")
    }
    if (path.any { it.code < 0x20 || it.code == 0x7f }) {
        throw SpaceArchiveException("Archive entry path contains a control character")
    }
    if (path.startsWith('/') || path.startsWith('\\') || path.contains('\\')) {
        throw SpaceArchiveException("Archive entry path is not portable")
    }
    if (WINDOWS_ABSOLUTE_PATH.containsMatchIn(path)) {
        throw SpaceArchiveException("Archive entry path is absolute")
    }
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        throw SpaceArchiveException("Archive entry path contains an unsafe segment")
    }
}

private fun checkedTotal(current: Long, increment: Long, maximum: Long, label: String): Long {
    val updated = try {
        Math.addExact(current, increment)
    } catch (_: ArithmeticException) {
        throw SpaceArchiveException("$label size is too large")
    }
    if (updated > maximum) throw SpaceArchiveException("$label exceeds its size limit")
    return updated
}

private fun Path.toArchivePath(): String = joinToString("/") { it.toString() }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private const val MANIFEST_ENTRY = "manifest.json"
private const val INDEX_ENTRY = "index.json"
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val DEFAULT_WRITER_PATH_LIMIT = 512
private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
private val REVISION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
private val SOURCE_LABEL = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val SHA256 = Regex("[a-f0-9]{64}")
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:")
