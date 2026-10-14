package org.apptwin.runtime

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.env.BackgroundExecutionSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.apptwin.MainActivity
import org.apptwin.ui.collectWhileStarted
import org.apptwin.ui.observeSpaceHealth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity lifecycle driving production collection/observation helpers; not MCS health proof. The existing Compose test clock accelerates helper delays. */
@RunWith(AndroidJUnit4::class)
class RealHostLifecycleSubscriptionE2eTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test
    fun realStopCancelsCollectionAndHealthObservationThenResumeSubscribesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val priorPolicy = BackgroundExecutionSettings.isEnabled(context)
        val source = MutableStateFlow("Owned lifecycle initial")
        val healthReads = AtomicInteger()
        val healthStops = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).useForegroundAcceptanceHost { scenario ->
            scenario.awaitRealForegroundHost()
            try {
            scenario.onActivity { host ->
                host.setContent {
                    val rendered by source.collectWhileStarted()
                    Text(rendered)
                    LaunchedEffect(host.lifecycle) {
                        host.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            observeSpaceHealth(refresh = { healthReads.incrementAndGet() },
                                stop = { healthStops.incrementAndGet() }, intervalMillis = 100)
                        }
                    }
                }
            }
            // Commit the real setContent composition/frame before observing its subscriptions.
            compose.waitForIdle()
            // Compose's existing test clock schedules the production helper's delay.
            compose.mainClock.advanceTimeBy(300)
            await("Actual upstream subscription and health callbacks") {
                source.subscriptionCount.value == 1 && healthReads.get() >= 2
            }
            compose.onNodeWithText("Owned lifecycle initial").assertIsDisplayed()
            scenario.moveToState(Lifecycle.State.CREATED)
            await("Actual upstream cancellation and observation stop") {
                source.subscriptionCount.value == 0 && healthStops.get() >= 1
            }
            val stoppedReads = healthReads.get()
            source.value = "Owned lifecycle resumed"
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            assertEquals(0, source.subscriptionCount.value)
            assertEquals("Cancelled observer cannot keep polling when its clock advances", stoppedReads, healthReads.get())
            assertEquals("UI stop does not change durable daemon policy", priorPolicy, BackgroundExecutionSettings.isEnabled(context))
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.awaitRealForegroundHost()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(300)
            await("Actual resubscription and observer restart") {
                source.subscriptionCount.value == 1 && healthReads.get() > stoppedReads
            }
            compose.onNodeWithText("Owned lifecycle resumed").assertIsDisplayed()
            assertEquals(priorPolicy, BackgroundExecutionSettings.isEnabled(context))
            } catch (failure: Throwable) {
                var host = "unavailable"
                runCatching { scenario.onActivity { activity ->
                    host = "lifecycle=${activity.lifecycle.currentState.name},focused=${activity.hasWindowFocus()},task=${activity.taskId},destroyed=${activity.isDestroyed}"
                } }.exceptionOrNull()?.let(failure::addSuppressed)
                failure.addSuppressed(AssertionError("Lifecycle subscription metadata: subscriptions=${source.subscriptionCount.value}," +
                    "healthReads=${healthReads.get()},healthStops=${healthStops.get()},host=$host"))
                throw failure
            }
        }
        await("Composition disposal releases upstream") { source.subscriptionCount.value == 0 }
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(50) }
        error("Timed out: $description")
    }
}
