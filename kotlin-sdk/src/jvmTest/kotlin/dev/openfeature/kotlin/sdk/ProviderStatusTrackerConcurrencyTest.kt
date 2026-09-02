package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    @Test
    fun racingReconciliationsNeverLeaveTheTrackerReconciling() = runBlocking {
        repeat(ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            // Two invocations on different threads. The mark, the registration and the reconciling
            // report have to be one step, or one of them mistakes the other's report for its own and
            // neither ends up reporting the outcome.
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
            val collector = launch(Dispatchers.Default) {
                tracker.observe().collect {
                    observed.add(it)
                    replayed.complete(Unit)
                }
            }
            // Sent only once the replay has arrived, so the handover point is unambiguous.
            withTimeout(5_000) { replayed.await() }
            launch(Dispatchers.Default) {
                tracker.send(OpenFeatureProviderEvents.ProviderStale())
                tracker.send(OpenFeatureProviderEvents.ProviderReconciling())
            }.join()

            withTimeout(5_000) { while (observed.size < 3) yield() }
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

    // The fence's third clause — an event carrying no status is never fenced out, because the replay
    // cannot stand in for it — is deliberately not tested here, and cannot be. It only decides
    // anything for an event buffered after the collector's slot was allocated but before the snapshot
    // was read, and that window is not observable from outside the class: an event sent a moment
    // earlier is correctly never delivered at all, so "the later event arrived but the earlier one did
    // not" has a legitimate reading as well as a buggy one. A test asserting the buggy reading has a
    // false-positive mode, which is what it did under load. The clause is covered by construction and
    // by the deterministic aStatelessEventIsDeliveredButNeverReplayed in ProviderStatusTrackerTests.

    private fun errorNumbered(number: Int) = OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(message = number.toString())
    )

    private fun OpenFeatureProviderEvents.number(): Int =
        requireNotNull(eventDetails?.message) { "event carried no number" }.toInt()
}