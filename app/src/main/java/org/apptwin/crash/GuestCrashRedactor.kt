package org.apptwin.crash

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

internal object GuestCrashRedactor {
    const val MAX_STACK_FRAMES = 128
    private const val MAX_CLASS_NAME_LENGTH = 256
    private const val MAX_METHOD_NAME_LENGTH = 128
    private const val MAX_FILE_NAME_LENGTH = 128
    private const val MAX_CAUSE_DEPTH = 16
    private val SAFE_SYMBOL_CHARACTER = Regex("[A-Za-z0-9_.$<>-]")
    private val SAFE_FILE_CHARACTER = Regex("[A-Za-z0-9_.$-]")

    fun hashIdentifier(salt: ByteArray, value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(salt + byteArrayOf(0) + value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun exceptionClassName(throwable: Throwable): String = sanitizeSymbol(
        diagnosticCause(throwable).javaClass.name,
        MAX_CLASS_NAME_LENGTH,
        "java.lang.Throwable",
    )

    fun stackFrames(throwable: Throwable): List<GuestCrashStackFrame> = diagnosticCause(throwable).stackTrace
        .take(MAX_STACK_FRAMES)
        .map(::stackFrame)

    /** Virtual-runtime wrappers must not hide the guest root cause from issue grouping. */
    private fun diagnosticCause(throwable: Throwable): Throwable {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current = throwable
        repeat(MAX_CAUSE_DEPTH) {
            if (!visited.add(current)) return current
            val next = current.cause ?: return current
            if (next === current || next in visited) return current
            current = next
        }
        return current
    }

    private fun stackFrame(frame: StackTraceElement): GuestCrashStackFrame = GuestCrashStackFrame(
        className = sanitizeSymbol(frame.className, MAX_CLASS_NAME_LENGTH, "unknown.Class"),
        methodName = sanitizeSymbol(frame.methodName, MAX_METHOD_NAME_LENGTH, "unknown"),
        fileName = frame.fileName?.let(::sanitizeFileName),
        lineNumber = frame.lineNumber.takeIf { it >= 1 } ?: -1,
    )

    private fun sanitizeFileName(value: String): String? {
        val basename = value.substringAfterLast('/').substringAfterLast('\\')
        if (basename.isBlank()) return null
        return sanitize(
            basename,
            MAX_FILE_NAME_LENGTH,
            SAFE_FILE_CHARACTER,
        ).ifBlank { null }
    }

    private fun sanitizeSymbol(value: String, maxLength: Int, fallback: String): String = sanitize(
        value,
        maxLength,
        SAFE_SYMBOL_CHARACTER,
    ).ifBlank { fallback }

    private fun sanitize(value: String, maxLength: Int, allowed: Regex): String = buildString {
        value.take(maxLength).forEach { character ->
            append(if (allowed.matches(character.toString())) character else '_')
        }
    }
}
