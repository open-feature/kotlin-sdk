package dev.openfeature.kotlin.sdk

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvaluationStateSnapshotTests {

    private suspend fun withIsolatedApi(block: suspend () -> Unit) {
        try {
            OpenFeatureAPI.shutdown()
            block()
        } finally {
            OpenFeatureAPI.shutdown()
        }
    }

    @Test
    fun getEvaluationStateReturnsProviderAndContextTogether() = runTest {
        withIsolatedApi {
            val provider = NoOpProvider()
            val evaluationContext = ImmutableContext("user-1", mapOf("plan" to Value.String("premium")))
            OpenFeatureAPI.setProviderAndWait(provider, evaluationContext)

            val state = OpenFeatureAPI.getEvaluationState()

            assertEquals(provider, state.provider)
            assertEquals(evaluationContext, state.context)
        }
    }

    @Test
    fun snapshotUsesConsistentProviderContextUnderConcurrency() = runTest {
        withIsolatedApi {
            val mismatches = atomic(0)
            val callsA = atomic(0)
            val callsB = atomic(0)
            val startGate = CompletableDeferred<Unit>()
            val onMismatch: () -> Unit = { mismatches.incrementAndGet(); Unit }
            val providerA = GuardedProvider("A", onMismatch) { callsA.incrementAndGet(); Unit }
            val providerB = GuardedProvider("B", onMismatch) { callsB.incrementAndGet(); Unit }

            OpenFeatureAPI.setProviderAndWait(providerA, ImmutableContext("A"))

            val swapJob = launch(Dispatchers.Default) {
                startGate.await()
                repeat(100) {
                    if (it % 2 == 0) {
                        OpenFeatureAPI.setProviderAndWait(providerA, ImmutableContext("A"))
                    } else {
                        OpenFeatureAPI.setProviderAndWait(providerB, ImmutableContext("B"))
                    }
                    yield()
                }
            }

            val trackJob = launch(Dispatchers.Default) {
                startGate.await()
                repeat(500) {
                    try {
                        OpenFeatureAPI.getClient().track("event")
                    } catch (_: IllegalStateException) {
                        // mismatch already recorded by GuardedProvider
                    }
                    yield()
                }
            }

            val evaluateJob = launch(Dispatchers.Default) {
                startGate.await()
                repeat(500) {
                    OpenFeatureAPI.getClient().getBooleanValue("flag", false)
                    yield()
                }
            }

            startGate.complete(Unit)
            joinAll(swapJob, trackJob, evaluateJob)

            assertTrue(callsA.value > 0, "providerA should handle calls during concurrent swaps")
            assertTrue(callsB.value > 0, "providerB should handle calls during concurrent swaps")
            assertEquals(
                0,
                mismatches.value,
                "track and evaluation should not observe mismatched provider/context pairs"
            )
        }
    }

    @Test
    fun trackPassesStoredEvaluationContextToProvider() = runTest {
        withIsolatedApi {
            val trackingProvider = CapturingTrackingProvider()
            val evaluationContext = ImmutableContext(
                "user-1",
                mapOf("plan" to Value.String("premium"), "num" to Value.Integer(10))
            )
            OpenFeatureAPI.setProviderAndWait(trackingProvider)
            OpenFeatureAPI.setEvaluationContextAndWait(evaluationContext)

            OpenFeatureAPI.getClient().track(
                "test",
                TrackingEventDetails(
                    5.0,
                    ImmutableStructure("items" to Value.Integer(2))
                )
            )

            assertEquals("test", trackingProvider.lastEventName)
            assertEquals(evaluationContext, trackingProvider.lastContext)
            assertNotNull(trackingProvider.lastDetails)
            assertEquals(5.0, trackingProvider.lastDetails?.value)
            assertEquals(Value.Integer(2), trackingProvider.lastDetails?.structure?.getValue("items"))
        }
    }

    private class GuardedProvider(
        private val expectedTargetingKey: String,
        private val onMismatch: () -> Unit,
        private val onMatchedCall: () -> Unit
    ) : NoOpProvider() {
        private fun assertMatchingContext(context: EvaluationContext?) {
            if (context?.getTargetingKey() != expectedTargetingKey) {
                onMismatch()
                throw IllegalStateException(
                    "Provider for '$expectedTargetingKey' received context '${context?.getTargetingKey()}'"
                )
            }
            onMatchedCall()
        }

        override fun getBooleanEvaluation(
            key: String,
            defaultValue: Boolean,
            context: EvaluationContext?
        ): ProviderEvaluation<Boolean> {
            assertMatchingContext(context)
            return super.getBooleanEvaluation(key, defaultValue, context)
        }

        override fun track(
            trackingEventName: String,
            context: EvaluationContext?,
            details: TrackingEventDetails?
        ) {
            assertMatchingContext(context)
        }
    }

    private class CapturingTrackingProvider : NoOpProvider() {
        var lastEventName: String? = null
        var lastContext: EvaluationContext? = null
        var lastDetails: TrackingEventDetails? = null

        override fun track(
            trackingEventName: String,
            context: EvaluationContext?,
            details: TrackingEventDetails?
        ) {
            lastEventName = trackingEventName
            lastContext = context
            lastDetails = details
        }
    }
}