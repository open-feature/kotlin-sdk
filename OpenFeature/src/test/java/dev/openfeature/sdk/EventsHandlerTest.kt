package dev.openfeature.sdk

import dev.openfeature.sdk.async.observeProviderReady
import dev.openfeature.sdk.async.toAsync
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
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
import org.mockito.Mockito.`when`
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventsHandlerTest {

    @Test
    fun observing_event_observer_works() = runTest {
        val eventObserver = EventHandler.eventsObserver()
        val eventPublisher = EventHandler.eventsPublisher()
        var emitted = false

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            eventObserver.observe<OpenFeatureEvents.ProviderReady>()
                .take(1)
                .collect {
                    emitted = true
                }
        }

        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        job.join()
        Assert.assertTrue(emitted)
    }

    @Test
    fun multiple_subscribers_works() = runTest {
        val eventObserver = EventHandler.eventsObserver()
        val eventPublisher = EventHandler.eventsPublisher()
        val numberOfSubscribers = 10
        val parentJob = Job()
        var emitted = 0

        repeat(numberOfSubscribers) {
            CoroutineScope(parentJob).launch(UnconfinedTestDispatcher(testScheduler)) {
                eventObserver.observe<OpenFeatureEvents.ProviderReady>()
                    .take(1)
                    .collect {
                        emitted += 1
                    }
            }
        }

        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        parentJob.children.forEach { it.join() }
        Assert.assertTrue(emitted == 10)
    }

    @Test
    fun canceling_one_subscriber_does_not_cancel_others() = runTest {
        val eventObserver = EventHandler.eventsObserver()
        val eventPublisher = EventHandler.eventsPublisher()
        val numberOfSubscribers = 10
        val parentJob = Job()
        var emitted = 0

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            eventObserver.observe<OpenFeatureEvents.ProviderReady>()
                .take(1)
                .collect {}
        }

        repeat(numberOfSubscribers) {
            CoroutineScope(parentJob).launch(UnconfinedTestDispatcher(testScheduler)) {
                eventObserver.observe<OpenFeatureEvents.ProviderReady>()
                    .take(1)
                    .collect {
                        emitted += 1
                    }
            }
        }
        job.cancel()
        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        parentJob.children.forEach { it.join() }
        Assert.assertTrue(emitted == 10)
    }

    @Test
    fun the_provider_status_stream_works() = runTest {
        val eventPublisher = EventHandler.eventsPublisher()
        var isProviderReady = false

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            EventHandler.eventsObserver()
                .observe<OpenFeatureEvents.ProviderReady>()
                .take(1)
                .collect {
                    isProviderReady = true
                }
        }

        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        job.join()
        Assert.assertTrue(isProviderReady)
    }

    @Test
    fun the_provider_status_stream_not_emitting_without_event_published() = runTest {
        var isProviderReady = false

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            EventHandler.eventsObserver()
                .observe<OpenFeatureEvents.ProviderReady>()
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
        val eventPublisher = EventHandler.eventsPublisher()
        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        var isProviderReady = false

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeProviderReady()
                .take(1)
                .collect {
                    isProviderReady = true
                }
        }

        job.join()
        Assert.assertTrue(isProviderReady)
    }

    @Test
    fun observe_string_value_from_client_works() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventPublisher = EventHandler.eventsPublisher(testDispatcher)
        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        val key = "mykey"
        val default = "default"
        val resultTexts = mutableListOf<String>()

        val mockOpenFeatureClient = mock<OpenFeatureClient> {
            on { getStringValue(key, default) } doReturn "text1"
        }

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(testDispatcher) {
            mockOpenFeatureClient.toAsync()
                .observeStringValue(key, default)
                .take(2)
                .collect {
                    resultTexts.add(it)
                }
        }

        `when`(mockOpenFeatureClient.getStringValue(key, default))
            .thenReturn("text2")

        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        job.join()
        Assert.assertEquals(listOf("text1", "text2"), resultTexts)
    }

    @Test
    fun observe_string_value_from_client_waits_until_provider_ready() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventPublisher = EventHandler.eventsPublisher(testDispatcher)
        val key = "mykey"
        val default = "default"
        val resultTexts = mutableListOf<String>()

        val mockOpenFeatureClient = mock<OpenFeatureClient> {
            on { getStringValue(key, default) } doReturn "text1"
        }

        // observing the provider status after the provider ready event is published
        val job = backgroundScope.launch(testDispatcher) {
            mockOpenFeatureClient.toAsync()
                .observeStringValue(key, default)
                .take(1)
                .collect {
                    resultTexts.add(it)
                }
        }

        eventPublisher.publish(OpenFeatureEvents.ProviderReady)
        job.join()
        Assert.assertEquals(listOf("text1"), resultTexts)
    }
}