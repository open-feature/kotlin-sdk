package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ITERATIONS = 500
private const val EVENTS_PER_ITERATION = 8

/**
 * Racing subscribe against send, which only JVM and native can do — JS is single-threaded, and the
 * common tests can only drive the deterministic ordering.
 *
 * The sequence fence in [ProviderStatusTracker.observe] exists because a subscriber's slot is
 * allocated before it reads the status to replay, so an event landing in between is both folded into
 * the replayed status and buffered for delivery. What that must never produce is a duplicate or a
 * reordering, which is what these assertions pin.
 */
class ProviderStatusTrackerConcurrencyTest {

    @Test
    fun subscribingWhileEventsAreSentNeverDuplicatesOrReordersThem() = runBlocking {
        repeat(ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(errorNumbered(0))

            val observed = mutableListOf<Int>()
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                tracker.observe().collect { event ->
                    subscribed.complete(Unit)
                    observed.add(event.number())
                }
            }

            // Deliberately not waiting for the subscription: the point is to land inside the window.
            launch(Dispatchers.Default) {
                for (number in 1..EVENTS_PER_ITERATION) tracker.send(errorNumbered(number))
            }.join()

            withTimeout(5_000) { subscribed.await() }
            collector.cancel()
            collector.join()

            // Strict monotonicity forbids both a replay that repeats a live event and any reordering.
            val snapshot = observed.toList()
            assertTrue(
                snapshot.zipWithNext().all { (previous, next) -> previous < next },
                "iteration $iteration observed a duplicate or out-of-order sequence: $snapshot"
            )
        }
    }

    @Test
    fun statusIsConsistentWithTheLastEventSentUnderConcurrentSenders() = runBlocking {
        repeat(ITERATIONS) {
            val tracker = ProviderStatusTracker()
            val senders = (1..4).map {
                launch(Dispatchers.Default) {
                    repeat(EVENTS_PER_ITERATION) { tracker.send(OpenFeatureProviderEvents.ProviderStale()) }
                }
            }
            senders.forEach { it.join() }
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            assertEquals(OpenFeatureStatus.Ready, tracker.status)
        }
    }

    private fun errorNumbered(number: Int) = OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(message = number.toString())
    )

    private fun OpenFeatureProviderEvents.number(): Int =
        requireNotNull(eventDetails?.message) { "event carried no number" }.toInt()
}