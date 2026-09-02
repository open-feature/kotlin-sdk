package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [ProviderStatusTracker]'s status transitions, its replay-on-subscribe contract, and the
 * reconciliation coalescing that requirements 5.3.4.2 and 5.3.4.3 describe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderStatusTrackerTests {

    private class Recording(private val events: MutableList<OpenFeatureProviderEvents>, private val job: Job) :
        List<OpenFeatureProviderEvents> by events {
        suspend fun stop() = job.cancelAndJoin()
    }

    /** Collects from [tracker], returning once the subscription is established. */
    private fun TestScope.record(tracker: ProviderStatusTracker): Recording {
        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { tracker.observe().collect { received.add(it) } }
        testScheduler.runCurrent()
        return Recording(received, job)
    }

    // MARK: status transitions

    @Test
    fun aFreshTrackerIsNotReady() {
        assertEquals(OpenFeatureStatus.NotReady, ProviderStatusTracker().status)
    }

    @Test
    fun aProviderErrorCarryingProviderFatalBecomesFatal() {
        val tracker = ProviderStatusTracker()
        tracker.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(errorCode = ErrorCode.PROVIDER_FATAL)
            )
        )
        assertIs<OpenFeatureStatus.Fatal>(tracker.status)
    }

    @Test
    fun aProviderErrorWithoutProviderFatalBecomesError() {
        val tracker = ProviderStatusTracker()
        tracker.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(message = "boom")
            )
        )
        val status = assertIs<OpenFeatureStatus.Error>(tracker.status)
        assertEquals("boom", status.error.message)
    }

    @Test
    fun contextChangedBecomesReady() {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderContextChanged())
        assertEquals(OpenFeatureStatus.Ready, tracker.status)
    }

    @Test
    fun configurationChangedLeavesTheStatusAlone() {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderStale())
        tracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        assertEquals(OpenFeatureStatus.Stale, tracker.status)
    }

    @Test
    fun resetReturnsTheTrackerToNotReady() {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())
        tracker.reset()
        assertEquals(OpenFeatureStatus.NotReady, tracker.status)
    }

    // MARK: replay on subscribe

    @Test
    fun nothingIsReplayedWhileNotReady() = runTest {
        val tracker = ProviderStatusTracker()
        val received = record(tracker)
        advanceUntilIdle()
        received.stop()

        assertEquals(emptyList(), received.map { it::class.simpleName })
    }

    @Test
    fun theCurrentStatusIsReplayedOnceToANewSubscriber() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderStale())

        val received = record(tracker)
        advanceUntilIdle()
        received.stop()

        assertEquals(listOf(OpenFeatureProviderEvents.ProviderStale::class), received.map { it::class })
    }

    @Test
    fun aFatalStatusIsReplayedAsAnErrorCarryingProviderFatal() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "unrecoverable",
                    errorCode = ErrorCode.PROVIDER_FATAL
                )
            )
        )

        val received = record(tracker)
        advanceUntilIdle()
        received.stop()

        val replayed = assertIs<OpenFeatureProviderEvents.ProviderError>(received.single())
        assertEquals(ErrorCode.PROVIDER_FATAL, replayed.eventDetails?.errorCode)
        assertEquals("unrecoverable", replayed.eventDetails?.message)
    }

    @Test
    fun theReplayIsFollowedByTheLiveStreamWithoutGapsOrDuplicates() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())

        val received = record(tracker)
        tracker.send(OpenFeatureProviderEvents.ProviderStale())
        tracker.send(OpenFeatureProviderEvents.ProviderReconciling())
        advanceUntilIdle()
        received.stop()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderReconciling::class
            ),
            received.map { it::class }
        )
    }

    @Test
    fun aStatelessEventIsDeliveredButNeverReplayed() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())
        // Sent before anyone subscribes: it carries no status, so there is nothing to replay it in.
        tracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())

        val received = record(tracker)
        tracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        advanceUntilIdle()
        received.stop()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderConfigurationChanged::class
            ),
            received.map { it::class }
        )
    }

    @Test
    fun subscribersReplayIndependently() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())
        val first = record(tracker)

        tracker.send(OpenFeatureProviderEvents.ProviderStale())
        advanceUntilIdle()
        val second = record(tracker)
        advanceUntilIdle()

        first.stop()
        second.stop()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderStale::class
            ),
            first.map { it::class }
        )
        assertEquals(listOf(OpenFeatureProviderEvents.ProviderStale::class), second.map { it::class })
    }

    @Test
    fun aCancelledSubscriberStopsReceivingEvents() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())

        val received = record(tracker)
        advanceUntilIdle()
        received.stop()

        tracker.send(OpenFeatureProviderEvents.ProviderStale())
        advanceUntilIdle()

        assertEquals(listOf(OpenFeatureProviderEvents.ProviderReady::class), received.map { it::class })
    }

    @Test
    fun theStatusIsCurrentByTheTimeASubscriberSeesTheEvent() = runTest {
        val tracker = ProviderStatusTracker()
        val seen = mutableListOf<OpenFeatureStatus>()
        val job = launch { tracker.observe().collect { seen.add(tracker.status) } }
        testScheduler.runCurrent()

        tracker.send(OpenFeatureProviderEvents.ProviderStale())
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(listOf<OpenFeatureStatus>(OpenFeatureStatus.Stale), seen)
    }

    // MARK: reconciliation

    @Test
    fun aReconciliationReportsReconcilingThenContextChanged() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())

        val received = record(tracker)
        tracker.reconciling { }
        advanceUntilIdle()
        received.stop()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            received.map { it::class }
        )
        assertEquals(OpenFeatureStatus.Ready, tracker.status)
    }

    @Test
    fun aFailedReconciliationReportsAnErrorAndRethrows() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())

        val received = record(tracker)
        var thrown: Throwable? = null
        try {
            tracker.reconciling { throw OpenFeatureError.GeneralError("reconcile failed") }
        } catch (e: Throwable) {
            thrown = e
        }
        advanceUntilIdle()
        received.stop()

        assertIs<OpenFeatureError.GeneralError>(thrown)
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderError::class
            ),
            received.map { it::class }
        )
        val status = assertIs<OpenFeatureStatus.Error>(tracker.status)
        assertEquals("reconcile failed", status.error.message)
    }

    @Test
    fun aBlockThatReportsItsOwnFailureIsNotReportedForTwice() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())

        val received = record(tracker)
        try {
            tracker.reconciling {
                tracker.send(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(message = "reported by the provider")
                    )
                )
                throw OpenFeatureError.GeneralError("thrown as well")
            }
        } catch (_: Throwable) {
            // Reported through the event stream; the throw is the provider's own to see.
        }
        advanceUntilIdle()
        received.stop()

        val errors = received.filterIsInstance<OpenFeatureProviderEvents.ProviderError>()
        assertEquals(1, errors.size, "collected ${received.map { it::class.simpleName }}")
        assertEquals("reported by the provider", errors.single().eventDetails?.message)
    }

    @Test
    fun overlappingReconciliationsReportReconcilingOnceAndTheLastOutcome() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val received = record(tracker)
        val first = launch {
            tracker.reconciling {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val second = launch { tracker.reconciling { } }
        runCurrent()

        // The second finished, but the first is still in flight, so no outcome is reported yet.
        assertEquals(OpenFeatureStatus.Reconciling, tracker.status)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        advanceUntilIdle()
        received.stop()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            received.map { it::class }
        )
    }

    @Test
    fun aNotReadyProviderDoesNotReportReconciling() = runTest {
        val tracker = ProviderStatusTracker()

        val received = record(tracker)
        tracker.reconciling { }
        advanceUntilIdle()
        received.stop()

        // Nothing to reconcile from, so nothing is announced; the outcome still applies.
        assertEquals(
            listOf(OpenFeatureProviderEvents.ProviderContextChanged::class),
            received.map { it::class }
        )
        assertEquals(OpenFeatureStatus.Ready, tracker.status)
    }

    @Test
    fun aCancelledReconciliationOnANotReadyProviderLeavesItNotReady() = runTest {
        val tracker = ProviderStatusTracker()

        val job = launch { tracker.reconciling { awaitCancellation() } }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()

        // Reconciling was never announced, so there is nothing to restore and nothing to get stuck on.
        assertEquals(OpenFeatureStatus.NotReady, tracker.status)
    }

    @Test
    fun aReconciliationSupersededByResetCannotReportOverTheNextOne() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderReady())
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val releaseFresh = CompletableDeferred<Unit>()

        val stale = launch {
            tracker.reconciling {
                staleStarted.complete(Unit)
                releaseStale.await()
            }
        }
        staleStarted.await()
        tracker.reset()
        assertEquals(OpenFeatureStatus.NotReady, tracker.status)

        // A reconciliation belonging to the generation that replaced the superseded one.
        tracker.send(OpenFeatureProviderEvents.ProviderReady())
        val fresh = launch {
            runCatching {
                tracker.reconciling {
                    freshStarted.complete(Unit)
                    releaseFresh.await()
                    throw OpenFeatureError.GeneralError("fresh reconciliation failed")
                }
            }
        }
        freshStarted.await()

        // The superseded invocation terminates while the fresh one is still in flight. Counting it
        // out would make it look like the last in flight and hand it the fresh one's outcome.
        releaseStale.complete(Unit)
        stale.join()
        releaseFresh.complete(Unit)
        fresh.join()
        advanceUntilIdle()

        val status = assertIs<OpenFeatureStatus.Error>(tracker.status)
        assertEquals("fresh reconciliation failed", status.error.message)
    }

    @Test
    fun aReconciliationCancelledThroughoutRestoresThePrecedingStatus() = runTest {
        val tracker = ProviderStatusTracker()
        tracker.send(OpenFeatureProviderEvents.ProviderStale())

        val received = record(tracker)
        val job = launch { tracker.reconciling { awaitCancellation() } }
        runCurrent()
        assertEquals(OpenFeatureStatus.Reconciling, tracker.status)

        job.cancelAndJoin()
        advanceUntilIdle()
        received.stop()

        assertEquals(OpenFeatureStatus.Stale, tracker.status)
        assertTrue(
            received.map { it::class }.containsAll(
                listOf(
                    OpenFeatureProviderEvents.ProviderReconciling::class,
                    OpenFeatureProviderEvents.ProviderStale::class
                )
            ),
            "collected ${received.map { it::class.simpleName }}"
        )
    }
}