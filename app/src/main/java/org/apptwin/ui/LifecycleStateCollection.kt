package org.apptwin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Uses the already-resolved Lifecycle APIs; subscriptions end while the host is stopped. */
@Composable
internal fun <T> StateFlow<T>.collectWhileStarted(): State<T> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return key(this) {
        val initialValue = remember(this) { value }
        produceState(initialValue, this, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { value = it }
            }
        }
    }
}

/** Only observes health; cancelling this loop never disables the application workload. */
internal suspend fun observeSpaceHealth(
    refresh: () -> Unit,
    stop: () -> Unit,
    intervalMillis: Long = 10_000L,
) {
    try {
        while (true) {
            refresh()
            delay(intervalMillis)
        }
    } finally {
        stop()
    }
}
