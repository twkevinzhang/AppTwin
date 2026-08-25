package org.apptwin.runtime

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apptwin.GroupAppLaunchContract
import org.apptwin.groups.FileGroupStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt-in helper that opens the existing cloned LINE without changing its data. */
@RunWith(AndroidJUnit4::class)
class OpenClonedLineE2eTest {
    @Test
    fun openExistingClonedLine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "requires -e $OPEN_ARGUMENT true",
            InstrumentationRegistry.getArguments().getString(OPEN_ARGUMENT).toBoolean(),
        )
        val groupId = InstrumentationRegistry.getArguments().getString(GROUP_ID_ARGUMENT)
        val virtualUserId = InstrumentationRegistry.getArguments()
            .getString(VIRTUAL_USER_ID_ARGUMENT)
            ?.toIntOrNull()
        assumeTrue("requires -e $GROUP_ID_ARGUMENT <uuid>", !groupId.isNullOrEmpty())
        assumeTrue("requires -e $VIRTUAL_USER_ID_ARGUMENT <id>", virtualUserId != null)
        val context = instrumentation.targetContext
        val group = FileGroupStore(context).loadSnapshot().groups.firstOrNull { candidate ->
            candidate.id == groupId &&
                candidate.environmentBinding?.internalId == virtualUserId &&
                candidate.apps.any { it.packageName == LINE_PACKAGE }
        }
        assumeTrue(
            "requires the exact Group/virtual-user scope containing cloned LINE",
            group != null,
        )

        context.startActivity(
            GroupAppLaunchContract.intent(context, requireNotNull(group).id, LINE_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.uiAutomation.waitForIdle(IDLE_QUIET_MS, WINDOW_TIMEOUT_MS)
        SystemClock.sleep(LAUNCH_SETTLE_MS)
        assertTrue(
            "cloned LINE did not become the active AppTwin window",
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                context.packageName,
        )

    }

    private companion object {
        const val OPEN_ARGUMENT = "openClonedLine"
        const val GROUP_ID_ARGUMENT = "clonedLineGroupId"
        const val VIRTUAL_USER_ID_ARGUMENT = "clonedLineVirtualUserId"
        const val LINE_PACKAGE = "jp.naver.line.android"
        const val IDLE_QUIET_MS = 500L
        const val WINDOW_TIMEOUT_MS = 5_000L
        const val LAUNCH_SETTLE_MS = 4_000L
    }
}
