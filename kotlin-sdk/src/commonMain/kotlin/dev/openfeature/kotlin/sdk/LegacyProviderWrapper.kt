package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val WRAPPER_EVENT_BUFFER_CAPACITY = 64

/**
 * Presents a provider that does not emit its own lifecycle events as one that does, by synthesising
 * those events around the lifecycle methods.
 *
 * This is the deprecated compatibility path. Everything the SDK used to do for every provider —
 * inferring readiness from `initialize` returning, and coalescing overlapping context reconciliations —
 * lives here and nowhere else, so that removing support for it later is a matter of deleting this class
 * and the branch that installs it.
 *
 * Events the wrapped provider emits itself are passed through unchanged. Where a wrapped provider emits
 * a lifecycle event of its own, the wrapper suppresses the event it would otherwise have synthesised, so
 * a partially migrated provider does not produce the same transition twice.
 */
internal class LegacyProviderWrapper(
    val inner: FeatureProvider,
    private val eventDispatcher: CoroutineDispatcher
) : StateManagingProvider,
    FeatureProvider by inner {

    private val lock = SynchronizedObject()

    /**
     * Replays its most recent event because the SDK subscribes concurrently with `initialize` being
     * called: without a replay the synthesised readiness event can be emitted before the SDK is
     * listening and be lost. Only the SDK consumes this; application subscribers read the SDK's own
     * stream, which does not replay.
     */
    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(
        replay = 1,
        extraBufferCapacity = WRAPPER_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Single queue for everything this wrapper publishes, so that events forwarded from [inner] and
     * events synthesised here keep a total order instead of racing each other into [events].
     */
    private val outgoing = Channel<Outgoing>(Channel.UNLIMITED)

    private var scope: CoroutineScope? = null
    private var relayJob: Job? = null
    private var publishJob: Job? = null
    private var initialized = false

    private sealed interface Outgoing {
        /** An event from [inner], forwarded unchanged. */
        class Forwarded(val event: OpenFeatureProviderEvents) : Outgoing

        /**
         * An event the wrapper stands in for, dropped if [inner] has reported a lifecycle event of its
         * own since [innerReportsAtRequest]. Evaluated when this item is dequeued, so anything [inner]
         * queued first has already been counted.
         */
        class Synthesised(val event: OpenFeatureProviderEvents, val innerReportsAtRequest: Long) : Outgoing
    }

    /** Counts lifecycle events seen from [inner], so a synthesised event can be suppressed. */
    private var innerLifecycleEvents: Long = 0

    /**
     * Last lifecycle event actually published, whichever side it came from. Readiness is usually this
     * wrapper's own synthesised event rather than anything [inner] emitted, so a cancelled reconciliation
     * has to be restored from what was published, not from what was forwarded.
     */
    private var lastPublishedLifecycleEvent: OpenFeatureProviderEvents? = null

    private var activeReconciliations = 0
    private var reconciliationRestoreEvent: OpenFeatureProviderEvents? = null
    private var reconciliationTerminalEvent: OpenFeatureProviderEvents? = null
    private var reconciliationTerminalInnerEvents: Long? = null

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override suspend fun initialize(initialContext: EvaluationContext?) {
        if (synchronized(lock) { initialized }) return

        val seen = startRelay()
        try {
            inner.initialize(initialContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Emitted before propagating so handlers see the failure, not just the caller.
            synthesise(
                seen,
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(
                        message = e.message ?: "Provider failed to initialize",
                        errorCode = (e as? OpenFeatureError)?.errorCode()
                    )
                )
            )
            throw e
        }

        synchronized(lock) { initialized = true }
        synthesise(seen, OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        val toCancel = synchronized(lock) {
            initialized = false
            activeReconciliations = 0
            reconciliationRestoreEvent = null
            reconciliationTerminalEvent = null
            reconciliationTerminalInnerEvents = null
            val job = relayJob
            relayJob = null
            job
        }
        toCancel?.cancel(CancellationException("Provider event relay cancelled due to shutdown"))

        try {
            inner.shutdown()
        } finally {
            val toClose = synchronized(lock) {
                val current = scope
                scope = null
                current
            }
            toClose?.cancel(CancellationException("Legacy provider wrapper closed due to shutdown"))
        }
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        val shouldAnnounce = synchronized(lock) {
            if (activeReconciliations == 0) {
                reconciliationRestoreEvent = lastPublishedLifecycleEvent
                reconciliationTerminalEvent = null
                reconciliationTerminalInnerEvents = null
            }
            activeReconciliations++
            activeReconciliations == 1
        }
        if (shouldAnnounce) {
            outgoing.send(Outgoing.Forwarded(OpenFeatureProviderEvents.ProviderReconciling()))
        }

        var terminal: OpenFeatureProviderEvents? = null
        try {
            inner.onContextSet(oldContext, newContext)
            terminal = OpenFeatureProviderEvents.ProviderContextChanged()
        } catch (e: CancellationException) {
            // Cancelled by design: contributes no outcome, so the pre-reconciliation state is restored.
        } catch (e: OpenFeatureError) {
            terminal = OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(message = e.message, errorCode = e.errorCode())
            )
        } catch (e: Throwable) {
            terminal = OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(message = e.message ?: "Context reconciliation failed")
            )
        } finally {
            // Non-cancellable: a cancelled invocation still has to report or restore.
            withContext(NonCancellable) {
                completeReconciliation(terminal)?.let { queueAfterRelay(it) }
            }
        }
    }

    /**
     * Records this invocation's outcome and returns the event to emit, if this was the last invocation
     * in flight and nothing from [inner] has superseded the outcome in the meantime.
     */
    private fun completeReconciliation(terminal: OpenFeatureProviderEvents?): OpenFeatureProviderEvents? =
        synchronized(lock) {
            if (terminal != null) {
                reconciliationTerminalEvent = terminal
                reconciliationTerminalInnerEvents = innerLifecycleEvents
            }
            activeReconciliations--
            if (activeReconciliations > 0) return@synchronized null

            val recorded = reconciliationTerminalEvent
            val supersededByInner = recorded != null && reconciliationTerminalInnerEvents != innerLifecycleEvents
            val result = when {
                supersededByInner -> null
                recorded != null -> recorded
                // No outcome recorded means every invocation was cancelled.
                else -> reconciliationRestoreEvent
            }
            reconciliationRestoreEvent = null
            reconciliationTerminalEvent = null
            reconciliationTerminalInnerEvents = null
            result
        }

    /** Queues an event the wrapper originates, after letting the relay forward what it already has. */
    private suspend fun queueAfterRelay(event: OpenFeatureProviderEvents) {
        yield()
        outgoing.send(Outgoing.Forwarded(event))
    }

    private fun startRelay(): Long = synchronized(lock) {
        relayJob?.cancel(CancellationException("Provider event relay cancelled due to re-initialization"))
        val relayScope = scope ?: CoroutineScope(SupervisorJob() + eventDispatcher).also { scope = it }
        if (publishJob == null) {
            publishJob = relayScope.launch { publishOutgoing() }
        }
        relayJob = relayScope.launch {
            inner.observe().collect { event ->
                outgoing.send(Outgoing.Forwarded(event))
            }
        }
        innerLifecycleEvents
    }

    private suspend fun publishOutgoing() {
        for (item in outgoing) {
            when (item) {
                is Outgoing.Forwarded -> {
                    if (item.event.toOpenFeatureStatus() != null) {
                        synchronized(lock) {
                            innerLifecycleEvents++
                            lastPublishedLifecycleEvent = item.event
                        }
                    }
                    events.emit(item.event)
                }

                is Outgoing.Synthesised -> {
                    val reported = synchronized(lock) { innerLifecycleEvents > item.innerReportsAtRequest }
                    if (!reported) {
                        if (item.event.toOpenFeatureStatus() != null) {
                            synchronized(lock) { lastPublishedLifecycleEvent = item.event }
                        }
                        events.emit(item.event)
                    }
                }
            }
        }
    }

    /**
     * Queues [event] to be published unless [inner] reports a lifecycle event of its own first.
     *
     * A provider midway through migrating may emit some of its own lifecycle events; where it does, the
     * wrapper stands aside rather than reporting the same transition twice.
     */
    private suspend fun synthesise(seen: Long, event: OpenFeatureProviderEvents) {
        // The relay goes first so anything the provider emitted can suppress this stand-in.
        yield()
        outgoing.send(Outgoing.Synthesised(event, seen))
    }
}