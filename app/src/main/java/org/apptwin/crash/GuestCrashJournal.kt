package org.apptwin.crash

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** Synchronous, bounded storage for guest crashes that must survive host process death. */
class GuestCrashJournal internal constructor(
    noBackupFilesDir: File,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val saltGenerator: () -> ByteArray,
) {
    constructor(context: Context) : this(
        noBackupFilesDir = context.applicationContext.noBackupFilesDir,
        clock = System::currentTimeMillis,
        idGenerator = { UUID.randomUUID().toString() },
        saltGenerator = { ByteArray(SALT_BYTE_COUNT).also(SecureRandom()::nextBytes) },
    )

    internal constructor(
        noBackupFilesDir: File,
        clock: () -> Long = System::currentTimeMillis,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
    ) : this(
        noBackupFilesDir,
        clock,
        idGenerator,
        { ByteArray(SALT_BYTE_COUNT).also(SecureRandom()::nextBytes) },
    )

    private val root = File(noBackupFilesDir, DIRECTORY_NAME)

    fun append(
        packageName: String,
        processName: String,
        throwable: Throwable,
    ): GuestCrashRecord? = append(
        packageName,
        processName,
        throwable,
        clock(),
    )

    /** Persists the redacted record before returning; failures are best-effort during a crash. */
    fun append(
        packageName: String,
        processName: String,
        throwable: Throwable,
        capturedAtEpochMillis: Long,
    ): GuestCrashRecord? = runCatching {
        synchronized(PROCESS_LOCK) {
            ensureRoot()
            withJournalLock {
                val now = clock()
                if (isExpired(capturedAtEpochMillis, now)) return@withJournalLock null
                val existing = readAndClean(now).sortedWith(RECORD_ORDER).toMutableList()
                while (existing.size >= MAX_RECORDS) {
                    deleteRecord(existing.removeAt(0).id)
                }
                val salt = loadOrCreateSalt()
                val record = GuestCrashRecord(
                    id = UUID.fromString(idGenerator()).toString(),
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    packageNameHash = GuestCrashRedactor.hashIdentifier(salt, packageName),
                    processNameHash = GuestCrashRedactor.hashIdentifier(salt, processName),
                    exceptionClassName = GuestCrashRedactor.exceptionClassName(throwable),
                    stackFrames = GuestCrashRedactor.stackFrames(throwable),
                )
                writeRecord(record)
                record
            }
        }
    }.getOrNull()

    /** Returns oldest-first records, removing expired, oversized, and corrupt entries. */
    fun pending(): List<GuestCrashRecord> = runCatching {
        synchronized(PROCESS_LOCK) {
            if (!root.isDirectory) return@synchronized emptyList()
            withJournalLock {
                readAndClean(clock()).sortedWith(RECORD_ORDER)
            }
        }
    }.getOrDefault(emptyList())

    /** Idempotently deletes a record after the backend has accepted it into its local queue. */
    fun acknowledge(recordId: String): Boolean = runCatching {
        val canonicalId = UUID.fromString(recordId).toString()
        synchronized(PROCESS_LOCK) {
            if (!root.isDirectory) return@synchronized true
            withJournalLock { deleteRecord(canonicalId) }
        }
    }.getOrDefault(false)

    private fun readAndClean(now: Long): List<GuestCrashRecord> {
        val records = mutableListOf<GuestCrashRecord>()
        recordFiles().forEach { file ->
            val record = runCatching { readRecord(file) }.getOrNull()
            when {
                record == null -> file.delete()
                isExpired(record.capturedAtEpochMillis, now) -> file.delete()
                else -> records += record
            }
        }
        return records
    }

    private fun readRecord(file: File): GuestCrashRecord {
        require(file.length() in 1..MAX_RECORD_BYTES.toLong()) { "Invalid guest crash size" }
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        val frameCount = required(properties, "frameCount").toInt()
        require(frameCount in 0..GuestCrashRedactor.MAX_STACK_FRAMES) { "Invalid frame count" }
        val expectedKeys = mutableSetOf(
            "version",
            "id",
            "capturedAtEpochMillis",
            "packageNameHash",
            "processNameHash",
            "exceptionClassName",
            "frameCount",
        )
        repeat(frameCount) { index ->
            expectedKeys += "frame.$index.className"
            expectedKeys += "frame.$index.methodName"
            expectedKeys += "frame.$index.fileName"
            expectedKeys += "frame.$index.lineNumber"
        }
        require(properties.stringPropertyNames() == expectedKeys) { "Unexpected guest crash fields" }
        require(required(properties, "version") == FORMAT_VERSION) { "Unknown guest crash version" }
        val id = UUID.fromString(required(properties, "id")).toString()
        require(file.name == "$id.$RECORD_EXTENSION") { "Guest crash id mismatch" }
        val packageHash = required(properties, "packageNameHash")
        val processHash = required(properties, "processNameHash")
        require(HASH.matches(packageHash) && HASH.matches(processHash)) { "Invalid identifier hash" }
        val exceptionClassName = required(properties, "exceptionClassName")
        require(SAFE_CLASS_NAME.matches(exceptionClassName)) { "Invalid exception class" }
        val frames = (0 until frameCount).map { index ->
            val className = required(properties, "frame.$index.className")
            val methodName = required(properties, "frame.$index.methodName")
            val fileName = required(properties, "frame.$index.fileName").ifBlank { null }
            val lineNumber = required(properties, "frame.$index.lineNumber").toInt()
            require(SAFE_CLASS_NAME.matches(className) && SAFE_METHOD_NAME.matches(methodName)) {
                "Invalid stack symbol"
            }
            require(fileName == null || SAFE_FILE_NAME.matches(fileName)) {
                "Invalid stack file"
            }
            require(lineNumber == -1 || lineNumber >= 1) { "Invalid stack line" }
            GuestCrashStackFrame(className, methodName, fileName, lineNumber)
        }
        return GuestCrashRecord(
            id = id,
            capturedAtEpochMillis = required(properties, "capturedAtEpochMillis").toLong(),
            packageNameHash = packageHash,
            processNameHash = processHash,
            exceptionClassName = exceptionClassName,
            stackFrames = frames,
        )
    }

    private fun writeRecord(record: GuestCrashRecord) {
        val properties = Properties().apply {
            setProperty("version", FORMAT_VERSION)
            setProperty("id", record.id)
            setProperty("capturedAtEpochMillis", record.capturedAtEpochMillis.toString())
            setProperty("packageNameHash", record.packageNameHash)
            setProperty("processNameHash", record.processNameHash)
            setProperty("exceptionClassName", record.exceptionClassName)
            setProperty("frameCount", record.stackFrames.size.toString())
            record.stackFrames.forEachIndexed { index, frame ->
                setProperty("frame.$index.className", frame.className)
                setProperty("frame.$index.methodName", frame.methodName)
                setProperty("frame.$index.fileName", frame.fileName.orEmpty())
                setProperty("frame.$index.lineNumber", frame.lineNumber.toString())
            }
        }
        val bytes = ByteArrayOutputStream().use { output ->
            properties.store(output, "AppTwin redacted guest crash")
            output.toByteArray()
        }
        require(bytes.size <= MAX_RECORD_BYTES) { "Guest crash record exceeds size limit" }
        val destination = recordFile(record.id)
        val replacement = File(root, ".${record.id}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(replacement).use { output ->
                output.write(bytes)
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

    private fun loadOrCreateSalt(): ByteArray {
        val saltFile = File(root, SALT_FILE_NAME)
        if (saltFile.isFile) {
            runCatching {
                val salt = Base64.getUrlDecoder().decode(saltFile.readText(Charsets.US_ASCII))
                require(salt.size == SALT_BYTE_COUNT)
                return salt
            }
            saltFile.delete()
        }
        val salt = saltGenerator()
        require(salt.size == SALT_BYTE_COUNT) { "Invalid installation salt" }
        val replacement = File(root, ".$SALT_FILE_NAME-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(replacement).use { output ->
                output.write(Base64.getUrlEncoder().withoutPadding().encode(salt))
                output.fd.sync()
            }
            Files.move(
                replacement.toPath(),
                saltFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            replacement.delete()
        }
        return salt
    }

    private fun ensureRoot() {
        check(root.isDirectory || root.mkdirs()) { "Unable to create guest crash journal" }
    }

    private fun <T> withJournalLock(block: () -> T): T {
        val lockFile = File(root, LOCK_FILE_NAME)
        return FileOutputStream(lockFile, true).channel.use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun recordFiles(): List<File> = root.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == RECORD_EXTENSION && !it.name.startsWith(".") }

    private fun recordFile(id: String): File = File(root, "$id.$RECORD_EXTENSION")

    private fun deleteRecord(id: String): Boolean {
        val file = recordFile(id)
        return !file.exists() || file.delete()
    }

    private fun required(properties: Properties, key: String): String =
        requireNotNull(properties.getProperty(key)) { "Missing $key" }

    private fun isExpired(capturedAtEpochMillis: Long, now: Long): Boolean =
        capturedAtEpochMillis <= now - TTL_MILLIS

    internal companion object {
        const val DIRECTORY_NAME = "guest-crashes"
        const val MAX_RECORDS = 20
        const val MAX_RECORD_BYTES = 256 * 1024
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val FORMAT_VERSION = "1"
        private const val RECORD_EXTENSION = "properties"
        private const val LOCK_FILE_NAME = ".journal.lock"
        private const val SALT_FILE_NAME = ".install-salt"
        private const val SALT_BYTE_COUNT = 32
        private val HASH = Regex("[0-9a-f]{64}")
        private val SAFE_CLASS_NAME = Regex("[A-Za-z0-9_.$<>-]{1,256}")
        private val SAFE_METHOD_NAME = Regex("[A-Za-z0-9_.$<>-]{1,128}")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9_.$-]{1,128}")
        private val RECORD_ORDER = compareBy(GuestCrashRecord::capturedAtEpochMillis, GuestCrashRecord::id)
        private val PROCESS_LOCK = Any()
    }
}
