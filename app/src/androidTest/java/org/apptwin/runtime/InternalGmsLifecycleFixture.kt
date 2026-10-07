package org.apptwin.runtime

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import org.apptwin.gms.AndroidGmsOperations
import org.apptwin.gms.production
import org.apptwin.groups.FileGroupStore

/** Low-level lifecycle compatibility fixture; these actions are not product operations. */
internal fun internalGmsLifecycleFixture(): AndroidGmsOperations {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return AndroidGmsOperations.production(context.applicationContext as Application, FileGroupStore(context))
}
