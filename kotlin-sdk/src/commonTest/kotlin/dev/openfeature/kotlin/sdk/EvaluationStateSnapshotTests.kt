package dev.openfeature.kotlin.sdk

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EvaluationStateSnapshotTests {

    @BeforeTest
    fun setup() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun getEvaluationStateReturnsProviderAndContextTogether() = runTest {
        val provider = NoOpProvider()
        val evaluationContext = ImmutableContext("user-1", mapOf("plan" to Value.String("premium")))
        OpenFeatureAPI.setProviderAndWait(provider, evaluationContext)

        val state = OpenFeatureAPI.getEvaluationState()

        assertEquals(provider, state.provider)
        assertEquals(evaluationContext, state.context)
    }

    @Test
    fun trackUsesConsistentProviderContextSnapshotUnderConcurrency() = runTest {
        val providerA = GuardedTrackingProvider("A")
        val providerB = GuardedTrackingProvider("B")
        val mismatches = atomic(0)

        val swapJob = launch(Dispatchers.Default) {
            repeat(100) {
                if (it % 2 == 0) {
                    OpenFeatureAPI.setProviderAndWait(providerA, ImmutableContext("A"))
                } else {
                    OpenFeatureAPI.setProviderAndWait(providerB, ImmutableContext("B"))
                }
            }
        }

        val trackJob = launch(Dispatchers.Default) {
            repeat(500) {
                try {
                    OpenFeatureAPI.getClient().track("event")
                } catch (_: IllegalStateException) {
                    mismatches.incrementAndGet()
                }
            }
        }

        joinAll(swapJob, trackJob)
        assertEquals(0, mismatches.value, "track should not observe mismatched provider/context pairs")
    }

    @Test
    fun trackPassesStoredEvaluationContextToProvider() = runTest {
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

    private class GuardedTrackingProvider(
        private val expectedTargetingKey: String
    ) : NoOpProvider() {
        override fun track(
            trackingEventName: String,
            context: EvaluationContext?,
            details: TrackingEventDetails?
        ) {
            if (context?.getTargetingKey() != expectedTargetingKey) {
                throw IllegalStateException(
                    "Provider for '$expectedTargetingKey' received context '${context?.getTargetingKey()}'"
                )
            }
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