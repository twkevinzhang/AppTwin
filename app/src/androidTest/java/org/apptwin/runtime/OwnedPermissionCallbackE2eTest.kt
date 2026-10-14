package org.apptwin.runtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VPackageManager
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.GroupAppItem
import org.apptwin.MainActivity
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Activity's production permission-result entry point and durable virtual scope. */
@RunWith(AndroidJUnit4::class)
class OwnedPermissionCallbackE2eTest {
    @Test
    fun capturedCallbackTargetsOriginalSpaceAndCancellationCannotGrantAnotherSpace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AppTwinApplication
        val operations = app.mainContainer.operationGraph.mainOperations()
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val store = FileGroupStore(context)
        val baseline = tuples(store.loadSnapshot().groups)
        val owned = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            val groups = listOf("A", "B").map { suffix ->
                val group = operations.createGroup("Owned callback $suffix").also { owned += it.id }
                operations.addAppToGroup(group.id, PACKAGE)
            }
            groups.forEach { assertTrue(operations.setClonePermission(it.item(), Manifest.permission.CAMERA, false)) }
            val a = groups[0]; val b = groups[1]
            ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { scenario ->
                fun vm(action: (MainViewModel) -> Unit) = scenario.onActivity { activity ->
                    action(ViewModelProvider(activity, app.mainContainer.viewModelFactory)[MainViewModel::class.java])
                }
                scenario.awaitRealForegroundHost()
                await("Both exact callback source identities enriched") {
                    var ready = false
                    vm { model -> ready = !model.homeState.value.isRefreshing && groups.all { group ->
                        model.sessionState.value.groups.any { it.groupId == group.id && it.apps.any { clone -> clone.app.packageName == PACKAGE } }
                    } }
                    ready
                }
                vm { it.openSpaceDetails(a.id) }
                vm { it.prepareClonePermissionRequest(a.item(), Manifest.permission.CAMERA) {} }
                await("Original callback request prepared") {
                    var ready = false; vm { ready = it.pendingClonePermissionName() == Manifest.permission.CAMERA }; ready
                }
                var originalTicket: Long? = null
                vm { originalTicket = it.pendingClonePermissionTicket() }
                assertNotNull(originalTicket)
                vm { it.openSpaceDetails(b.id) }
                vm { it.completeClonePermissionRequest(Manifest.permission.CAMERA, true, originalTicket) }
                await("Callback grants only captured A") { granted(a) && !granted(b) }
                vm { assertEquals("Current selection stays B", b.id, it.navigationState.value.selectedSpaceId) }
                assertTrue(operations.setClonePermission(a.item(), Manifest.permission.CAMERA, false))
                vm { it.prepareClonePermissionRequest(a.item(), Manifest.permission.CAMERA) {} }
                await("Cancelable callback prepared") {
                    var ready = false; vm { ready = it.pendingClonePermissionName() == Manifest.permission.CAMERA }; ready
                }
                var cancelledTicket: Long? = null
                vm { cancelledTicket = it.pendingClonePermissionTicket(); it.cancelClonePermissionRequest() }
                vm { it.prepareClonePermissionRequest(b.item(), Manifest.permission.CAMERA) {} }
                await("Fresh B request after cancelled A") {
                    var ready = false; vm { ready = it.pendingClonePermissionTicket() != null }; ready
                }
                var ticketB: Long? = null
                vm { ticketB = it.pendingClonePermissionTicket() }
                assertNotEquals(cancelledTicket, ticketB)
                vm { it.completeClonePermissionRequest(Manifest.permission.CAMERA, true, cancelledTicket) }
                vm { assertEquals("Stale callback must not consume current B request", ticketB, it.pendingClonePermissionTicket()) }
                assertFalse(granted(a)); assertFalse(granted(b))
                vm { it.completeClonePermissionRequest(Manifest.permission.CAMERA, false, ticketB) }
                await("Valid B denial consumes its exact request") {
                    var ready = false; vm { ready = it.pendingClonePermissionTicket() == null }; ready
                }
                vm { it.prepareClonePermissionRequest(a.item(), Manifest.permission.CAMERA) {} }
                await("Removed-source callback prepared") {
                    var ready = false; vm { ready = it.pendingClonePermissionName() == Manifest.permission.CAMERA }; ready
                }
                var removedTicket: Long? = null
                vm { removedTicket = it.pendingClonePermissionTicket() }
                operations.deleteGroup(a.id)
                vm { it.completeClonePermissionRequest(Manifest.permission.CAMERA, true, removedTicket) }
                // Drain a real production refresh through the serialized I/O lane before final assertions.
                vm { it.refresh() }
                await("Callback and refresh terminal") {
                    var ready = false; vm { ready = !it.homeState.value.isRefreshing && it.pendingClonePermissionName() == null }; ready
                }
                assertNull(store.find(a.id)); assertFalse(granted(b))
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            val errors = mutableListOf<Throwable>()
            owned.asReversed().forEach { id ->
                try { if (store.find(id) != null) operations.deleteGroup(id); assertNull(store.find(id)) }
                catch (error: Throwable) { errors += error }
            }
            try { assertEquals(baseline, tuples(store.loadSnapshot().groups)) } catch (error: Throwable) { errors += error }
            if (failure != null) errors.forEach(failure!!::addSuppressed)
            else if (errors.isNotEmpty()) throw errors.first().also { error -> errors.drop(1).forEach(error::addSuppressed) }
        }
    }
    private fun granted(group: Group) = VPackageManager.get().checkPermission(Manifest.permission.CAMERA,
        PACKAGE, requireNotNull(group.environmentBinding).internalId) == PackageManager.PERMISSION_GRANTED
    private fun Group.item() = GroupAppItem(id, name, health, apps.single { it.packageName == PACKAGE }, "Owned callback fixture", "fixture", true, "")
    private fun tuples(groups: List<Group>) = groups.sortedBy { it.id }.map { "${it.id}|${it.environmentBinding?.internalId}|${it.apps.map { app -> app.packageName }.sorted().joinToString(",") }" }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        error("Timed out: $description")
    }
    private companion object { const val PACKAGE = "org.apptwin.fixture" }
}
