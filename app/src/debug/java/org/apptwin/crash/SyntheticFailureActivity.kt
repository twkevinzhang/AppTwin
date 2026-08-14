package org.apptwin.crash

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock

/** ADB-only failures used to verify the debug Crashlytics pipeline on a physical device. */
class SyntheticFailureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_JAVA -> throw SyntheticHostCrashException()
            MODE_ANR -> {
                SystemClock.sleep(ANR_DURATION_MILLIS)
                throw SyntheticAnrTerminalException()
            }
            else -> finish()
        }
    }

    private class SyntheticHostCrashException : RuntimeException()
    private class SyntheticAnrTerminalException : RuntimeException()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_JAVA = "java"
        const val MODE_ANR = "anr"
        private const val ANR_DURATION_MILLIS = 20_000L
    }
}
