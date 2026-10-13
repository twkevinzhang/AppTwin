package org.apptwin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras

/** Uses the activity's saved-state owners and default arguments, including process restoration. */
internal class MainViewModelFactory(
    private val createMainViewModel: (SavedStateHandle) -> MainViewModel,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == MainViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return createMainViewModel(extras.createSavedStateHandle()) as T
    }
}
