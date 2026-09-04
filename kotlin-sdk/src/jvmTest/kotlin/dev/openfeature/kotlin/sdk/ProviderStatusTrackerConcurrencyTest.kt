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
 * Racing subscribe against send, which only JVM and native can do — JS is single-threaded.
 *
 * A subscriber's slot is allocated before it reads the status to replay, so an event landing in
 * between is both folded into the replay and buffered for delivery. These assertions pin that this
 * never produces a duplicate or a reordering.
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

    @Test
    fun racingReconciliationsNeverLeaveTheTrackerReconciling() = runBlocking {
        repeat(ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            // The mark, the registration and the reconciling report have to be one step, or one
            // invocation mistakes the other's report for its own and neither reports the outcome.
            (1..2).map { launch(Dispatchers.Default) { tracker.reconciling { } } }.forEach { it.join() }

            assertEquals(
                OpenFeatureStatus.Ready,
                tracker.status,
                "iteration $iteration was left mid-reconciliation"
            )
        }
    }

    @Test
    fun theReplayHandsOffToTheLiveStreamWithoutLoss() = runBlocking {
        repeat(ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            val observed = mutableListOf<OpenFeatureProviderEvents>()
            val replayed = CompletableDeferred<Unit>()
            val handedOff = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                tracker.observe().collect {
                    observed.add(it)
                    replayed.complete(Unit)
                    if (observed.size == 3) handedOff.complete(Unit)
                }
            }
            // Sent only once the replay has arrived, so the handover point is unambiguous.
            withTimeout(5_000) { replayed.await() }
            launch(Dispatchers.Default) {
                tracker.send(OpenFeatureProviderEvents.ProviderStale())
                tracker.send(OpenFeatureProviderEvents.ProviderReconciling())
            }.join()

            // Signalled by the collector rather than polled: observed is the collector's alone until
            // it has joined.
            withTimeout(5_000) { handedOff.await() }
            collector.cancel()
            collector.join()

            assertEquals(
                listOf(
                    OpenFeatureProviderEvents.ProviderReady::class,
                    OpenFeatureProviderEvents.ProviderStale::class,
                    OpenFeatureProviderEvents.ProviderReconciling::class
                ),
                observed.take(3).map { it::class },
                "iteration $iteration"
            )
        }
    }

    // The fence's third clause — an event carrying no status is never fenced out — has no sound race
    // test: its window is not observable from outside the class, and an event sent a moment earlier is
    // correctly never delivered, so the buggy and the legitimate reading are indistinguishable. It is
    // covered by aStatelessEventIsDeliveredButNeverReplayed in ProviderStatusTrackerTests.

    private fun errorNumbered(number: Int) = OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(message = number.toString())
    )

    private fun OpenFeatureProviderEvents.number(): Int =
        requireNotNull(eventDetails?.message) { "event carried no number" }.toInt()
}