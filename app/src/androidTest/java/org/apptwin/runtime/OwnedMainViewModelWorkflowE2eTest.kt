package org.apptwin.runtime

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.MainActivity
import org.apptwin.MainDestination
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production ViewModel/state owners -> real adapters/runtime -> rendered owned-space workflow. */
@RunWith(AndroidJUnit4::class)
class OwnedMainViewModelWorkflowE2eTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test
    fun createPickLaunchInspectAndDeleteOwnedSpaceThroughProductionViewModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as AppTwinApplication
        val operations = application.mainContainer.operationGraph.mainOperations()
        val store = FileGroupStore(context)
        val baselineGroups = store.loadSnapshot().groups
        val baseline = baselineGroups.map { it.id }.toSet()
        val baselinePublic = publicTuples(baselineGroups)
        val name = "Owned VM ${UUID.randomUUID().toString().take(8)}"
        var ownedId: String? = null
        var primary: Throwable? = null
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            val revision = AndroidPackageRevisionImporter(context).sync(PACKAGE)
            assertTrue("Installed fixture revision ready", revision is RevisionImportResult.Activated || revision is RevisionImportResult.AlreadyCurrent)
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { scenario ->
                scenario.awaitRealForegroundHost()
                fun vm(action: (MainViewModel) -> Unit) = scenario.onActivity { activity ->
                    action(ViewModelProvider(activity, application.mainContainer.viewModelFactory)[MainViewModel::class.java])
                }
                await("initial recovery/catalog readiness") {
                    var ready = false
                    vm { model -> ready = !model.homeState.value.isRefreshing && model.homeState.value.error == null &&
                        model.sessionState.value.busyGroupId == null && model.sessionState.value.busyPackageName == null }
                    ready
                }
                vm { model -> assertFalse("Host onboarding must already be completed", model.navigationState.value.showOnboarding) }
                vm { it.navigate(MainDestination.HOME) }
                await("Actual navigation Home before creation") {
                    var ready = false
                    vm { ready = it.navigationState.value.destination == MainDestination.HOME }
                    ready
                }
                vm { it.createGroup(name) }
                await("owned space persisted and typed Home visible") {
                    val owned = store.loadSnapshot().groups.singleOrNull { it.name == name && it.id !in baseline }
                    if (owned != null) ownedId = owned.id
                    var ready = false
                    vm { model -> ready = owned != null && !model.homeState.value.isCreatingGroup &&
                        !model.homeState.value.isRefreshing && model.homeState.value.groups.any { it.groupId == owned.id } }
                    ready
                }
                val id = requireNotNull(ownedId)
                awaitOwnText(name)
                vm { it.openAppPicker(id) }
                await("typed exact picker catalog") {
                    var ready = false
                    vm { model -> ready = model.pickerState.value.appPickerGroupId == id &&
                        !model.pickerState.value.isRefreshing && model.pickerState.value.apps.any { it.entry.packageName == PACKAGE } }
                    ready
                }
                vm { model -> model.selectApp(model.pickerState.value.apps.single { it.entry.packageName == PACKAGE }) }
                await("membership created through picker") { store.find(id)?.contains(PACKAGE) == true }
                val group = requireNotNull(store.find(id))
                val user = requireNotNull(group.environmentBinding).internalId
                val original = awaitForegroundFixture(user)
                await("first launch coordinator released and journal terminal") {
                    var ready = false
                    vm { model -> ready = model.sessionState.value.launchingAppKey == null &&
                        model.sessionState.value.busyPackageName == null && model.pickerState.value.appPickerGroupId == null }
                    ready && FileOperationRecordStore(context).listPending().none { it.target.spaceId == id }
                }
                scenario.awaitRealForegroundHost()
                await("typed home clone available for warm launch") {
                    var ready = false
                    vm { model -> ready = !model.homeState.value.isRefreshing && model.homeState.value.groups
                        .any { it.groupId == id && it.apps.any { app -> app.app.packageName == PACKAGE } } }
                    ready
                }
                await("Original fixture paused before warm user operation") {
                    val state = runCatching { JSONObject(File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE),
                        "files/fixture-lifecycle.json").readText()) }.getOrNull()
                    state != null && state.optString("instance") == original.getString("instance") &&
                        state.optInt("pid") == original.getInt("pid") && state.optInt("task") == original.getInt("task") &&
                        state.optString("phase") in setOf("PAUSED", "STOPPED") && !state.optBoolean("resumed")
                }
                vm { it.launchGroupApp(id, PACKAGE) }
                val warm = awaitForegroundFixture(user)
                listOf("instance", "pid", "task", "launchCount").forEach { key ->
                    assertEquals("Warm ViewModel launch retains $key", original.get(key), warm.get(key))
                }
                await("warm coordinator release") {
                    var ready = false
                    vm { ready = it.sessionState.value.launchingAppKey == null && it.sessionState.value.busyPackageName == null }
                    ready && FileOperationRecordStore(context).listPending().none { it.target.spaceId == id }
                }
                scenario.awaitRealForegroundHost()
                vm { it.openSpaceDetails(id) }
                await("typed details exact owned selection") {
                    var ready = false
                    vm { model -> ready = model.navigationState.value.destination == MainDestination.SPACE_DETAILS &&
                        model.navigationState.value.selectedSpaceId == id && model.spaceDetailsState.value.selectedSpaceId == id &&
                        model.spaceDetailsState.value.spaceDetails?.groupId == id && !model.spaceDetailsState.value.isLoadingSpaceDetails }
                    ready
                }
                awaitOwnText(name)
                val directories = listOf(VEnvironment.getDataUserPackageDirectory(user, PACKAGE),
                    VEnvironment.getDeDataUserPackageDirectory(user, PACKAGE), VEnvironment.getVirtualPrivateStorageDir(user, PACKAGE))
                vm { it.deleteGroup(id) }
                await("ViewModel delete cleanup and saved navigation") {
                    var ready = false
                    vm { model -> ready = model.navigationState.value.destination == MainDestination.HOME &&
                        model.navigationState.value.selectedSpaceId == null && model.sessionState.value.busyGroupId == null }
                    ready && store.find(id) == null && directories.none(File::exists)
                }
                assertEquals("Existing public space identities/environment/memberships preserved", baselinePublic, publicTuples(store.loadSnapshot().groups))
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            val cleanupFailures = mutableListOf<Throwable>()
            val cleanupIds = listOfNotNull(ownedId) + store.loadSnapshot().groups
                .filter { it.name == name && it.id !in baseline }.map { it.id }
            cleanupIds.distinct().forEach { id ->
                try { if (store.find(id) != null) operations.deleteGroup(id); assertNull(store.find(id)) }
                catch (error: Throwable) { cleanupFailures += error }
            }
            try { assertEquals("Existing public space identities/environment/memberships preserved", baselinePublic, publicTuples(store.loadSnapshot().groups)) }
            catch (error: Throwable) { cleanupFailures += error }
            if (primary != null) cleanupFailures.forEach(primary!!::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) throw cleanupFailures.first().also { error -> cleanupFailures.drop(1).forEach(error::addSuppressed) }
        }
    }

    private fun publicTuples(groups: List<org.apptwin.groups.Group>): List<String> =
        groups.sortedBy { it.id }.map { group ->
            "${group.id}|${group.environmentBinding?.internalId}|${group.apps.map { it.packageName }.sorted().joinToString(",") }"
        }

    private fun awaitOwnText(name: String) {
        // EmptyComposeRule observes the real ActivityScenario's rendered Compose roots.
        // Scroll the actual list if this label has not yet been composed in the viewport.
        val present = compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        if (!present) compose.onNode(hasScrollAction(), useUnmergedTree = true).performScrollToNode(hasText(name))
        compose.waitUntil(40_000) {
            compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
    }
    private fun awaitForegroundFixture(user: Int): JSONObject {
        val file = File(VEnvironment.getDataUserPackageDirectory(user, PACKAGE), "files/fixture-lifecycle.json")
        var observed: JSONObject? = null
        await("Actual exact fixture foreground") {
            observed = runCatching { JSONObject(file.readText()) }.getOrNull()
            observed?.let { it.optString("phase") == "RESUMED" && it.optBoolean("resumed") && it.optBoolean("focused") &&
                !it.optBoolean("finishing") && !it.optBoolean("changingConfigurations") } == true
        }
        return requireNotNull(observed).also {
            assertEquals("Only first creation accepted", 1, it.getInt("launchCount"))
            val task = com.lody.virtual.client.ipc.VActivityManager.get().getTaskInfo(it.getInt("task"))
            assertEquals(user, task?.userId)
            assertEquals(PACKAGE, task?.topActivity?.packageName)
        }
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 40_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out: $description")
    }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
