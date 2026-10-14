package org.apptwin

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.apptwin.archive.SharedPreferencesArchiveExportSettingsStore

/** Host-owned dependencies survive activity recreation without retaining an activity. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MainApplicationContainer(application: Application) {
    val backgroundServiceRepository: org.apptwin.runtime.BackgroundServiceRepository =
        org.apptwin.runtime.ApplicationBackgroundServiceRepository(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate),
            Dispatchers.IO,
            org.apptwin.runtime.AndroidBackgroundServiceBackend(application),
        )
    val operationGraph = AndroidOperationCompositionRoot(application)
    private val operations = operationGraph.mainOperations()
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val launchDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val onboardingStore = AndroidOnboardingStore(application)
    private val archiveExportSettingsStore = SharedPreferencesArchiveExportSettingsStore(application)

    val viewModelFactory = MainViewModelFactory { savedStateHandle ->
        MainViewModel(
            savedStateHandle = savedStateHandle,
            operations = operations,
            ioDispatcher = ioDispatcher,
            launchDispatcher = launchDispatcher,
            onboardingStore = onboardingStore,
            archiveExportSettingsStore = archiveExportSettingsStore,
            backgroundServiceRepository = backgroundServiceRepository,
        )
    }
}
