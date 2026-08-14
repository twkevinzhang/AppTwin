package org.apptwin.fixture

import android.app.Activity
import android.os.Bundle

/** Test-only guest entry point whose uncaught exception exercises AppTwin's virtual bridge. */
class SyntheticCrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        throw SyntheticGuestCrashException()
    }

    private class SyntheticGuestCrashException : RuntimeException()
}
