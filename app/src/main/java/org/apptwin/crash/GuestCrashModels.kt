package org.apptwin.crash

/** A redacted, bounded Java stack frame that is safe to persist locally. */
data class GuestCrashStackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int,
) {
    fun toStackTraceElement(): StackTraceElement = StackTraceElement(
        className,
        methodName,
        fileName,
        lineNumber,
    )
}

/**
 * A replayable guest crash record.
 *
 * Package and process identifiers are installation-scoped hashes. The original Throwable
 * message and arbitrary metadata are deliberately not part of this model.
 */
data class GuestCrashRecord(
    val id: String,
    val capturedAtEpochMillis: Long,
    val packageNameHash: String,
    val processNameHash: String,
    val exceptionClassName: String,
    val stackFrames: List<GuestCrashStackFrame>,
) {
    fun reconstructThrowable(): Throwable = GuestCrashReplayException().also { replay ->
        replay.stackTrace = stackFrames.map(GuestCrashStackFrame::toStackTraceElement).toTypedArray()
    }
}

/** A message-free Throwable used only to submit a persisted stack to a crash backend. */
class GuestCrashReplayException internal constructor() : RuntimeException(null, null, false, true)
