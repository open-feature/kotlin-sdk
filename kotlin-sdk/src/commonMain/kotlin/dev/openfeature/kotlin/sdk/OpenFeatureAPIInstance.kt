package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.logging.LoggerFactory
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

private const val LOGGER_NAME = "OpenFeatureAPI"

/**
 * Core implementation of the OpenFeature API.
 *
 * Each instance maintains its own independent state: provider, evaluation context and hooks. The
 * global singleton [OpenFeatureAPI] is one such instance. To create isolated, independent instances
 * use [dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance].
 *
 * Status belongs to the registered provider, not to this instance: [getStatus] and [statusFlow] both
 * read [FeatureProvider.status], and the SDK never sets it. The one exception is shutdown, where the
 * SDK infers not-ready because it is the SDK that initiated the shutdown.
 *
 * @see OpenFeatureAPI
 * @see dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
 */
@Suppress("TooManyFunctions")
open class OpenFeatureAPIInstance internal constructor() {
    private val logger = LoggerFactory.getLogger(LOGGER_NAME)
    private val stateLock = SynchronizedObject()

    /** The provider installed when none has been registered, or once one has been cleared. */
    private class NoProvider : NoOpProvider()

    /**
     * One registration of one provider, boxed so that [MutableStateFlow] never conflates two of
     * them: a fresh box is never equal to its predecessor, so every swap restarts the subscriptions
     * derived from it, which is what keeps a retired provider's events out of its successor's stream.
     */
    private class ProviderRegistration(
        val provider: FeatureProvider,
        dispatcher: CoroutineDispatcher
    ) {
        /**
         * Serial, so this provider's lifecycle calls are entered one at a time and in the order they
         * were made. A call that suspends releases the dispatcher, so the SDK never waits for one
         * lifecycle call to finish before entering the next.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val scope = CoroutineScope(
            SupervisorJob() +
                dispatcher.limitedParallelism(1) +
                CoroutineExceptionHandler { _, _ -> /* reported by dispatchLifecycle */ }
        )

        var providerJob: Job? = null
        var contextSetJob: Job? = null
    }

    private var registration = ProviderRegistration(NoProvider(), Dispatchers.Default)
    private val providerRegistrations = MutableStateFlow(registration)

    private var context: EvaluationContext? = null

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * The status of the registered provider, and every transition it reports.
     *
     * A projection of [FeatureProvider.status], so it can never disagree with [getStatus]. A
     * provider that reports nothing yields exactly one [OpenFeatureStatus.NotReady].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val statusFlow: Flow<OpenFeatureStatus> = providerRegistrations
        .flatMapLatest { current ->
            current.provider.observe()
                .transform { event ->
                    // The event's own status, not a re-read of provider.status: two transitions
                    // reported back to back would otherwise both read the later one.
                    val reported = event.toOpenFeatureStatus()
                    reported?.let { emit(it) }
                    // Then catch up, so a subscriber whose buffer overflowed and lost an event still
                    // converges on the truth at the next one rather than staying behind for good.
                    val live = current.provider.status
                    if (live != reported) emit(live)
                }
                .onStart { emit(current.provider.status) }
        }
        .distinctUntilChanged()

    /**
     * Set the [FeatureProvider] for this instance. Returns once the provider is registered, having
     * started its initialization; the provider reports readiness itself through its events.
     *
     * @param provider the provider to set
     * @param dispatcher the dispatcher this provider's lifecycle calls run on
     * @param initialContext the initial [EvaluationContext] for provider initialization
     */
    fun setProvider(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        initialContext: EvaluationContext? = null
    ) {
        swapProvider(provider, initialContext, dispatcher)
    }

    /**
     * Set the [FeatureProvider] for this instance, suspending until its `initialize` has terminated.
     *
     * A provider reports its own outcome, so this does not throw when initialization fails: the
     * failure arrives as an [OpenFeatureProviderEvents.ProviderError] and as
     * [OpenFeatureStatus.Error]. A provider that throws without reporting anything stays
     * [OpenFeatureStatus.NotReady], and the failure is logged.
     *
     * @param provider the [FeatureProvider] to set
     * @param initialContext the initial [EvaluationContext] for provider initialization
     * @param dispatcher the dispatcher this provider's lifecycle calls run on; the caller's own
     * dispatcher by default, so that a caller controlling time controls the provider's lifecycle too
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher? = null
    ) {
        swapProvider(provider, initialContext, dispatcher ?: callerDispatcher())
            .joinPropagatingCancellation()
    }

    private suspend fun callerDispatcher(): CoroutineDispatcher =
        currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default

    private fun swapProvider(
        newProvider: FeatureProvider,
        initialContext: EvaluationContext?,
        dispatcher: CoroutineDispatcher
    ): Job {
        trackProviderBinding(newProvider)

        val next = ProviderRegistration(newProvider, dispatcher)
        val (previous, initializationContext) = synchronized(stateLock) {
            val previous = registration
            registration = next
            if (initialContext != null) context = initialContext
            // Published under the same lock that commits the swap: otherwise two concurrent swaps can
            // leave evaluations on one provider and statusFlow/observe() pinned to the other.
            providerRegistrations.value = next
            previous to context
        }

        // Retired here rather than from the successor's job: that job can be cancelled before it is
        // ever dispatched, which would leave the outgoing provider running and never shut down.
        release(previous)
        if (previous.provider !== newProvider) retireProvider(previous.provider)

        next.providerJob = next.dispatchLifecycle("initialize") {
            newProvider.initialize(initializationContext)
        }
        return requireNotNull(next.providerJob)
    }

    /**
     * Get the current [FeatureProvider] for this instance.
     */
    fun getProvider(): FeatureProvider = synchronized(stateLock) { registration.provider }

    /**
     * Snapshot of the provider, evaluation context and hooks for synchronous client operations.
     */
    internal fun getEvaluationState(): EvaluationState = synchronized(stateLock) {
        EvaluationState(registration.provider, context, hooks)
    }

    /**
     * Clear the current [FeatureProvider] and reset to a no-op provider.
     *
     * Installs a provider that was never initialized, so the status is not-ready once this returns,
     * as requirement 1.7.6 asks: the SDK initiated the shutdown, so no provider event is involved.
     */
    suspend fun clearProvider() {
        val next = ProviderRegistration(NoProvider(), Dispatchers.Default)
        val previous = synchronized(stateLock) {
            val previous = registration
            registration = next
            providerRegistrations.value = next
            previous
        }
        release(previous)
        retireProvider(previous.provider)
    }

    /**
     * Stops a registration's lifecycle work, which every swap owes its predecessor — including one
     * that re-registers the same provider instance, whose scope would otherwise be left running.
     */
    private fun release(retired: ProviderRegistration) {
        val cause = CancellationException("Provider registration was replaced")
        retired.providerJob?.cancel(cause)
        retired.contextSetJob?.cancel(cause)
        retired.scope.cancel(cause)
    }

    /**
     * Unbinds a provider and shuts it down.
     *
     * Only for a provider that is actually being dropped: re-registering the same instance must not
     * shut it down. A `CancellationException` from `shutdown` is logged rather than rethrown — it
     * belongs to the provider being retired, not to whoever asked for the swap.
     */
    private fun retireProvider(provider: FeatureProvider) {
        untrackProviderBinding(provider)
        try {
            provider.shutdown()
        } catch (e: Throwable) {
            logger.warn({ "Provider ${provider.attributionName()} failed to shut down" }, throwable = e)
        }
    }

    /**
     * Set the [EvaluationContext] for this instance, suspending until the provider's `onContextSet`
     * has terminated.
     *
     * The provider reports the transitions around its own reconciliation, so a provider still
     * reconciling in the background leaves the status [OpenFeatureStatus.Reconciling] when this
     * returns.
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    suspend fun setEvaluationContextAndWait(evaluationContext: EvaluationContext) {
        updateContext(evaluationContext).joinPropagatingCancellation()
    }

    /**
     * Set the [EvaluationContext] for this instance. Returns once the reconciliation has started.
     *
     * Reconciliation runs on the dispatcher the provider was registered with, so that it is ordered
     * against that provider's `initialize`.
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    fun setEvaluationContext(evaluationContext: EvaluationContext) {
        // Started lazily so that superseding the previous job and recording this one are one step:
        // otherwise two concurrent calls can leave one of the two jobs untracked and uncancellable.
        // Only the fire-and-forget path supersedes its predecessor — an awaited context set must not
        // cancel another awaited one, since overlapping reconciliations are legal and the provider
        // collapses them into a single reported transition.
        val job = updateContext(evaluationContext, CoroutineStart.LAZY) { current, job ->
            current.contextSetJob?.cancel(
                CancellationException("Set context job was cancelled due to new context")
            )
            current.contextSetJob = job
        }
        job.start()
    }

    private fun updateContext(
        newContext: EvaluationContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        record: (ProviderRegistration, Job) -> Unit = { _, _ -> }
    ): Job = synchronized(stateLock) {
        val current = registration
        val oldContext = context
        context = newContext

        val job = current.dispatchLifecycle("onContextSet", start) {
            current.provider.onContextSet(oldContext, newContext)
        }
        record(current, job)
        job
    }

    /**
     * Awaits a lifecycle call, cancelling it if the caller is cancelled.
     *
     * The call runs on the registration's own scope so that its ordering does not depend on the
     * caller, which means cancelling the caller would otherwise leave it running.
     */
    private suspend fun Job.joinPropagatingCancellation() {
        try {
            join()
        } catch (e: CancellationException) {
            cancel(e)
            // Awaited so the provider has finished unwinding — and reported the outcome it owes the
            // reconciliation — before the caller sees the cancellation.
            withContext(NonCancellable) { join() }
            throw e
        }
    }

    /**
     * Runs one of [provider]'s lifecycle calls.
     *
     * A throw is not a status signal — the provider owns its status — so it is logged and the status
     * left alone. Cancellation still propagates, so structured concurrency and timeouts work.
     */
    private fun ProviderRegistration.dispatchLifecycle(
        operation: String,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        work: suspend () -> Unit
    ): Job = scope.launch(start = start) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn({
                "Provider ${provider.attributionName()} failed during $operation. The SDK does not " +
                    "derive status from a thrown exception: report the failure by emitting a " +
                    "ProviderError event."
            }, throwable = e)
        }
    }

    /**
     * Get the current [EvaluationContext] for this instance.
     */
    fun getEvaluationContext(): EvaluationContext? {
        return synchronized(stateLock) { context }
    }

    /**
     * Get the [ProviderMetadata] for the current [FeatureProvider].
     */
    fun getProviderMetadata(): ProviderMetadata? {
        return getProvider().metadata
    }

    /**
     * Get a [Client] for this instance.
     */
    fun getClient(name: String? = null, version: String? = null): Client {
        return OpenFeatureClient(this, name, version)
    }

    /**
     * Add [Hook]s to this instance.
     */
    fun addHooks(hooks: List<Hook<*>>) {
        synchronized(stateLock) { this.hooks += hooks }
    }

    /**
     * Clear all [Hook]s from this instance.
     */
    fun clearHooks() {
        synchronized(stateLock) { this.hooks = listOf() }
    }

    /**
     * Shutdown this instance. Cancels pending jobs and resets the provider to no-op.
     */
    suspend fun shutdown() {
        clearHooks()
        clearProvider()
    }

    /**
     * Get the current [OpenFeatureStatus] of this instance, as reported by its provider.
     */
    fun getStatus(): OpenFeatureStatus = getProvider().status

    /**
     * Observe the events emitted by the currently configured provider.
     *
     * Switches to the new provider on a swap, so a retired provider's events stop arriving. To handle
     * one event type, narrow with the reified [observe] overload or with
     * [kotlinx.coroutines.flow.filterIsInstance].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<OpenFeatureProviderEvents> =
        providerRegistrations.flatMapLatest { it.provider.observe() }

    /**
     * Claims [provider] for this instance.
     *
     * Two instances driving one provider would drive one [ProviderStatusTracker], leaving its status
     * undefined, so this is a programming error rather than a provider failure — and with status
     * owned by the provider there is no status channel left to report it on.
     *
     * @throws IllegalStateException if another instance already owns [provider]
     */
    private fun trackProviderBinding(provider: FeatureProvider) {
        if (provider is NoProvider) return
        synchronized(bindingLock) {
            val existingOwner = boundProviders.findOwner(provider)
            if (existingOwner != null && existingOwner !== this) {
                throw IllegalStateException(
                    "Provider ${provider.metadata.name} is already bound to another OpenFeature API instance. " +
                        "A provider should not be bound to multiple API instances simultaneously."
                )
            }
            boundProviders.setOwner(provider, this)
        }
    }

    private fun untrackProviderBinding(provider: FeatureProvider) {
        if (provider is NoProvider) return
        synchronized(bindingLock) {
            if (boundProviders.findOwner(provider) === this) {
                boundProviders.removeProvider(provider)
            }
        }
    }

    companion object {
        /**
         * Identity-based registry tracking which instance owns each provider.
         * Uses a list with === checks instead of a map to avoid structural equality issues
         * when providers implement equals/hashCode.
         */
        private val boundProviders = IdentityRegistry()
        private val bindingLock = SynchronizedObject()

        /**
         * Clear all provider bindings. Intended for test isolation only.
         */
        internal fun clearBoundProviders() {
            boundProviders.clear()
        }
    }
}

/**
 * Observe one type of event from the currently configured provider.
 *
 * The unparameterised [OpenFeatureAPIInstance.observe] yields every event; this narrows it.
 */
inline fun <reified T : OpenFeatureProviderEvents> OpenFeatureAPIInstance.observe(): Flow<T> =
    observe().filterIsInstance()

/** Provider name for a log line, or null: naming a provider must never fail a registration. */
internal fun FeatureProvider.attributionName(): String? = runCatching { metadata.name }.getOrNull()

/**
 * Simple identity-based registry. All lookups use referential equality (===) so that
 * distinct provider objects are never conflated, even if they share equals/hashCode.
 */
internal class IdentityRegistry {
    private val entries = mutableListOf<Pair<FeatureProvider, OpenFeatureAPIInstance>>()

    fun findOwner(provider: FeatureProvider): OpenFeatureAPIInstance? {
        return entries.firstOrNull { it.first === provider }?.second
    }

    fun setOwner(provider: FeatureProvider, owner: OpenFeatureAPIInstance) {
        val index = entries.indexOfFirst { it.first === provider }
        if (index >= 0) {
            entries[index] = provider to owner
        } else {
            entries.add(provider to owner)
        }
    }

    fun removeProvider(provider: FeatureProvider) {
        entries.removeAll { it.first === provider }
    }

    fun clear() {
        entries.clear()
    }
}