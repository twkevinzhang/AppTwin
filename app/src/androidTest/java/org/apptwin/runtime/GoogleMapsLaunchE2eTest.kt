package org.apptwin.runtime

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in ASUS acceptance for the Play-distributed Google Maps clone.
 *
 * The harness owns the host force-stop between phases:
 *
 * 1. Run this class with `-e mapsE2ePhase 1`.
 * 2. Run `am force-stop org.apptwin`.
 * 3. Run this class with `-e mapsE2ePhase 2`.
 *
 * Both phases reuse an existing Group containing [MAPS_PACKAGE]. They do not create or delete a
 * Group, clear Maps data, or perform sign-in. Pass `-e mapsGroupId <id>` when more than one Group
 * contains Maps.
 */
@RunWith(AndroidJUnit4::class)
class GoogleMapsLaunchE2eTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    private val operations
        get() = AndroidMainOperations(context.applicationContext as Application)

    @Test
    fun phaseOneColdLaunchesThenBackgroundsAndResumesMaps() = runBlocking {
        assumePhase("1")
        val group = existingMapsGroup()
        val userId = group.environmentId()

        clearLogcat()
        VirtualCore.get().killApp(MAPS_PACKAGE, userId)
        assertStarted(operations.launchGroupApp(group.mapsItem()))
        assertMapsRemainsForeground(userId, COLD_LAUNCH_OBSERVATION_MILLIS)

        executeShellCommand("input keyevent KEYCODE_HOME")
        awaitMapsBackgrounded()

        assertStarted(operations.launchGroupApp(group.mapsItem()))
        assertMapsRemainsForeground(userId, RESUME_OBSERVATION_MILLIS)
        assertNoMapsFatal(readLogcat())
    }

    @Test
    fun phaseTwoColdLaunchesMapsAfterHostForceStop() = runBlocking {
        assumePhase("2")
        val group = existingMapsGroup()
        val userId = group.environmentId()

        clearLogcat()
        assertFalse(
            "Maps guest process must be cold after the harness force-stops AppTwin",
            VirtualCore.get().isAppRunning(MAPS_PACKAGE, userId),
        )
        assertStarted(operations.launchGroupApp(group.mapsItem()))
        assertMapsRemainsForeground(userId, COLD_LAUNCH_OBSERVATION_MILLIS)
        executeShellCommand("screencap -p $SCREENSHOT_PATH")
        assertNoMapsFatal(readLogcat())
    }

    private fun existingMapsGroup(): Group {
        val snapshot = FileGroupStore(context).loadSnapshot()
        assertTrue("Group metadata must be readable: ${snapshot.issues}", snapshot.issues.isEmpty())
        val candidates = snapshot.groups.filter { it.contains(MAPS_PACKAGE) }
        val requestedId = InstrumentationRegistry.getArguments().getString(GROUP_ID_ARGUMENT)
        return if (requestedId.isNullOrBlank()) {
            require(candidates.size == 1) {
                "Expected exactly one existing Group containing $MAPS_PACKAGE, found " +
                    "${candidates.map(Group::id)}; pass -e $GROUP_ID_ARGUMENT <id>"
            }
            candidates.single()
        } else {
            requireNotNull(candidates.singleOrNull { it.id == requestedId }) {
                "Group $requestedId does not exist or does not contain $MAPS_PACKAGE"
            }
        }
    }

    private fun assertMapsRemainsForeground(userId: Int, observationMillis: Long) {
        awaitMapsForeground()
        val deadline = System.nanoTime() + observationMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            assertTrue(
                "Maps guest process stopped during the foreground observation window",
                VirtualCore.get().isAppRunning(MAPS_PACKAGE, userId),
            )
            assertTrue(
                "Maps left the foreground; ${foregroundDiagnostic()}",
                isMapsForeground(),
            )
            Thread.sleep(FOREGROUND_POLL_INTERVAL_MILLIS)
        }
    }

    private fun awaitMapsForeground() {
        repeat(FOREGROUND_TRANSITION_ATTEMPTS) {
            if (isMapsForeground()) return
            Thread.sleep(FOREGROUND_TRANSITION_INTERVAL_MILLIS)
        }
        error("Maps did not enter the foreground; ${foregroundDiagnostic()}")
    }

    private fun awaitMapsBackgrounded() {
        repeat(FOREGROUND_TRANSITION_ATTEMPTS) {
            if (!isMapsForeground()) return
            Thread.sleep(FOREGROUND_TRANSITION_INTERVAL_MILLIS)
        }
        error("Maps remained in the foreground after HOME")
    }

    private fun isMapsForeground(): Boolean {
        val windowPackage = activeWindowPackage()
        return windowPackage == MAPS_PACKAGE ||
            (windowPackage == context.packageName && activeVirtualPackage() == MAPS_PACKAGE)
    }

    private fun activeWindowPackage(): String? =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    /**
     * Accessibility can expose either the guest package or AppTwin's physical ShadowActivity.
     * When it exposes the host, resolve the physical foreground task back to its virtual top.
     */
    @Suppress("DEPRECATION")
    private fun activeVirtualPackage(): String? {
        val physicalTaskId = context.getSystemService(ActivityManager::class.java)
            .getRunningTasks(1)
            .firstOrNull()
            ?.id
            ?: return null
        return VActivityManager.get().getTaskInfo(physicalTaskId)?.topActivity?.packageName
    }

    private fun foregroundDiagnostic(): String =
        "activeWindowPackage=${activeWindowPackage()} activeVirtualPackage=${activeVirtualPackage()}"

    private fun assertNoMapsFatal(logcat: String) {
        assertFalse(
            "Original AccountManager package/UID crash recurred:\n${mapsCrashEvidence(logcat)}",
            logcat.contains("Package $MAPS_PACKAGE does not belong to"),
        )
        val lines = logcat.lineSequence().toList()
        val mapsFatal = lines.indices.firstOrNull { index ->
            lines[index].contains("FATAL EXCEPTION:") &&
                lines.drop(index).take(FATAL_PROCESS_LOOKAHEAD_LINES).any { line ->
                    line.contains("Process: $MAPS_PACKAGE")
                }
        }
        assertTrue(
            "Google Maps emitted a fatal exception:\n${mapsCrashEvidence(logcat, mapsFatal)}",
            mapsFatal == null,
        )
    }

    private fun mapsCrashEvidence(logcat: String, startLine: Int? = null): String {
        val lines = logcat.lineSequence().toList()
        val firstRelevant = startLine ?: lines.indexOfFirst { line ->
            line.contains("Package $MAPS_PACKAGE does not belong to") ||
                line.contains("Process: $MAPS_PACKAGE")
        }
        if (firstRelevant < 0) return "no Maps-specific fatal evidence found"
        val from = (firstRelevant - CRASH_CONTEXT_LINES).coerceAtLeast(0)
        val to = (firstRelevant + CRASH_CONTEXT_LINES + 1).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    private fun clearLogcat() {
        executeShellCommand("logcat -c")
    }

    private fun readLogcat(): String = executeShellCommand("logcat -d -v threadtime")

    private fun executeShellCommand(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return descriptor.use { it.readFully() }
    }

    private fun ParcelFileDescriptor.readFully(): String =
        FileInputStream(fileDescriptor).bufferedReader().use { it.readText() }

    private fun Group.mapsItem(): GroupAppItem {
        val app = requireNotNull(apps.singleOrNull { it.packageName == MAPS_PACKAGE })
        val packageInfo = context.packageManager.getPackageInfo(MAPS_PACKAGE, 0)
        val label = packageInfo.applicationInfo?.let(context.packageManager::getApplicationLabel)
            ?.toString()
            ?: "Google Maps"
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = app,
            appLabel = label,
            versionName = packageInfo.versionName.orEmpty(),
            sourceInstalled = true,
            launchStatus = "",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("Google Maps clone must start: $result", result is RuntimeLaunchResult.Started)
    }

    private fun assumePhase(expected: String) {
        val actual = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)
        assumeTrue("pass -e $PHASE_ARGUMENT $expected", actual == expected)
    }

    private companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val PHASE_ARGUMENT = "mapsE2ePhase"
        const val GROUP_ID_ARGUMENT = "mapsGroupId"
        const val COLD_LAUNCH_OBSERVATION_MILLIS = 30_000L
        const val RESUME_OBSERVATION_MILLIS = 10_000L
        const val FOREGROUND_POLL_INTERVAL_MILLIS = 1_000L
        const val FOREGROUND_TRANSITION_ATTEMPTS = 30
        const val FOREGROUND_TRANSITION_INTERVAL_MILLIS = 500L
        const val FATAL_PROCESS_LOOKAHEAD_LINES = 8
        const val CRASH_CONTEXT_LINES = 12
        const val NANOS_PER_MILLI = 1_000_000L
        const val SCREENSHOT_PATH = "/sdcard/Download/apptwin-google-maps-fixed.png"
    }
}
