package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The lifecycle contracts the SDK owes a provider that reports its own status: a report made
 * mid-`initialize`, a provider that reports nothing, a registration superseded before it runs.
 */
@OptIn(ExperimentalIsolatedApi::class, ExperimentalCoroutinesApi::class)
class ProviderLifecycleTests {

    @AfterTest
    fun tearDown() {
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    /** Reports [eventsOnInitialize] from inside `initialize`, then optionally throws. */
    private class ReportingProvider(
        private val eventsOnInitialize: List<OpenFeatureProviderEvents> = emptyList(),
        private val initializeFailure: Throwable? = null
    ) : NoOpProvider() {
        private val statusTracker = ProviderStatusTracker()

        override val status: OpenFeatureStatus get() = statusTracker.status

        override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

        override suspend fun initialize(initialContext: EvaluationContext?) {
            eventsOnInitialize.forEach { statusTracker.send(it) }
            initializeFailure?.let { throw it }
        }

        override fun shutdown() = statusTracker.reset()
    }

    @Test
    fun readinessReportedDuringInitializeIsNotOverwrittenWhenItReturns() = runTest {
        val instance = createOpenFeatureAPIInstance()
        // The provider decides it is stale while initializing, and says nothing further.
        val provider = ReportingProvider(listOf(OpenFeatureProviderEvents.ProviderStale()))

        instance.setProviderAndWait(provider)
        advanceUntilIdle()

        // The SDK concludes nothing from initialize having returned: the provider's own report stands.
        assertEquals(OpenFeatureStatus.Stale, instance.getStatus())
    }

    @Test
    fun aProviderThatThrowsWithoutReportingStaysNotReady() = runTest {
        val instance = createOpenFeatureAPIInstance()
        val provider = ReportingProvider(initializeFailure = OpenFeatureError.GeneralError("no connection"))

        // The failure is logged, not rethrown: a provider reports its own status, and this one did not.
        instance.setProviderAndWait(provider)
        advanceUntilIdle()

        assertEquals(OpenFeatureStatus.NotReady, instance.getStatus())
    }

    @Test
    fun aProviderThatThrowsAfterReportingKeepsTheStatusItReported() = runTest {
        val instance = createOpenFeatureAPIInstance()
        val provider = ReportingProvider(
            eventsOnInitialize = listOf(
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(message = "handshake rejected")
                )
            ),
            initializeFailure = OpenFeatureError.GeneralError("handshake rejected")
        )

        instance.setProviderAndWait(provider)
        advanceUntilIdle()

        val status = assertIs<OpenFeatureStatus.Error>(instance.getStatus())
        assertEquals("handshake rejected", status.error.message)
    }

    @Test
    fun aProviderSupersededBeforeItsRegistrationRanIsStillRetired() = runTest {
        val instance = createOpenFeatureAPIInstance()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val superseded = SpyProvider()

        // Three fire-and-forget swaps with no chance to run in between: the middle registration's job
        // is cancelled before it is ever dispatched, so retiring must not depend on that job running.
        instance.setProvider(superseded, dispatcher = dispatcher)
        instance.setProvider(NoOpProvider(), dispatcher = dispatcher)
        instance.setProvider(NoOpProvider(), dispatcher = dispatcher)

        // Polled rather than advanced: a fire-and-forget swap retires its predecessor off the
        // caller's thread, so the teardown does not run on this test's virtual clock.
        waitAssert {
            assertEquals(1, superseded.shutdownCalls.value, "a dropped provider must still be shut down")
        }

        // ...and must have been released from the registry, so another instance can take it on.
        val other = createOpenFeatureAPIInstance()
        other.setProviderAndWait(superseded)
        advanceUntilIdle()
        assertTrue(other.getProvider() === superseded)
    }

    @Test
    fun reRegisteringTheSameProviderDoesNotShutItDown() = runTest {
        val instance = createOpenFeatureAPIInstance()
        val provider = SpyProvider()

        instance.setProviderAndWait(provider)
        instance.setProviderAndWait(provider)
        advanceUntilIdle()

        assertEquals(0, provider.shutdownCalls.value)
        assertEquals(OpenFeatureStatus.Ready, instance.getStatus())
    }

    /** Blocks in whichever lifecycle method is gated, and records the ones that ran to completion. */
    private class GatedProvider(
        private val gateInitialize: Boolean = false,
        private val gateContextSet: Boolean = false
    ) : NoOpProvider() {
        private val statusTracker = ProviderStatusTracker()

        val completed = mutableListOf<String>()
        val initializeStarted = Channel<Unit>(Channel.UNLIMITED)
        val releaseInitialize = Channel<Unit>(Channel.UNLIMITED)
        val contextSetStarted = Channel<Unit>(Channel.UNLIMITED)
        val releaseContextSet = Channel<Unit>(Channel.UNLIMITED)

        override val status: OpenFeatureStatus get() = statusTracker.status

        override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

        override suspend fun initialize(initialContext: EvaluationContext?) {
            if (gateInitialize) {
                initializeStarted.send(Unit)
                releaseInitialize.receive()
            }
            statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
            completed += "initialize"
        }

        override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
            if (gateContextSet) {
                contextSetStarted.send(Unit)
                releaseContextSet.receive()
            }
            completed += "onContextSet"
        }

        override fun shutdown() = statusTracker.reset()
    }

    @Test
    fun reRegisteringTheSameProviderDoesNotCancelItsInFlightInitialize() = runTest {
        val instance = createOpenFeatureAPIInstance()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = GatedProvider(gateInitialize = true)

        instance.setProvider(provider, dispatcher = dispatcher)
        provider.initializeStarted.receive()

        // Re-registering keeps the registration, so the work already running on its scope survives.
        instance.setProvider(provider, dispatcher = dispatcher)
        provider.releaseInitialize.send(Unit)
        provider.releaseInitialize.send(Unit)
        advanceUntilIdle()

        assertEquals(listOf("initialize", "initialize"), provider.completed)
    }

    @Test
    fun reRegisteringTheSameProviderDoesNotCancelItsInFlightReconciliation() = runTest {
        val instance = createOpenFeatureAPIInstance()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = GatedProvider(gateContextSet = true)

        instance.setProviderAndWait(provider, dispatcher = dispatcher)
        instance.setEvaluationContext(ImmutableContext("ctx"))
        provider.contextSetStarted.receive()

        instance.setProvider(provider, dispatcher = dispatcher)
        provider.releaseContextSet.send(Unit)
        advanceUntilIdle()

        assertTrue(
            provider.completed.contains("onContextSet"),
            "the reconciliation was cancelled by the rebind: ${provider.completed}"
        )
    }
}