package org.apptwin.runtime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.MainDestination
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real host lifecycle/Settings return; changes only newly owned clone permission records. */
@RunWith(AndroidJUnit4::class)
class OwnedLifecyclePermissionRefreshE2eTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun stoppedHostAndActualSettingsReturnRereadOwnedPermissionSource() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as AppTwinApplication
        val operations = app.mainContainer.operationGraph.mainOperations()
        assertEquals("Host grant is a harness prerequisite", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        context.packageManager.getPackageInfo(PACKAGE, 0)
        val store = FileGroupStore(context)
        val baseline = publicTuples(store.loadSnapshot().groups)
        val owned = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            val a = operations.createGroup("Owned lifecycle A").also { owned += it.id }
            val b = operations.createGroup("Owned lifecycle B").also { owned += it.id }
            val groupA = operations.addAppToGroup(a.id, PACKAGE)
            val groupB = operations.addAppToGroup(b.id, PACKAGE)
            val itemA = groupA.item()
            val itemB = groupB.item()
            assertTrue(operations.setClonePermission(itemA, Manifest.permission.CAMERA, true))
            assertTrue(operations.setClonePermission(itemB, Manifest.permission.CAMERA, false))
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { scenario ->
                fun vm(action: (MainViewModel) -> Unit) = scenario.onActivity { activity ->
                    action(ViewModelProvider(activity, app.mainContainer.viewModelFactory)[MainViewModel::class.java])
                }
                scenario.awaitRealForegroundHost()
                vm { it.openSpaceDetails(a.id) }
                await("Initial selected owned permission loaded") {
                    var ready = false
                    vm { ready = camera(it, a.id) == true && !it.spaceDetailsState.value.isLoadingSpaceDetails }
                    ready
                }
                assertRenderedCamera(itemA, granted = true)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { activity -> assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState) }
                assertNull("Production visible-host registry must release stopped host", mainActivityLaunchHosts.current())
                assertTrue(operations.setClonePermission(itemA, Manifest.permission.CAMERA, false))
                assertEquals(false, requireNotNull(operations.loadSpaceDetails(a.id)).permissions
                    .single { it.permission == Manifest.permission.CAMERA }.apps.single { it.packageName == PACKAGE }.granted)
                // Observe the pre-resume snapshot; the changed durable source is the refresh witness.
                var beforeResume: Boolean? = null
                vm { beforeResume = camera(it, a.id) }
                assertEquals("No permissions refresh was requested while stopped", true, beforeResume)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.awaitRealForegroundHost()
                await("Actual resume publishes newly denied source") {
                    var ready = false
                    vm { ready = camera(it, a.id) == false && !it.spaceDetailsState.value.isLoadingSpaceDetails }
                    ready
                }
                assertRenderedCamera(itemA, granted = false)

                var ownedSettingsOpened = false
                var settingsFailure: Throwable? = null
                try {
                scenario.onActivity { activity ->
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)))
                    ownedSettingsOpened = true
                }
                await("Actual Android Settings is foreground and host stopped") {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.packageName?.toString() == "com.android.settings" && mainActivityLaunchHosts.current() == null
                }
                assertTrue(operations.setClonePermission(itemA, Manifest.permission.CAMERA, true))
                assertEquals(true, requireNotNull(operations.loadSpaceDetails(a.id)).permissions
                    .single { it.permission == Manifest.permission.CAMERA }.apps.single { it.packageName == PACKAGE }.granted)
                // Settings was launched above this host in the same task: close the actual
                // owned Settings Activity with Back rather than merely foregrounding its task.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                scenario.awaitRealForegroundHost()
                ownedSettingsOpened = false
                await("Settings return refreshes exact selected source") {
                    var ready = false
                    vm { model -> ready = camera(model, a.id) == true &&
                        model.navigationState.value.destination == MainDestination.SPACE_DETAILS &&
                        !model.spaceDetailsState.value.isLoadingSpaceDetails }
                    ready
                }
                assertRenderedCamera(itemA, granted = true)
                assertEquals("Other owned space remains denied", false, requireNotNull(operations.loadSpaceDetails(b.id)).permissions
                    .single { it.permission == Manifest.permission.CAMERA }.apps.single { it.packageName == PACKAGE }.granted)
                } catch (error: Throwable) { settingsFailure = error; throw error }
                finally {
                    if (ownedSettingsOpened && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings") {
                        try {
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                            scenario.awaitRealForegroundHost()
                        } catch (cleanup: Throwable) {
                            if (settingsFailure != null) settingsFailure!!.addSuppressed(cleanup) else throw cleanup
                        }
                    }
                }
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            owned.asReversed().forEach { id ->
                try { operations.deleteGroup(id); assertNull(store.find(id)) } catch (error: Throwable) { errors += error }
            }
            try { assertEquals(baseline, publicTuples(store.loadSnapshot().groups)) } catch (error: Throwable) { errors += error }
            if (failure != null) errors.forEach(failure!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }

    private fun camera(vm: MainViewModel, id: String): Boolean? = vm.spaceDetailsState.value
        .spaceDetails?.takeIf { it.groupId == id }?.permissions?.singleOrNull { it.permission == Manifest.permission.CAMERA }
        ?.apps?.singleOrNull { it.packageName == PACKAGE }?.granted
    private fun assertRenderedCamera(item: GroupAppItem, granted: Boolean) {
        val tag = "space-permission-${Manifest.permission.CAMERA}-${item.launchKey}"
        if (compose.onAllNodesWithText("按權限管理 App").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("按權限管理 App").performScrollTo().performClick()
        }
        compose.onNodeWithTag("space-details").performScrollToNode(hasTestTag(tag))
        if (granted) compose.onNodeWithTag(tag).assertIsOn() else compose.onNodeWithTag(tag).assertIsOff()
    }
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE },
        "Owned lifecycle fixture", "fixture", true, "")
    private fun publicTuples(groups: List<Group>) = groups.sortedBy { it.id }.map {
        "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",") }"
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out: $description")
    }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
