package org.apptwin.runtime

import android.app.Application
import android.app.ActivityManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VAccountManager
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.MainActivity
import org.apptwin.MainViewModel
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual guest login return must update the still-open production account page. */
@RunWith(AndroidJUnit4::class)
class SpaceAccountRefreshE2eTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var originalHost: MainActivity

    @Test fun delayedLoginReturnRefreshesAccountsAndCancelLeavesSpaceEmpty() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("spaceAccountRefreshE2e") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        originalHost = compose.activity
        val operations = AndroidMainOperations(context.applicationContext as Application)
        AndroidPackageRevisionImporter(context).sync(PACKAGE)
        val owned = mutableListOf<String>()
        var primary: Throwable? = null
        try {
            operations.reconcileGroups()
            val a = operations.createGroup("Account refresh A").also { owned += it.id }
            val b = operations.createGroup("Account refresh B").also { owned += it.id }
            val preparedA = operations.addAppToGroup(a.id, PACKAGE)
            val preparedB = operations.addAppToGroup(b.id, PACKAGE)
            val userA = requireNotNull(preparedA.environmentBinding).internalId
            val userB = requireNotNull(preparedB.environmentBinding).internalId
            assertTrue(requireNotNull(operations.loadSpaceDetails(a.id)).accountTypes.any { it.type == TYPE })
            operations.loadSpaceDetails(b.id)
            // Preserve the launch Intent tracked by ActivityScenario. A new host Intent
            // would make its lifecycle listener ignore later resume/destroy events.
            returnToOriginalHostTask()
            if (compose.onAllNodesWithTag("complete-onboarding").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("complete-onboarding").performClick()
            }
            compose.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[MainViewModel::class.java].refresh()
            }
            compose.waitUntil(15_000) {
                var included = false
                compose.activityRule.scenario.onActivity { activity ->
                    included = ViewModelProvider(activity)[MainViewModel::class.java].uiState.groups.any { it.groupId == a.id }
                }
                included
            }
            compose.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[MainViewModel::class.java].openSpaceDetails(a.id)
            }
            compose.waitUntil(15_000) { mainActivityLaunchHosts.current() != null && runCatching {
                compose.onAllNodesWithTag("space-details").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false) }
            // LazyColumn may not compose the account card until it has been scrolled into view.
            compose.onNodeWithTag("space-details").performScrollToNode(hasText("管理帳號"))
            compose.onNodeWithText("管理帳號").performClick()
            android.util.Log.i("FixtureAccount", "refresh setup-ready")
            startLogin()
            awaitGuestButton("Cancel fixture login")
            assertTrue("Opening guest login must not create an account", VAccountManager.get().getAccounts(userA, TYPE).isEmpty())
            clickGuestButton("Cancel fixture login")
            returnToOriginalHostTask()
            awaitAccountPage()
            assertTrue(VAccountManager.get().getAccounts(userA, TYPE).isEmpty())
            assertTrue(VAccountManager.get().getAccounts(userB, TYPE).isEmpty())
            assertTrue(compose.onAllNodesWithText(NAME).fetchSemanticsNodes().isEmpty())
            startLogin()
            awaitGuestButton("Complete fixture login")
            clickGuestButton("Complete fixture login")
            returnToOriginalHostTask()
            awaitAccountPage()
            assertEquals(NAME, VAccountManager.get().getAccounts(userA, TYPE).single().name)
            assertTrue("Guest completion stays inside selected space", VAccountManager.get().getAccounts(userB, TYPE).isEmpty())
            android.util.Log.i("FixtureAccount", "interactive backend-created; awaiting original account page row")
            // Returning through the existing task models Recents. No refresh call,
            // account-page navigation, or Activity restart follows guest completion.
            compose.waitUntil(20_000) { runCatching {
                compose.onAllNodesWithText(NAME).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false) }
            compose.onNodeWithText(NAME).assertIsDisplayed()
            assertEquals(NAME, VAccountManager.get().getAccounts(userA, TYPE).single().name)
            assertTrue("Guest completion stays inside selected space", VAccountManager.get().getAccounts(userB, TYPE).isEmpty())
        } catch (failure: Throwable) {
            android.util.Log.e("FixtureAccount", "refresh failure", failure)
            primary = failure
            throw failure
        } finally {
            val errors = mutableListOf<Throwable>()
            // Finish any owned fixture Activity before removing its space.
            runCatching {
                val cancel = findGuestButton("Cancel fixture login")
                if (cancel != null) cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                else if (mainActivityLaunchHosts.current() == null && instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("AppTwin synthetic account login")?.isNotEmpty() == true) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                }
            }
            owned.asReversed().forEach { id ->
                try { assertNotNull(operations.deleteGroup(id)) } catch (error: Throwable) { errors += error }
            }
            // Cleanup only: finish the exact host task created by this test. Virtual
            // guest tasks may otherwise leave ActivityScenario's finish waiting in PAUSED.
            // This executes after all acceptance assertions (or after the recorded failure).
            runCatching {
                if (!originalHost.isDestroyed) {
                    returnToOriginalHostTask()
                    assertSame(originalHost, mainActivityLaunchHosts.current())
                    originalHost.getSystemService(ActivityManager::class.java).appTasks
                        .single { it.taskInfo.taskId == originalHost.taskId }.finishAndRemoveTask()
                    instrumentation.waitForIdleSync()
                }
                val destroyDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var destroyed = false
                while (!destroyed && android.os.SystemClock.elapsedRealtime() < destroyDeadline) {
                    instrumentation.runOnMainSync { destroyed = originalHost.isDestroyed }
                    if (!destroyed) Thread.sleep(100)
                }
                assertTrue("Owned host must actually be destroyed after task cleanup", destroyed)
                android.util.Log.i("FixtureAccount", "cleanup actual-destroyed")
            }.exceptionOrNull()?.let(errors::add)
            if (primary != null) errors.forEach { primary!!.addSuppressed(it) }
            else if (errors.isNotEmpty()) { errors.drop(1).forEach(errors.first()::addSuppressed); throw errors.first() }
        }
    }

    private fun startLogin() {
        awaitAccountPage()
        android.util.Log.i("FixtureAccount", "refresh account-page-ready")
        compose.onNodeWithTag("space-account-add").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        android.util.Log.i("FixtureAccount", "refresh add-clicked")
        compose.waitUntil(15_000) { runCatching {
            compose.onAllNodesWithTag("space-authenticator-$TYPE").fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false) }
        android.util.Log.i("FixtureAccount", "refresh menu-ready")
        // Calling the visible UI's semantics handler avoids a touch-up injection racing
        // with the guest Activity that this button opens.
        compose.onNodeWithTag("space-authenticator-$TYPE").performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    private fun awaitAccountPage() {
        compose.waitUntil(20_000) { mainActivityLaunchHosts.current() != null && runCatching {
            compose.onAllNodes(hasTestTag("space-account-add") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false) }
    }
    private fun returnToOriginalHostTask() {
        val manager = originalHost.getSystemService(ActivityManager::class.java)
        val task = manager.appTasks.single { it.taskInfo.taskId == originalHost.taskId }
        task.moveToFront()
        compose.waitUntil(15_000) { mainActivityLaunchHosts.current() === originalHost }
        assertSame("Recents return must retain the original MainActivity instance", originalHost, mainActivityLaunchHosts.current())
    }
    private fun findGuestButton(text: String): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        return (root.findAccessibilityNodeInfosByText(text) + root.findAccessibilityNodeInfosByText(text.uppercase(java.util.Locale.ROOT)))
            .firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true && it.isClickable }
    }
    private fun awaitGuestButton(text: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 35_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (findGuestButton(text) != null) {
                android.util.Log.i("FixtureAccount", "refresh guest-button-ready")
                return
            }
            Thread.sleep(100)
        }
        error("Guest fixture login button did not appear: $text")
    }
    private fun clickGuestButton(text: String) {
        assertTrue("Actual guest button must accept click", requireNotNull(findGuestButton(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    companion object {
        private const val PACKAGE = "org.apptwin.fixture"
        private const val TYPE = "org.apptwin.fixture.interactive"
        private const val NAME = "fixture-interactive-account"
    }
}
