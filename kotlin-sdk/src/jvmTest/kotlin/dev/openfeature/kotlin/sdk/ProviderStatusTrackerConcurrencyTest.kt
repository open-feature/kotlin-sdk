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

    @Test
    fun aStatelessEventIsNotFencedOutFromUnderALaterOne() = runBlocking {
        repeat(ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            val observed = mutableListOf<OpenFeatureProviderEvents>()
            val collector = launch(Dispatchers.Default) {
                tracker.observe().collect { observed.add(it) }
            }
            // Racing the subscription: the configuration change is sent first, so if the later stale
            // report got through then the configuration change must have too. Fencing an event that
            // carries no status would drop it here, because the replay cannot stand in for it.
            launch(Dispatchers.Default) {
                tracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
                tracker.send(OpenFeatureProviderEvents.ProviderStale())
            }.join()

            withTimeout(5_000) { while (tracker.status != OpenFeatureStatus.Stale) yield() }
            collector.cancel()
            collector.join()

            val snapshot = observed.toList()
            val sawStaleLive = snapshot.drop(1).any { it is OpenFeatureProviderEvents.ProviderStale }
            if (sawStaleLive) {
                assertTrue(
                    snapshot.any { it is OpenFeatureProviderEvents.ProviderConfigurationChanged },
                    "iteration $iteration delivered the later event but dropped the earlier one: " +
                        snapshot.map { it::class.simpleName }
                )
            }
        }
    }

    private fun errorNumbered(number: Int) = OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(message = number.toString())
    )

    private fun OpenFeatureProviderEvents.number(): Int =
        requireNotNull(eventDetails?.message) { "event carried no number" }.toInt()
}