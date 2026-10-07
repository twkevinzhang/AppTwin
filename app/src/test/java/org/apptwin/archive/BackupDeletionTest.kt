package org.apptwin.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupDeletionTest {
    @Test fun `retry removes stale record after file deletion and metadata commit failure`() {
        var exists = true
        var attempts = 0
        fun attempt() = deleteBackupIdempotently(
            deleteFile = { if (exists) { exists = false; true } else false },
            presence = { if (exists) BackupPresence.PRESENT else BackupPresence.CONFIRMED_MISSING },
            removeRecord = { attempts++; if (attempts == 1) error("commit failure") },
        )
        assertThrows(IllegalStateException::class.java) { attempt() }
        assertFalse(exists)
        attempt()
        assertEquals(2, attempts)
    }

    @Test fun `inaccessible file preserves record even when delete throws`() {
        var removed = false
        assertThrows(IllegalStateException::class.java) {
            deleteBackupIdempotently({ throw SecurityException("grant revoked") },
                { BackupPresence.UNAVAILABLE }, { removed = true })
        }
        assertFalse(removed)
    }

    @Test fun `existing file preserves record when provider refuses deletion`() {
        var removed = false
        assertThrows(IllegalStateException::class.java) {
            deleteBackupIdempotently({ false }, { BackupPresence.PRESENT }, { removed = true })
        }
        assertFalse(removed)
    }
}
