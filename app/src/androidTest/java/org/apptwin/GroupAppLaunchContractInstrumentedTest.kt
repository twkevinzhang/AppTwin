package org.apptwin

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAppLaunchContractInstrumentedTest {
    @Test
    fun productShortcutIntentRoutesDirectlyToExactGroupAndPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val intent = GroupAppLaunchContract.intent(context, GROUP_ID, LINE_PACKAGE)

        assertEquals(
            ComponentName(context, MainActivity::class.java),
            intent.component,
        )
        assertEquals(GroupAppLaunchContract.ACTION_LAUNCH_GROUP_APP, intent.action)
        assertEquals(
            GROUP_ID,
            intent.getStringExtra(GroupAppLaunchContract.EXTRA_GROUP_ID),
        )
        assertEquals(
            LINE_PACKAGE,
            intent.getStringExtra(GroupAppLaunchContract.EXTRA_PACKAGE_NAME),
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(
            "$GROUP_ID:$LINE_PACKAGE",
            GroupAppLaunchContract.launchKey(GROUP_ID, LINE_PACKAGE),
        )
    }

    private companion object {
        const val GROUP_ID = "11111111-1111-1111-1111-111111111111"
        const val LINE_PACKAGE = "jp.naver.line.android"
    }
}
