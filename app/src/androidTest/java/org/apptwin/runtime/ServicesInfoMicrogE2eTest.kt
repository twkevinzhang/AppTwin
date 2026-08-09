package org.apptwin.runtime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.GroupAppItem
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in ASUS A/B acceptance using the Play-distributed Services Info application.
 *
 * The test deliberately leaves the Groups and clones installed for manual inspection. The caller
 * owns the clean-state precondition and must pass `-e servicesInfoE2e 1` explicitly.
 */
@RunWith(AndroidJUnit4::class)
class ServicesInfoMicrogE2eTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context: Context
        get() = instrumentation.targetContext

    @Test
    fun servicesInfoSeesMicrogOnlyInEnabledGroup() = runBlocking {
        assumeTrue(
            "opt-in Services Info E2E; pass -e $OPT_IN_ARGUMENT 1",
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "1",
        )

        val initial = FileGroupStore(context).loadSnapshot()
        assertTrue("Services Info E2E requires clean Group metadata", initial.groups.isEmpty())
        assertTrue("Group metadata must be readable", initial.issues.isEmpty())

        val imported = AndroidPackageRevisionImporter(context).sync(SERVICES_INFO_PACKAGE)
        assertTrue(
            "Play-installed Services Info revision must be importable: $imported",
            imported is RevisionImportResult.Activated ||
                imported is RevisionImportResult.AlreadyCurrent,
        )

        val operations = AndroidMainOperations(context.applicationContext as Application)
        val groupA = operations.addAppToGroup(
            operations.createGroup(GROUP_A_NAME).id,
            SERVICES_INFO_PACKAGE,
        )
        val groupB = operations.addAppToGroup(
            operations.createGroup(GROUP_B_NAME).id,
            SERVICES_INFO_PACKAGE,
        )
        val userA = groupA.environmentId()
        val userB = groupB.environmentId()
        assertTrue("Groups must use distinct positive virtual users", userA > 0 && userB > 0 && userA != userB)

        operations.grantGmsConsent(groupA.id)
        val enabled = operations.enableGms(groupA.id)
        assertTrue(
            "Group A microG enable must reach a business terminal: $enabled",
            enabled is GmsLifecycleResult.Completed || enabled is GmsLifecycleResult.AlreadySatisfied,
        )
        assertTrue("Group A must contain GmsCore", VirtualCore.get().isAppInstalledAsUser(userA, GMS_PACKAGE))
        assertTrue("Group A must contain Companion", VirtualCore.get().isAppInstalledAsUser(userA, COMPANION_PACKAGE))
        assertFalse("Group B must not contain GmsCore", VirtualCore.get().isAppInstalledAsUser(userB, GMS_PACKAGE))
        assertFalse("Group B must not contain Companion", VirtualCore.get().isAppInstalledAsUser(userB, COMPANION_PACKAGE))

        assertStarted(operations.launchGroupApp(groupA.servicesInfoItem()))
        val evidenceA = awaitEvidence()
        captureScreenshot(A_SCREENSHOT)
        Log.i(TAG, "GROUP_A user=$userA status=${evidenceA.status} info=${evidenceA.info}")
        assertTrue(
            "Group A must report installed Play services: ${evidenceA.status}",
            evidenceA.status.contains("installed", ignoreCase = true) &&
                !evidenceA.status.contains("not installed", ignoreCase = true),
        )
        assertTrue(
            "Group A must expose a Play services version: ${evidenceA.info}",
            evidenceA.info.contains("version", ignoreCase = true),
        )

        assertStarted(operations.launchGroupApp(groupB.servicesInfoItem()))
        val evidenceB = awaitEvidence(previous = evidenceA)
        captureScreenshot(B_SCREENSHOT)
        Log.i(TAG, "GROUP_B user=$userB status=${evidenceB.status} info=${evidenceB.info}")
        assertTrue(
            "Group B must report Play services missing or unavailable: ${evidenceB.status}",
            evidenceB.status.contains("not installed", ignoreCase = true) ||
                evidenceB.status.contains("missing", ignoreCase = true) ||
                evidenceB.status.contains("unavailable", ignoreCase = true),
        )
    }

    private fun awaitEvidence(previous: Evidence? = null): Evidence {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        repeat(POLL_ATTEMPTS) {
            val status = textForViewId(STATUS_VIEW_ID)
            val info = textForViewId(INFO_VIEW_ID)
            if (!status.isNullOrBlank()) {
                val evidence = Evidence(status, info.orEmpty())
                if (previous == null || evidence != previous) return evidence
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        error(
            "Services Info evidence did not appear/change; " +
                "visible=${visibleText().joinToString(" | ")}",
        )
    }

    private fun textForViewId(viewId: String): String? =
        instrumentation.uiAutomation.windows.asSequence()
            .mapNotNull { it.root }
            .flatMap { root -> root.findAccessibilityNodeInfosByViewId(viewId).asSequence() }
            .mapNotNull(AccessibilityNodeInfo::getText)
            .map(CharSequence::toString)
            .firstOrNull { it.isNotBlank() }

    private fun visibleText(): List<String> {
        val output = mutableListOf<String>()
        instrumentation.uiAutomation.windows.forEach { window ->
            window.root?.let { collectText(it, output) }
        }
        return output.distinct()
    }

    private fun collectText(node: AccessibilityNodeInfo, output: MutableList<String>) {
        node.text?.toString()?.takeIf(String::isNotBlank)?.let(output::add)
        repeat(node.childCount) { index -> node.getChild(index)?.let { collectText(it, output) } }
    }

    private fun captureScreenshot(path: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p $path")
        FileInputStream(descriptor.fileDescriptor).use { input ->
            while (input.read() != -1) Unit
        }
        descriptor.closeQuietly()
        Log.i(TAG, "SCREENSHOT=$path")
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    private fun Group.servicesInfoItem(): GroupAppItem {
        val app = requireNotNull(apps.singleOrNull { it.packageName == SERVICES_INFO_PACKAGE })
        return GroupAppItem(
            groupId = id,
            groupName = name,
            groupHealth = health,
            app = app,
            appLabel = "Services Info (Update)",
            versionName = SERVICES_INFO_VERSION,
            sourceInstalled = true,
            launchStatus = "",
        )
    }

    private fun Group.environmentId(): Int = requireNotNull(environmentBinding).internalId

    private fun assertStarted(result: RuntimeLaunchResult) {
        assertTrue("Services Info clone must start: $result", result is RuntimeLaunchResult.Started)
    }

    private data class Evidence(val status: String, val info: String)

    private companion object {
        private const val TAG = "ServicesInfoMicrogE2e"
        private const val OPT_IN_ARGUMENT = "servicesInfoE2e"
        private const val SERVICES_INFO_PACKAGE = "com.weberdo.apps.serviceinfo"
        private const val SERVICES_INFO_VERSION = "0.17"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val COMPANION_PACKAGE = "com.android.vending"
        private const val GROUP_A_NAME = "Services Info · microG A"
        private const val GROUP_B_NAME = "Services Info · no GMS B"
        private const val STATUS_VIEW_ID = "$SERVICES_INFO_PACKAGE:id/status"
        private const val INFO_VIEW_ID = "$SERVICES_INFO_PACKAGE:id/info"
        private const val A_SCREENSHOT = "/sdcard/Download/apptwin-services-info-group-a.png"
        private const val B_SCREENSHOT = "/sdcard/Download/apptwin-services-info-group-b.png"
        private const val POLL_ATTEMPTS = 50
        private const val POLL_INTERVAL_MILLIS = 500L
    }
}
