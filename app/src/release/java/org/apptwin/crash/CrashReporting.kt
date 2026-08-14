package org.apptwin.crash

import android.app.Application
import com.lody.virtual.client.core.VirtualCore

/** Release builds intentionally do not link or initialize a crash-reporting SDK. */
object CrashReporting {
    @Suppress("UNUSED_PARAMETER")
    fun initialize(application: Application, virtualCore: VirtualCore) = Unit
}
