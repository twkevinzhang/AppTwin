package org.apptwin.fixture

/** Loaded only by the explicit shared-write opt-in fixture probe. */
object SharedWriteNativeProbe {
    init { System.loadLibrary("apptwin_shared_write_probe") }
    @JvmStatic external fun writeRelative(ownedPath: String, changedBytes: ByteArray): Boolean
}
