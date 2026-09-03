package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ITERATIONS = 300

/**
 * Racing setProvider against a concurrent setEvaluationContext, which only JVM and native can do —
 * JS is single-threaded.
 */
@OptIn(ExperimentalIsolatedApi::class)
class ProviderLifecycleOrderingTest {

    @AfterTest
    fun tearDown() {
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    private class OrderRecordingProvider(private val order: MutableList<String>) : NoOpProvider() {
        override suspend fun initialize(initialContext: EvaluationContext?) {
            order += "initialize"
            super.initialize(initialContext)
        }

        override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
            order += "onContextSet"
        }
    }

    @Test
    fun aConcurrentContextSetNeverEntersBeforeInitializeOnTheSameRegistration() = runBlocking {
        repeat(ITERATIONS) { iteration ->
            val instance = createOpenFeatureAPIInstance()
            val order = CopyOnWriteArrayList<String>()
            val provider = OrderRecordingProvider(order)

            val setProviderJob = launch(Dispatchers.Default) { instance.setProvider(provider) }
            val setContextJob = launch(Dispatchers.Default) { instance.setEvaluationContext(ImmutableContext()) }
            setProviderJob.join()
            setContextJob.join()
            // Both calls only start work on the registration's own scope; give it time to run.
            delay(20)

            val recorded = order.toList()
            if (recorded.contains("onContextSet")) {
                assertEquals(
                    "initialize",
                    recorded.first(),
                    "iteration $iteration observed onContextSet before initialize: $recorded"
                )
            }
        }
    }
}