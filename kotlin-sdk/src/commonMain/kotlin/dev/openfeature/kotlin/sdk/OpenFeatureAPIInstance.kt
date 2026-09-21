package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOGGER_NAME = "OpenFeatureAPI"

/**
 * Core implementation of the OpenFeature API.
 *
 * Each instance maintains its own independent state: provider, evaluation context and hooks. The
 * global singleton [OpenFeatureAPI] is one such instance. To create isolated, independent instances
 * use [dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance].
 *
 * Status belongs to the registered provider: [getStatus] and [statusFlow] both read
 * [FeatureProvider.status], which the SDK never sets.
 *
 * @see OpenFeatureAPI
 * @see dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
 */
@Suppress("TooManyFunctions")
open class OpenFeatureAPIInstance internal constructor() {
    private val logger = LoggerFactory.getLogger(LOGGER_NAME)
    private val stateLock = SynchronizedObject()

    /**
     * The provider installed when none has been registered, or once one has been cleared.
     *
     * SDK no longer checks status before evaluating, so reporting unreadiness is this provider's
     * own job, as requirement 2.2.7 asks of any provider.
     */
    private class NoProvider : NoOpProvider() {
        override fun getBooleanEvaluation(
            key: String,
            defaultValue: Boolean,
            context: EvaluationContext?
        ): ProviderEvaluation<Boolean> = throw OpenFeatureError.ProviderNotReadyError()

        override fun getStringEvaluation(
            key: String,
            defaultValue: String,
            context: EvaluationContext?
        ): ProviderEvaluation<String> = throw OpenFeatureError.ProviderNotReadyError()

        override fun getIntegerEvaluation(
            key: String,
            defaultValue: Int,
            context: EvaluationContext?
        ): ProviderEvaluation<Int> = throw OpenFeatureError.ProviderNotReadyError()

        override fun getLongEvaluation(
            key: String,
            defaultValue: Long,
            context: EvaluationContext?
        ): ProviderEvaluation<Long> = throw OpenFeatureError.ProviderNotReadyError()

        override fun getDoubleEvaluation(
            key: String,
            defaultValue: Double,
            context: EvaluationContext?
        ): ProviderEvaluation<Double> = throw OpenFeatureError.ProviderNotReadyError()

        override fun getObjectEvaluation(
            key: String,
            defaultValue: Value,
            context: EvaluationContext?
        ): ProviderEvaluation<Value> = throw OpenFeatureError.ProviderNotReadyError()
    }

    /**
     * One registration of one provider, boxed so that a swap restarts the subscriptions derived from
     * it, which keeps a retired provider's events out of its successor's stream. Re-registering the
     * same instance reuses its box, so nothing restarts.
     */
    private class ProviderRegistration(
        val provider: FeatureProvider,
        dispatcher: CoroutineDispatcher
    ) {
        /** Serial, so this provider's lifecycle calls are entered in the order they were made. */
        val scope = CoroutineScope(
            SupervisorJob() +
                dispatcher.serialized() +
                CoroutineExceptionHandler { _, _ -> /* reported by dispatchLifecycle */ }
        )

        var providerJob: Job? = null
        var contextSetJob: Job? = null
    }

    private var registration = ProviderRegistration(NoProvider(), Dispatchers.Default)
    private val providerRegistrations = MutableStateFlow(registration)

    /** Never cancelled: a dropped retirement leaks the provider it was meant to release. */
    private val retirementScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    /** Retirements still in flight, so a provider registered again is ordered after its teardown. */
    private val retirements = mutableListOf<Pair<FeatureProvider, Job>>()

    private var context: EvaluationContext? = null

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * The status of the registered provider, and every transition it reports.
     *
     * Each emission comes from one event, so transitions arrive in the order the provider reported
     * them. [OpenFeatureStatus.NotReady] has no event, so a provider that returns to it after
     * registration reports that through [getStatus] alone; clearing the provider still emits it,
     * because the new registration restarts this flow. A provider that reports nothing yields
     * exactly one [OpenFeatureStatus.NotReady].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val statusFlow: Flow<OpenFeatureStatus> = providerRegistrations
        .flatMapLatest { current ->
            current.provider.observe()
                .transform { event -> event.toOpenFeatureStatus()?.let { emit(it) } }
                .onStart { emit(current.provider.status) }
        }
        .distinctUntilChanged()

    /**
     * Set the [FeatureProvider] for this instance. Returns once the provider is registered, having
     * started its initialization; the provider reports readiness itself through its events. The
     * outgoing provider is shut down in the background, so this does not wait for its teardown.
     *
     * @param provider the provider to set
     * @param dispatcher the dispatcher this provider's lifecycle calls run on; a provider that is
     * already registered keeps the dispatcher it was first registered with
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
     * failure arrives as an [OpenFeatureProviderEvents.ProviderError]. A provider that throws
     * without reporting anything stays [OpenFeatureStatus.NotReady].
     *
     * @param provider the [FeatureProvider] to set
     * @param initialContext the initial [EvaluationContext] for provider initialization
     * @param dispatcher the dispatcher this provider's lifecycle calls run on; pass the caller's own
     * to put the provider's lifecycle under a caller that controls time
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        val swap = swapProvider(provider, initialContext, dispatcher)
        swap.retirement?.join()
        swap.initialization.joinPropagatingCancellation()
    }

    /** The two pieces of work a swap starts: initializing the new provider, retiring the old one. */
    private class Swap(val initialization: Job, val retirement: Job?)

    /** What a swap decides under [stateLock], so retirement can run without holding the lock. */
    private class Commit(val current: ProviderRegistration, val retired: ProviderRegistration?)

    private fun swapProvider(
        newProvider: FeatureProvider,
        initialContext: EvaluationContext?,
        dispatcher: CoroutineDispatcher
    ): Swap {
        lateinit var initialization: Job
        val commit = synchronized(stateLock) {
            // Claimed under the lock that commits the swap, so a retirement of an earlier
            // registration of this same provider cannot unbind what is about to be published.
            trackProviderBinding(newProvider)
            val previous = registration
            // Reusing the registration keeps this initialize ordered against the provider's own
            // in-flight work.
            val rebinding = previous.provider === newProvider
            val current = if (rebinding) previous else ProviderRegistration(newProvider, dispatcher)
            registration = current
            if (initialContext != null) context = initialContext
            // Published under the lock that commits the swap, or two concurrent swaps can leave
            // evaluations on one provider and statusFlow pinned to the other.
            providerRegistrations.value = current
            val initializationContext = context
            val pendingRetirements = retirements.filter { it.first === newProvider }.map { it.second }
            // Dispatched before the lock is released, or a setEvaluationContext that acquires the
            // lock right after this one could queue onContextSet ahead of initialize on the
            // registration's serial scope.
            initialization = current.dispatchLifecycle("initialize") {
                // A retirement of this provider that already committed to shutting it down is still
                // running: initializing over it would race its teardown.
                pendingRetirements.forEach { it.join() }
                newProvider.initialize(initializationContext)
            }
            current.providerJob = initialization
            Commit(current = current, retired = previous.takeUnless { rebinding })
        }

        // Not from the successor's job, which can be cancelled before it is ever dispatched.
        val retirement = commit.retired?.let { retire(it) }
        return Swap(initialization, retirement)
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
     * Installs a provider that was never initialized, so the status is not-ready once this returns.
     */
    suspend fun clearProvider() {
        val next = ProviderRegistration(NoProvider(), Dispatchers.Default)
        val previous = synchronized(stateLock) {
            val previous = registration
            registration = next
            providerRegistrations.value = next
            previous
        }
        retire(previous).join()
    }

    /**
     * Retires a replaced registration: stops its lifecycle work, then unbinds and shuts its provider
     * down away from the caller's thread, since `shutdown` releases resources and threads.
     */
    private fun retire(retired: ProviderRegistration): Job {
        val cause = CancellationException("Provider registration was replaced")
        retired.providerJob?.cancel(cause)
        retired.contextSetJob?.cancel(cause)
        retired.scope.cancel(cause)
        val job = retirementScope.launch(start = CoroutineStart.LAZY) { retireProvider(retired.provider) }
        // Recorded before it can run, so a swap committing meanwhile finds it and waits for it.
        synchronized(stateLock) { retirements += retired.provider to job }
        job.invokeOnCompletion {
            synchronized(stateLock) { retirements.removeAll { (_, pending) -> pending === job } }
        }
        job.start()
        return job
    }

    /**
     * Shuts a provider down and unbinds it.
     *
     * Only for a provider that is actually being dropped: re-registering the same instance must not
     * shut it down, whether the registration was reused or this retirement was simply outrun.
     *
     * The binding outlives `shutdown`, so another instance claiming it meanwhile is refused.
     */
    private fun retireProvider(provider: FeatureProvider) {
        // Nothing escapes to the retirement scope, which has no handler of its own.
        try {
            if (synchronized(stateLock) { registration.provider === provider }) return
            try {
                provider.shutdown()
            } finally {
                synchronized(stateLock) {
                    if (registration.provider !== provider) untrackProviderBinding(provider)
                }
            }
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
        // Only this path supersedes the previous reconciliation: overlapping awaited ones are legal.
        updateContext(evaluationContext) { current, job ->
            current.contextSetJob?.cancel(
                CancellationException("Set context job was cancelled due to new context")
            )
            current.contextSetJob = job
        }
    }

    private fun updateContext(
        newContext: EvaluationContext,
        record: (ProviderRegistration, Job) -> Unit = { _, _ -> }
    ): Job {
        // Created lazily so that committing the context, superseding the previous job and recording
        // this one are one step, and started outside the lock so that an immediate dispatcher runs
        // the provider's onContextSet without stateLock held.
        val job = synchronized(stateLock) {
            val current = registration
            val oldContext = context
            context = newContext

            val job = current.dispatchLifecycle("onContextSet", CoroutineStart.LAZY) {
                current.provider.onContextSet(oldContext, newContext)
            }
            record(current, job)
            job
        }
        job.start()
        return job
    }

    /**
     * Awaits a lifecycle call, cancelling it if the caller is cancelled: the call runs on the
     * registration's own scope, so cancelling the caller would otherwise leave it running.
     */
    private suspend fun Job.joinPropagatingCancellation() {
        try {
            join()
        } catch (e: CancellationException) {
            cancel(e)
            // Awaited so the provider has reported the outcome it owes before the caller returns.
            withContext(NonCancellable) { join() }
            throw e
        }
    }

    /**
     * Runs one of the provider's lifecycle calls, logging a throw rather than deriving a status
     * from it. Cancellation still propagates.
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
     * Switches to the new provider on a swap, so a retired provider's events stop arriving. Narrow
     * to one event type with the reified [observe] overload.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<OpenFeatureProviderEvents> =
        providerRegistrations.flatMapLatest { it.provider.observe() }

    /**
     * Claims [provider] for this instance.
     *
     * @throws IllegalStateException if another [OpenFeatureAPIInstance] already owns [provider]
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

/** Enters one lifecycle call at a time; Unconfined rejects limitedParallelism and cannot order. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun CoroutineDispatcher.serialized(): CoroutineDispatcher =
    if (this === Dispatchers.Unconfined) Dispatchers.Default.limitedParallelism(1) else limitedParallelism(1)

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