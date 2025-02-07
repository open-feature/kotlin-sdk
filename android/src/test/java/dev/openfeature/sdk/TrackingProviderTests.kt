package dev.openfeature.sdk

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TrackingProviderTests {

    private lateinit var inMemoryTrackingProvider: InMemoryTrackingProvider

    @Before
    fun setup() {
        inMemoryTrackingProvider = InMemoryTrackingProvider()
    }

    @After
    fun tearDown() {
        OpenFeatureAPI.shutdown()
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsOnEmptyName() = runTest {
        OpenFeatureAPI.setProvider(inMemoryTrackingProvider)
        OpenFeatureAPI.getClient().track("")
        assertEquals(0, inMemoryTrackingProvider.trackings.size)
    }

    @Test
    fun sendWithoutDetailsAppendsContext() = runTest {
        OpenFeatureAPI.setProviderAndWait(inMemoryTrackingProvider)
        val evaluationContext = ImmutableContext(
            "targetingKey",
            mapOf("integer" to Value.Integer(33))
        )
        OpenFeatureAPI.setEvaluationContext(
            evaluationContext
        )
        OpenFeatureAPI.getClient().track("MyEventName")

        val trackedEventCall = inMemoryTrackingProvider.trackings[0]
        assertEquals("MyEventName", trackedEventCall.first)
        val trackedEventDetails = trackedEventCall.third
        assertNull(trackedEventDetails)
        val trackedContext = trackedEventCall.second
        assertEquals(evaluationContext, trackedContext)
    }

    @Test
    fun trackEventWithDetails() = runTest {
        OpenFeatureAPI.setProviderAndWait(inMemoryTrackingProvider)
        val evaluationContext = ImmutableContext(
            "targetingKey",
            mapOf("integer" to Value.Integer(33))
        )
        OpenFeatureAPI.setEvaluationContext(
            evaluationContext
        )
        OpenFeatureAPI.getClient().track(
            "Checkout",
            TrackingEventDetails(
                499.99,
                ImmutableStructure(
                    "numberOfItems" to Value.Integer(4),
                    "timeInCheckout" to Value.String("PT3M20S")
                )
            )
        )

        val trackedEventCall = inMemoryTrackingProvider.trackings[0]
        assertEquals("Checkout", trackedEventCall.first)
        val trackedEventDetails = trackedEventCall.third
        assertNotNull(trackedEventDetails!!.value)
        assertEquals(499.99, trackedEventDetails.value)
        assertEquals(2, trackedEventDetails.structure.asMap().size)
        assertEquals(Value.Integer(4), trackedEventDetails.structure.getValue("numberOfItems"))
        assertEquals(Value.String("PT3M20S"), trackedEventDetails.structure.getValue("timeInCheckout"))

        val trackedContext = trackedEventCall.second
        assertEquals(evaluationContext, trackedContext)
    }

    private class InMemoryTrackingProvider : NoOpProvider() {
        val trackings = mutableListOf<Triple<String, EvaluationContext?, TrackingEventDetails?>>()

        override fun track(
            trackingEventName: String,
            context: EvaluationContext?,
            details: TrackingEventDetails?
        ) {
            trackings.add(Triple(trackingEventName, context, details))
        }
    }
}