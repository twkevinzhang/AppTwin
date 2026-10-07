package org.apptwin.archive

internal enum class BackupPresence { PRESENT, CONFIRMED_MISSING, UNAVAILABLE }

/** Removal may be retried after a metadata-write failure, but access errors never prove deletion. */
internal fun deleteBackupIdempotently(
    deleteFile: () -> Boolean,
    presence: () -> BackupPresence,
    removeRecord: () -> Unit,
) {
    val deleted = runCatching(deleteFile)
    if (deleted.getOrDefault(false) || presence() == BackupPresence.CONFIRMED_MISSING) {
        removeRecord()
    } else {
        throw IllegalStateException("永久刪除備份失敗或無法確認檔案狀態", deleted.exceptionOrNull())
    }
}
