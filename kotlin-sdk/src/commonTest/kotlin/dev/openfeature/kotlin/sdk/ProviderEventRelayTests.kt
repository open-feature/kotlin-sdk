package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The SDK republishes provider events itself rather than exposing the provider's stream directly, so
 * that the status is always updated before subscribers see the event that caused it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEventRelayTests {

    @BeforeTest
    fun setUp() = runTest {
        OpenFeatureAPI.shutdown()
    }

    private class ControllableEmitter(name: String) : DoSomethingProvider(
        metadata = object : ProviderMetadata {
            override val name: String = name
        }
    ) {
        private val emissions = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = 16)

        override suspend fun initialize(initialContext: EvaluationContext?) {
            emissions.emit(OpenFeatureProviderEvents.ProviderReady())
        }

        override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
            // Nothing: these tests drive emissions explicitly.
        }

        override fun observe(): Flow<OpenFeatureProviderEvents> = emissions

        fun emit(event: OpenFeatureProviderEvents) {
            check(emissions.tryEmit(event)) { "Test emitter buffer exhausted" }
        }
    }

    @Test
    fun statusReflectsTheEventBeforeSubscribersSeeIt() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = ControllableEmitter("relay-ordering")
        val statusesSeenByHandler = mutableListOf<OpenFeatureStatus>()

        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderStale>().collect {
                statusesSeenByHandler.add(OpenFeatureAPI.getStatus())
            }
        }
        testScheduler.runCurrent()

        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        provider.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(listOf<OpenFeatureStatus>(OpenFeatureStatus.Stale), statusesSeenByHandler)
    }

    @Test
    fun handlerAttachedWhenAlreadyReadyRunsImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(ControllableEmitter("late-subscriber"), dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())

        val received = mutableListOf<OpenFeatureProviderEvents.ProviderReady>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderReady>().collect { received.add(it) }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()

        assertEquals(1, received.size)
        assertEquals("late-subscriber", received.single().eventDetails?.providerName)
    }

    @Test
    fun handlerAttachedWhenAlreadyStaleReceivesStaleNotReady() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = ControllableEmitter("stale-state")
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()
        provider.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()

        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()

        assertIs<OpenFeatureProviderEvents.ProviderStale>(received.single())
    }

    @Test
    fun statelessEventIsNotResurfacedForLateSubscribers() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = ControllableEmitter("stateless-event")
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        // The most recent event carries no status, so it must not be what a late subscriber is told.
        provider.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        testScheduler.advanceUntilIdle()

        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()

        assertIs<OpenFeatureProviderEvents.ProviderReady>(received.single())
    }

    @Test
    fun eventsFromAReplacedProviderAreNotResurfaced() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val first = ControllableEmitter("first")
        OpenFeatureAPI.setProviderAndWait(first, dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()
        first.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()

        OpenFeatureAPI.setProviderAndWait(ControllableEmitter("second"), dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()

        assertIs<OpenFeatureProviderEvents.ProviderReady>(received.single())
        assertEquals("second", received.single().eventDetails?.providerName)
    }

    @Test
    fun shutdownRevertsToNotReadyWithoutEmittingAnEvent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(ControllableEmitter("shutdown"), dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) }
        }
        testScheduler.runCurrent()
        // Subscribing while Ready yields exactly one event, so a later count proves nothing was added.
        assertEquals(1, received.size)

        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        // There is no event type for NOT_READY: the SDK infers the transition instead of emitting.
        assertEquals(1, received.size)
    }

    @Test
    fun everyRelayedEventCarriesTheEmittingProviderName() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = ControllableEmitter("attribution")
        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) }
        }
        testScheduler.runCurrent()

        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()
        provider.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertTrue(received.isNotEmpty())
        assertTrue(received.all { it.eventDetails?.providerName == "attribution" })
    }
}