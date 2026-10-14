package org.apptwin.runtime

import android.accounts.Account
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Framework picker must honor the actual guest user rather than its forged userId extra. */
@RunWith(AndroidJUnit4::class)
class OwnedFrameworkAccountPickerE2eTest {
    @Test
    fun realPickerScopesSyntheticAccountsAcrossForgeryRotationCancelAndEmpty() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val originalAccessibilityFlags = automation.serviceInfo.flags
        val operations = (context.applicationContext as AppTwinApplication).mainContainer.operationGraph.mainOperations()
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        val owned = mutableListOf<String>()
        val accounts = mutableListOf<Pair<Int, Account>>()
        var primary: Throwable? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val imported = AndroidPackageRevisionImporter(context).sync(PACKAGE)
            assertTrue(imported is RevisionImportResult.Activated || imported is RevisionImportResult.AlreadyCurrent)
            fun create(label: String): Group = runBlocking {
                val group = operations.createGroup(label).also { owned += it.id }
                operations.addAppToGroup(group.id, PACKAGE)
            }
            val a = create("Owned account picker A")
            val b = create("Owned account picker B")
            val empty = create("Owned account picker empty")
            val accountA = Account("owned-a-${UUID.randomUUID()}", PACKAGE)
            val accountB = Account("owned-b-${UUID.randomUUID()}", PACKAGE)
            listOf(a to accountA, b to accountB).forEach { (group, account) ->
                assertTrue(VAccountManager.get().remote.addAccountExplicitly(group.user(), account, null, Bundle()))
                accounts += group.user() to account
            }
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { host ->
                fun probe(group: Group, mode: String) {
                    android.util.Log.i("OwnedAccountPicker", "phase=$mode stage=begin")
                    VirtualCore.get().killApp(PACKAGE, group.user())
                    val files = File(VEnvironment.getDataUserPackageDirectory(group.user(), PACKAGE), "files")
                    check(files.isDirectory || files.mkdirs())
                    val request = UUID.randomUUID().toString()
                    File(files, "enable-owned-account-picker-probe").writeText(JSONObject().put("requestId", request)
                        .put("forgedUser", b.user()).put("expectedName", accountA.name)
                        .put("accountType", PACKAGE).toString())
                    val result = File(files, "owned-account-picker-outcome.json")
                    check(!result.exists() || result.delete())
                    android.util.Log.i("OwnedAccountPicker", "phase=$mode stage=before-host")
                    try { host.awaitRealForegroundHost() } catch (error: Throwable) {
                        throw IllegalStateException("Owned picker phase=$mode could not acquire the real foreground host", error)
                    }
                    android.util.Log.i("OwnedAccountPicker", "phase=$mode stage=after-host")
                    val launch = runBlocking { operations.launchGroupApp(group.item()) }
                    assertTrue(launch is RuntimeLaunchResult.Started)
                    assertEquals(files.parentFile!!.absolutePath, (launch as RuntimeLaunchResult.Started).dataDirectory)
                    if (mode != "empty") {
                        awaitSyntheticAccount(accountA.name)
                        assertSyntheticAccountAbsent(accountB.name)
                        if (mode == "select") {
                            instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
                            try {
                                awaitSyntheticAccount(accountA.name)
                                assertSyntheticAccountAbsent(accountB.name)
                                clickSyntheticAccount(accountA.name)
                                clickFrameworkButton("android:id/button2")
                            } finally { instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
                        } else clickFrameworkButton("android:id/button1")
                    }
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    var observed: JSONObject? = null
                    while (SystemClock.elapsedRealtime() < deadline) {
                        assertNoPhysicalPicker()
                        observed = runCatching { JSONObject(result.readText()) }.getOrNull()
                        if (observed?.optString("requestId") == request) break
                        Thread.sleep(100)
                    }
                    android.util.Log.i("OwnedAccountPicker", "phase=$mode stage=after-callback observed=${observed != null}")
                    val outcome = requireNotNull(observed) { "Framework account picker callback did not terminate" }
                    assertEquals(request, outcome.getString("requestId"))
                    assertEquals(1, outcome.getInt("callbacks"))
                    when (mode) {
                        "select" -> { assertTrue(outcome.getBoolean("resultOk")); assertTrue(outcome.getBoolean("nameMatches")); assertTrue(outcome.getBoolean("typeMatches")) }
                        "cancel" -> { assertTrue(outcome.getBoolean("cancelled")); assertFalse(outcome.getBoolean("hasAccount")) }
                        else -> { assertTrue("Empty owned Space returns explicit cancellation", outcome.getBoolean("cancelled")); assertFalse("Empty owned Space must never return a host account", outcome.getBoolean("hasAccount")) }
                    }
                    Thread.sleep(500)
                    assertNoPhysicalPicker()
                    assertEquals(1, JSONObject(result.readText()).getInt("callbacks"))
                }
                probe(a, "select")
                probe(a, "cancel")
                probe(empty, "empty")
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            try { automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags } }
            catch (error: Throwable) { errors += error }
            accounts.forEach { (user, account) -> try {
                assertTrue(VAccountManager.get().removeAccountExplicitly(user, account))
                assertFalse(VAccountManager.get().getAccounts(user, PACKAGE).contains(account))
            } catch (error: Throwable) { errors += error } }
            owned.asReversed().forEach { id -> try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error } }
            try { assertEquals(baseline, tuples(store.loadSnapshot().groups)) } catch (error: Throwable) { errors += error }
            if (primary != null) errors.forEach(primary!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }
    private fun awaitSyntheticAccount(name: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            assertNoPhysicalPicker()
            if (syntheticNodes(name).any { it.isVisibleToUser }) return
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Owned synthetic account was not displayed by the virtual picker")
    }
    // The trusted picker runs in the engine process, outside Espresso's target View roots.
    // Accessibility inspection crosses that process boundary without dumping unrelated text.
    private fun syntheticNodes(name: String): List<android.view.accessibility.AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        return root.findAccessibilityNodeInfosByText(name).filter { it.text?.toString() == name }
    }
    private fun assertSyntheticAccountAbsent(name: String) {
        assertFalse("Other owned Space account must not appear", syntheticNodes(name).any { it.isVisibleToUser })
    }
    private fun clickSyntheticAccount(name: String) {
        clickNode(requireNotNull(syntheticNodes(name).firstOrNull { it.isVisibleToUser }))
    }
    private fun clickFrameworkButton(id: String) {
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser && it.isEnabled }
        assertNotNull("Expected virtual picker action button", node)
        clickNode(requireNotNull(node))
    }
    private fun clickNode(node: android.view.accessibility.AccessibilityNodeInfo) {
        var target: android.view.accessibility.AccessibilityNodeInfo? = node
        repeat(6) {
            val current = target ?: return@repeat
            if (current.isClickable && current.isEnabled) {
                assertTrue("Virtual picker accessibility click", current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            target = current.parent
        }
        fail("Virtual picker item had no clickable accessible ancestor")
    }
    private fun assertNoPhysicalPicker() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 3_000
        do {
            val snapshot = automation.executeShellCommand("dumpsys activity activities").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            val resumed = snapshot.lineSequence().filter {
                it.contains("mResumedActivity") || it.contains("topResumedActivity")
            }.toList()
            assertFalse("Framework account picker escaped to physical host account UI", resumed.any {
                it.contains("android/.accounts.Choose") || it.contains("com.google.android.gms/")
            })
            if (resumed.any { it.contains("ActivityRecord{") }) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Physical foreground metadata unavailable after bounded transition wait")
    }
    private fun Group.user() = requireNotNull(environmentBinding).internalId
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE }, "Owned picker fixture", "fixture", true, "")
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",")}" }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
