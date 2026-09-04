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

/** Stamped on a replayed event, below every real one so it is never fenced. */
private const val REPLAY_SEQUENCE = Long.MIN_VALUE

/**
 * Processes the [OpenFeatureProviderEvents] a provider emits, updates [status] accordingly, and
 * republishes the events to subscribers.
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
 * A new subscriber receives one synthetic event reflecting the current status, so attaching does not
 * race the first live event. Nothing is replayed while the provider is [OpenFeatureStatus.NotReady],
 * which has no corresponding event type.
 *
 * A provider delegates [FeatureProvider.status] and [FeatureProvider.observe] to this. Do not
 * collect [observe] on [kotlinx.coroutines.Dispatchers.Unconfined], and do not call [send] from
 * inside a collector: delivery would then run inline under the lock that orders events.
 */
class ProviderStatusTracker {
    private val lock = SynchronizedObject()

    private var currentStatus: OpenFeatureStatus = OpenFeatureStatus.NotReady
    private var sequence: Long = 0
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
     * Publishes [event] and applies its status. The caller must hold [lock]: a reconciliation
     * starting between the decision and the report would capture a status that is already replaced.
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
        var fence = 0L
        emitAll(
            events
                .onSubscription {
                    val replayed = synchronized(lock) {
                        fence = sequence
                        currentStatus
                    }
                    replayed.toCurrentStateEvent()?.let { emit(Emission(REPLAY_SEQUENCE, it)) }
                }
                .filter {
                    // A status-carrying event from before the snapshot is already in the replay; one
                    // carrying no status is not, so it is never fenced.
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
     * Where every invocation was cancelled, the status preceding reconciliation is reported again.
     * Where [block] reported a status of its own, that report stands.
     *
     * A reconciliation that begins while the provider is [OpenFeatureStatus.NotReady] reports
     * nothing at all: readiness is [FeatureProvider.initialize]'s to report, and there is no earlier
     * status to restore. A provider that does become usable during [block] can still say so itself.
     */
    suspend fun reconciling(block: suspend () -> Unit) {
        // One critical section: an overlapping invocation would otherwise read its mark before this
        // send bumps the sequence, and mistake that send for a report of its own.
        val registration = synchronized(lock) {
            val registration = reconciliations.begin(currentStatus)
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
        reconciliations.reset()
    }

    /**
     * Collapses overlapping reconciliations into one reported transition. The outcome belongs to the
     * reconciliation rather than to the invocation that produced it.
     */
    private class Reconciliations {
        private var generation = 0L
        private var active = 0
        private var restore: OpenFeatureStatus? = null
        private var terminal: OpenFeatureProviderEvents? = null
        private var reportedByBlock = false

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
         * An invocation from a superseded generation reports nothing and disturbs nothing.
         */
        fun end(
            registration: Registration,
            outcome: OpenFeatureProviderEvents?,
            blockReported: Boolean
        ): OpenFeatureProviderEvents? {
            if (registration.generation != generation) return null
            if (blockReported) reportedByBlock = true
            if (outcome != null) terminal = outcome
            if (--active > 0) return null

            // Decided only after the counter is decremented: returning earlier would strand it above
            // zero and no later reconciliation would resolve.
            return when {
                reportedByBlock -> null
                restore == OpenFeatureStatus.NotReady -> null
                else -> terminal ?: restore?.toCurrentStateEvent()
            }
        }

        fun reset() {
            generation++
            active = 0
        }
    }
}

private fun Throwable.toProviderErrorEvent() = OpenFeatureProviderEvents.ProviderError(
    OpenFeatureProviderEvents.EventDetails(
        message = message ?: "Context reconciliation failed",
        errorCode = (this as? OpenFeatureError)?.errorCode()
    )
)