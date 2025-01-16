package dev.openfeature.sdk

import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
import dev.openfeature.sdk.events.observeProviderReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventsHandlerTest {
    @Test
    fun observing_event_observer_works() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)
        var emitted = false

        val job = backgroundScope.launch(dispatcher) {
            provider.observe<OpenFeatureEvents.ProviderReady>()
                .take(1)
                .collect {
                    emitted = true
                }
        }
        provider.emitReady()
        job.join()
        Assert.assertTrue(emitted)
    }

    @Test
    fun multiple_subscribers_works() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)
        val numberOfSubscribers = 10
        val parentJob = Job()
        var emitted = 0

        repeat(numberOfSubscribers) {
            CoroutineScope(parentJob).launch(UnconfinedTestDispatcher(testScheduler)) {
                provider.observe<OpenFeatureEvents.ProviderReady>()
                    .take(1)
                    .collect {
                        emitted += 1
                    }
            }
        }

        provider.emitReady()
        parentJob.children.forEach { it.join() }
        Assert.assertTrue(emitted == 10)
    }

    @Test
    fun canceling_one_subscriber_does_not_cancel_others() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)
        val numberOfSubscribers = 10
        val parentJob = Job()
        var emitted = 0

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observe<OpenFeatureEvents.ProviderReady>()
                .take(1)
                .collect {}
        }

        repeat(numberOfSubscribers) {
            CoroutineScope(parentJob).launch(UnconfinedTestDispatcher(testScheduler)) {
                provider.observe<OpenFeatureEvents.ProviderReady>()
                    .take(1)
                    .collect {
                        emitted += 1
                    }
            }
        }
        job.cancel()
        provider.emitReady()
        parentJob.children.forEach { it.join() }
        Assert.assertTrue(emitted == 10)
    }

    @Test
    fun the_provider_status_stream_works() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)
        var isProviderReady = false

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observe<OpenFeatureEvents.ProviderReady>()
                .take(1)
                .collect {
                    isProviderReady = true
                }
        }

        provider.emitReady()
        job.join()
        Assert.assertTrue(isProviderReady)
    }

    @Test
    @kotlinx.coroutines.FlowPreview
    fun the_provider_status_stream_not_emitting_without_event_published() = runTest {
        var isProviderReady = false
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observe<OpenFeatureEvents.ProviderReady>()
                .timeout(10L.milliseconds)
                .collect {
                    isProviderReady = true
                }
        }

        job.join()
        Assert.assertTrue(!isProviderReady)
    }

    @Test
    fun the_provider_status_stream_is_replays_current_status() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)
        provider.emitReady()
        var isProviderReady = false

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.observeProviderReady()
                .take(1)
                .collect {
                    isProviderReady = true
                }
        }

        job.join()
        Assert.assertTrue(isProviderReady)
    }

    @Test
    fun the_provider_becomes_stale() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)
        var isProviderStale = false

        val job = backgroundScope.launch(dispatcher) {
            provider.observe<OpenFeatureEvents.ProviderStale>()
                .take(1)
                .collect {
                    isProviderStale = true
                }
        }

        provider.emitReady()
        provider.emitStale()
        job.join()
        Assert.assertTrue(isProviderStale)
    }

    @Test
    fun accessing_status_from_provider_works() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(dispatcher)
        val provider = TestFeatureProvider(eventHandler)

        Assert.assertEquals(OpenFeatureEvents.ProviderNotReady, provider.getProviderStatus())

        provider.emitReady()

        Assert.assertEquals(OpenFeatureEvents.ProviderReady, provider.getProviderStatus())

        provider.emitStale()

        Assert.assertEquals(OpenFeatureEvents.ProviderStale, provider.getProviderStatus())

        val illegalStateException = IllegalStateException("test")
        provider.emitError(illegalStateException)

        Assert.assertEquals(
            OpenFeatureEvents.ProviderError(illegalStateException),
            provider.getProviderStatus()
        )

        provider.shutdown()

        Assert.assertEquals(OpenFeatureEvents.ProviderNotReady, provider.getProviderStatus())
    }
}