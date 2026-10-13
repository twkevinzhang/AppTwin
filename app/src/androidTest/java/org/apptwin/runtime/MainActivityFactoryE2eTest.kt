package org.apptwin.runtime

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.apptwin.AppTwinApplication
import org.apptwin.MainActivity
import org.apptwin.MainDestination
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real production factory acceptance; owner reconstruction is not a process-death claim. */
@RunWith(AndroidJUnit4::class)
class MainActivityFactoryE2eTest {
    @Test
    fun activityRecreationRetainsSelectionAndApplicationGraph() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as AppTwinApplication
        val container = application.mainContainer
        val graph = container.operationGraph
        val operations = graph.mainOperations()
        val owned = operations.createGroup("Owned factory recreation")
        var primary: Throwable? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var firstActivity: MainActivity
                lateinit var firstViewModel: MainViewModel
                scenario.onActivity { activity ->
                    firstActivity = activity
                    assertSame(container, (activity.application as AppTwinApplication).mainContainer)
                    assertSame(graph, container.operationGraph)
                    assertSame(graph.spaces, container.operationGraph.spaces)
                    assertSame(graph.clones, container.operationGraph.clones)
                    firstViewModel = ViewModelProvider(activity, container.viewModelFactory)[MainViewModel::class.java]
                    firstViewModel.openSpaceDetails(owned.id)
                }
                awaitSelection(scenario, owned.id)
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotSame(firstActivity, activity)
                    assertSame(container, (activity.application as AppTwinApplication).mainContainer)
                    assertSame(graph, container.operationGraph)
                    assertSame(graph.spaces, container.operationGraph.spaces)
                    assertSame(graph.clones, container.operationGraph.clones)
                    val restored = ViewModelProvider(activity, container.viewModelFactory)[MainViewModel::class.java]
                    assertSame(firstViewModel, restored)
                    assertEquals(owned.id, restored.uiState.selectedSpaceId)
                    assertEquals(MainDestination.SPACE_DETAILS, restored.uiState.destination)
                }
                awaitSelection(scenario, owned.id)
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try {
                operations.deleteGroup(owned.id)
                assertTrue("Owned space cleanup", FileGroupStore(context).find(owned.id) == null)
            } catch (cleanup: Throwable) {
                if (primary != null) primary!!.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    @Test
    fun freshSavedStateOwnerRestoresSelectionUsingProductionFactory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as AppTwinApplication
        val operations = application.mainContainer.operationGraph.mainOperations()
        val owned = operations.createGroup("Owned factory saved state")
        val owners = mutableListOf<TestOwner>()
        var primary: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                val first = TestOwner(null).also(owners::add)
                val original = ViewModelProvider(first.viewModelStore,
                    application.mainContainer.viewModelFactory, first.extras())[MainViewModel::class.java]
                original.openSpaceDetails(owned.id)
                val saved = Bundle().also(first.controller::performSave)
                first.viewModelStore.clear()
                first.registry.currentState = Lifecycle.State.DESTROYED
                val second = TestOwner(saved).also(owners::add)
                val restored = ViewModelProvider(second.viewModelStore,
                    application.mainContainer.viewModelFactory, second.extras())[MainViewModel::class.java]
                assertNotSame(original, restored)
                assertEquals(owned.id, restored.uiState.selectedSpaceId)
                assertEquals(MainDestination.SPACE_DETAILS, restored.uiState.destination)
            }
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            instrumentation.runOnMainSync { owners.forEach { it.viewModelStore.clear() } }
            try {
                operations.deleteGroup(owned.id)
                assertTrue("Owned space cleanup", FileGroupStore(context).find(owned.id) == null)
            } catch (cleanup: Throwable) {
                if (primary != null) primary!!.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun awaitSelection(scenario: ActivityScenario<MainActivity>, id: String) {
        repeat(150) {
            var ready = false
            scenario.onActivity { activity ->
                val container = (activity.application as AppTwinApplication).mainContainer
                val state = ViewModelProvider(activity, container.viewModelFactory)[MainViewModel::class.java].uiState
                ready = state.selectedSpaceId == id && state.destination == MainDestination.SPACE_DETAILS &&
                    state.spaceDetails?.groupId == id && !state.isLoadingSpaceDetails
            }
            if (ready) return
            Thread.sleep(100)
        }
        error("Owned selected space did not load through production factory")
    }

    private class TestOwner(restored: Bundle?) : SavedStateRegistryOwner, ViewModelStoreOwner {
        val registry = LifecycleRegistry(this)
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        init {
            controller.performAttach()
            enableSavedStateHandles()
            controller.performRestore(restored)
            registry.currentState = Lifecycle.State.CREATED
        }
        fun extras() = MutableCreationExtras().apply {
            this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@TestOwner
            this[VIEW_MODEL_STORE_OWNER_KEY] = this@TestOwner
        }
    }
}
