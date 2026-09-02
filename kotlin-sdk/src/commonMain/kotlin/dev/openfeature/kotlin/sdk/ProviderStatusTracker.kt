package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toCurrentStateEvent
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

private const val EVENT_BUFFER_CAPACITY = 64

/** Sequence stamped on a replayed event, below every real one so it is never fenced. */
private const val REPLAY_SEQUENCE = Long.MIN_VALUE

/**
 * Processes the [OpenFeatureProviderEvents] a provider emits, updates [status] accordingly, and
 * republishes the events to subscribers.
 *
 * ## Event to status mapping
 *
 * | Event                                        | Resulting status |
 * |----------------------------------------------|------------------|
 * | `ProviderReady`                              | `Ready`          |
 * | `ProviderError`                              | `Error`          |
 * | `ProviderError` with `errorCode` `PROVIDER_FATAL` | `Fatal`     |
 * | `ProviderStale`                              | `Stale`          |
 * | `ProviderReconciling`                        | `Reconciling`    |
 * | `ProviderContextChanged`                     | `Ready`          |
 * | `ProviderConfigurationChanged`               | *(no change)*    |
 *
 * ## Status replay
 *
 * A new subscriber receives one synthetic event reflecting the current status, so attaching does not
 * race the first live event. Nothing is replayed while the provider is [OpenFeatureStatus.NotReady],
 * which has no corresponding event type.
 *
 * ## Recommended usage
 *
 * ```kotlin
 * class MyProvider : FeatureProvider {
 *     private val statusTracker = ProviderStatusTracker()
 *
 *     override val status: OpenFeatureStatus get() = statusTracker.status
 *     override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()
 *
 *     override suspend fun initialize(initialContext: EvaluationContext?) {
 *         connect(initialContext)
 *         statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
 *     }
 *
 *     override suspend fun onContextSet(
 *         oldContext: EvaluationContext?,
 *         newContext: EvaluationContext
 *     ) = statusTracker.reconciling { refresh(newContext) }
 *
 *     override fun shutdown() = statusTracker.reset()
 * }
 * ```
 *
 * Do not collect [observe] on [kotlinx.coroutines.Dispatchers.Unconfined], and do not call [send]
 * from inside a collector: delivery would then run inline under the lock that orders events, and the
 * order subscribers see would no longer match the order they were sent in.
 */
class ProviderStatusTracker {
    // Orders event handling in send(), and provides the snapshot a new subscriber fences against, so
    // that no event is missed or duplicated between reading the status and installing the collector.
    private val lock = SynchronizedObject()

    private var currentStatus: OpenFeatureStatus = OpenFeatureStatus.NotReady
    private var sequence: Long = 0

    /** Sequence of the last published event that carried a status, for [reconciling]'s mark. */
    private var statusSequence: Long = 0

    private val reconciliations = Reconciliations()

    private val events = MutableSharedFlow<Emission>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private class Emission(val sequence: Long, val event: OpenFeatureProviderEvents)

    /** The status implied by the most recent event, safe to read from any thread. */
    val status: OpenFeatureStatus get() = synchronized(lock) { currentStatus }

    /** Reports [event], updating [status] and publishing it to [observe]'s subscribers. */
    fun send(event: OpenFeatureProviderEvents) = synchronized(lock) { record(event) }

    /**
     * Publishes [event] and applies its status. The caller must hold [lock]: deciding what to report
     * and reporting it have to be one step, or a reconciliation starting in between reads a status
     * that is about to be replaced and captures it as the one to restore.
     */
    private fun record(event: OpenFeatureProviderEvents) {
        val status = event.toOpenFeatureStatus()
        sequence++
        if (status != null) {
            currentStatus = status
            statusSequence = sequence
        }
        events.tryEmit(Emission(sequence, event))
    }

    /** Stream to return from [FeatureProvider.observe]. */
    fun observe(): Flow<OpenFeatureProviderEvents> = flow {
        // Per collection, so each subscriber fences against its own snapshot.
        var fence = 0L
        emitAll(
            events
                .onSubscription {
                    // Runs once this collector is registered, so nothing sent from here on is missed.
                    val replayed = synchronized(lock) {
                        fence = sequence
                        currentStatus
                    }
                    replayed.toCurrentStateEvent()?.let { emit(Emission(REPLAY_SEQUENCE, it)) }
                }
                .filter {
                    // A status-carrying event from before the snapshot is already folded into the
                    // replay. An event carrying no status is not, so it is never fenced.
                    it.sequence == REPLAY_SEQUENCE ||
                        it.sequence > fence ||
                        it.event.toOpenFeatureStatus() == null
                }
                .map { it.event }
        )
    }

    /**
     * Runs [block] as a context reconciliation, reporting the transitions around it.
     *
     * Sends [OpenFeatureProviderEvents.ProviderReconciling] on entry, then
     * [OpenFeatureProviderEvents.ProviderContextChanged] on success or
     * [OpenFeatureProviderEvents.ProviderError] on failure, and rethrows whatever [block] threw.
     *
     * Overlapping invocations are collapsed: reconciliation is reported once, and the outcome
     * reported is that of the last invocation to terminate, as requirements 5.3.4.2 and 5.3.4.3 ask.
     * Where every invocation was cancelled, the status preceding reconciliation is reported again
     * rather than leaving the provider reconciling forever. Where [block] reported a status of its
     * own, that report stands and no outcome is synthesised over it.
     */
    suspend fun reconciling(block: suspend () -> Unit) {
        // One critical section: registering, reporting that reconciliation began, and taking the mark
        // have to be atomic, or an overlapping invocation reads its mark before this send bumps the
        // sequence and mistakes that send for a report of its own.
        val registration = synchronized(lock) {
            val registration = reconciliations.begin(currentStatus)
            // Nothing to reconcile from while not ready, and nothing to restore to either, so the
            // transition is not reported. Requirement 5.3.4.1 asks that RECONCILING handlers run while
            // onContextSet executes, not that a not-ready provider announce one.
            if (registration.first && currentStatus != OpenFeatureStatus.NotReady) {
                record(OpenFeatureProviderEvents.ProviderReconciling())
            }
            registration.copy(mark = statusSequence)
        }

        var outcome: OpenFeatureProviderEvents? = null
        try {
            block()
            outcome = OpenFeatureProviderEvents.ProviderContextChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            outcome = e.toProviderErrorEvent()
            throw e
        } finally {
            // A cancelled invocation still owes the reconciliation an outcome or a restoration.
            withContext(NonCancellable) {
                synchronized(lock) {
                    val reportedByBlock = statusSequence > registration.mark
                    reconciliations.end(registration, outcome, reportedByBlock)?.let { record(it) }
                }
            }
        }
    }

    /**
     * Returns the tracker to [OpenFeatureStatus.NotReady].
     *
     * Call this from [FeatureProvider.shutdown] where the provider can be registered again, so a
     * reused instance does not report the status it held before it was shut down.
     */
    fun reset() = synchronized(lock) {
        currentStatus = OpenFeatureStatus.NotReady
        sequence++
        statusSequence = sequence
        reconciliations.reset()
    }

    /**
     * Collapses overlapping reconciliations into one reported transition.
     *
     * The outcome belongs to the reconciliation rather than to the invocation that produced it: an
     * invocation that terminated normally still owns it when a later, overlapping one is cancelled.
     */
    private class Reconciliations {
        private var generation = 0L
        private var active = 0
        private var restore: OpenFeatureStatus? = null
        private var terminal: OpenFeatureProviderEvents? = null
        private var reportedByBlock = false

        /** One invocation's place in a reconciliation, so a stale one cannot disturb a newer one. */
        data class Registration(val generation: Long, val first: Boolean, val mark: Long = 0)

        /** Registers an invocation, reporting whether it is the one that opens the reconciliation. */
        fun begin(restoreTo: OpenFeatureStatus): Registration {
            if (active == 0) {
                restore = restoreTo
                terminal = null
                reportedByBlock = false
            }
            return Registration(generation, ++active == 1)
        }

        /**
         * Registers an invocation's outcome, returning what to report if it was the last in flight.
         *
         * An invocation from a superseded generation reports nothing and disturbs nothing: counting it
         * out would let it clear the state of the reconciliation that replaced it.
         */
        fun end(
            registration: Registration,
            outcome: OpenFeatureProviderEvents?,
            reportedByBlock: Boolean
        ): OpenFeatureProviderEvents? {
            if (registration.generation != generation) return null
            if (reportedByBlock) this.reportedByBlock = true
            if (outcome != null) terminal = outcome
            if (--active > 0) return null

            val reported = this.reportedByBlock
            val result = if (reported) null else terminal ?: restore?.toCurrentStateEvent()
            restore = null
            terminal = null
            this.reportedByBlock = false
            return result
        }

        fun reset() {
            generation++
            active = 0
            restore = null
            terminal = null
            reportedByBlock = false
        }
    }
}

private fun Throwable.toProviderErrorEvent() = OpenFeatureProviderEvents.ProviderError(
    OpenFeatureProviderEvents.EventDetails(
        message = message ?: "Context reconciliation failed",
        errorCode = (this as? OpenFeatureError)?.errorCode()
    )
)