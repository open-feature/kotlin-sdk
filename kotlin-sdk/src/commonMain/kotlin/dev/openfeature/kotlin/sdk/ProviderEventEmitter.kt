package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val DEFAULT_EXTRA_BUFFER_CAPACITY = 32

/**
 * Event stream for a [StateManagingProvider] to report its lifecycle through.
 *
 * A provider implementing [StateManagingProvider] owes the SDK an event for every status transition, and
 * owes it before the corresponding lifecycle method terminates. Doing that by hand means holding a flow,
 * choosing a replay policy that does not lose an event emitted before the SDK subscribes, keeping
 * emissions ordered under concurrency, and coalescing reconciliations that overlap. This does those.
 *
 * The provider owns its events; the SDK owns the status it derives from them. There is deliberately no
 * status here to read or set.
 *
 * ```kotlin
 * class MyProvider : StateManagingProvider {
 *     private val emitter = ProviderEventEmitter()
 *
 *     override fun observe(): Flow<OpenFeatureProviderEvents> = emitter.observe()
 *
 *     override suspend fun initialize(initialContext: EvaluationContext?) =
 *         emitter.initializing { connect(initialContext) }
 *
 *     override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) =
 *         emitter.reconciling { refresh(newContext) }
 * }
 * ```
 *
 * @param extraBufferCapacity events buffered beyond the replayed one before the oldest is dropped
 */
class ProviderEventEmitter(extraBufferCapacity: Int = DEFAULT_EXTRA_BUFFER_CAPACITY) {
    private val lock = SynchronizedObject()

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(
        replay = 1,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var activeReconciliations = 0
    private var lastLifecycleEvent: OpenFeatureProviderEvents? = null
    private var reconciliationRestoreEvent: OpenFeatureProviderEvents? = null

    /**
     * Stream to return from [StateManagingProvider.observe].
     *
     * Replays the most recent event, so a readiness report emitted while the SDK is still subscribing is
     * not lost.
     */
    fun observe(): Flow<OpenFeatureProviderEvents> = events.asSharedFlow()

    /**
     * Reports [event], ordered against every other report from this emitter.
     *
     * Use this for transitions that arise on their own — going stale, recovering, a configuration change
     * — rather than from a lifecycle method.
     */
    fun emit(event: OpenFeatureProviderEvents) {
        synchronized(lock) {
            if (event.toOpenFeatureStatus() != null) lastLifecycleEvent = event
            events.tryEmit(event)
        }
    }

    /**
     * Runs [block] as the provider's initialization, reporting the outcome before returning or throwing.
     *
     * Reports [OpenFeatureProviderEvents.ProviderReady] on success and
     * [OpenFeatureProviderEvents.ProviderError] on failure, then rethrows. Where readiness is signalled
     * asynchronously by an underlying client, keep [block] suspended until that signal arrives rather than
     * returning early: the SDK treats the return as a synchronization point, not as the report.
     */
    suspend fun initializing(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(providerError(e, "Provider failed to initialize"))
            throw e
        }
        emit(OpenFeatureProviderEvents.ProviderReady())
    }

    /**
     * Runs [block] as a context reconciliation, reporting the surrounding transitions.
     *
     * Reports [OpenFeatureProviderEvents.ProviderReconciling] when reconciliation begins and
     * [OpenFeatureProviderEvents.ProviderContextChanged] — or
     * [OpenFeatureProviderEvents.ProviderError] — once it ends, then rethrows any failure.
     *
     * Invocations that overlap are coalesced: only the first reports that reconciliation began, and only
     * the last to finish reports the outcome, so an intermediate reconciliation does not surface as a
     * spurious update. Where every invocation was cancelled, the transition reported before
     * reconciliation began is reported again, rather than leaving the SDK reconciling indefinitely.
     */
    suspend fun reconciling(block: suspend () -> Unit) {
        val first = synchronized(lock) {
            if (activeReconciliations == 0) reconciliationRestoreEvent = lastLifecycleEvent
            ++activeReconciliations == 1
        }
        if (first) emit(OpenFeatureProviderEvents.ProviderReconciling())

        var outcome: OpenFeatureProviderEvents? = null
        try {
            block()
            outcome = OpenFeatureProviderEvents.ProviderContextChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            outcome = providerError(e, "Context reconciliation failed")
            throw e
        } finally {
            val restore = synchronized(lock) {
                if (--activeReconciliations > 0) {
                    null
                } else {
                    (outcome ?: reconciliationRestoreEvent).also { reconciliationRestoreEvent = null }
                }
            }
            restore?.let { emit(it) }
        }
    }

    private fun providerError(cause: Throwable, fallbackMessage: String) =
        OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = cause.message ?: fallbackMessage,
                errorCode = (cause as? OpenFeatureError)?.errorCode()
            )
        )
}