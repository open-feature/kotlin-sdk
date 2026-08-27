package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toCurrentStateEvent
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.events.withProviderName
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.logging.LoggerFactory
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val EVENT_BUFFER_CAPACITY = 64
private const val LOGGER_NAME = "OpenFeatureAPI"

/**
 * Core implementation of the OpenFeature API.
 *
 * Each instance maintains its own independent state: provider, evaluation context, hooks, status,
 * and events. The global singleton [OpenFeatureAPI] is one such instance. To create isolated,
 * independent instances use
 * [dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance].
 *
 * @see OpenFeatureAPI
 * @see dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
 */
@Suppress("TooManyFunctions")
open class OpenFeatureAPIInstance internal constructor() {
    private var setProviderJob: Job? = null
    private var setEvaluationContextJob: Job? = null
    private var observeProviderEventsJob: Job? = null

    private val providerMutex = Mutex()
    private val stateLock = SynchronizedObject()
    private val noOpProvider = NoOpProvider()

    /** The provider as registered by the application: what evaluations and [getProvider] use. */
    private var provider: FeatureProvider = noOpProvider

    /**
     * The provider the lifecycle is driven through and whose events are relayed. Identical to [provider]
     * for a [StateManagingProvider]; otherwise the [LegacyProviderWrapper] standing in for it.
     */
    private var lifecycleProvider: FeatureProvider = noOpProvider
    private var providerGeneration: Long = 0
    private var context: EvaluationContext? = null

    /** Completed when the active provider reports a lifecycle event, so registration can settle. */
    private var pendingLifecycleReport: CompletableDeferred<Unit>? = null
    val providersFlow: MutableStateFlow<FeatureProvider> = MutableStateFlow(noOpProvider)

    private val _status: MutableStateFlow<OpenFeatureStatus> = MutableStateFlow(OpenFeatureStatus.NotReady)

    val statusFlow: StateFlow<OpenFeatureStatus> get() = _status

    /**
     * Events from the active provider, republished by the SDK so that the status is always updated
     * before subscribers observe the event that caused it.
     *
     * Overflow drops the oldest event rather than suspending: a slow subscriber must never stall the
     * SDK's own status derivation.
     */
    private val _events: MutableSharedFlow<OpenFeatureProviderEvents> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @PublishedApi
    internal val providerEvents: Flow<OpenFeatureProviderEvents>
        get() = _events.onSubscription {
            // Replays the state, not the last event: a handler attached while the provider is already
            // in a state must run, but a stateless event must not resurface.
            _status.value.toCurrentStateEvent()
                ?.withProviderName(getProvider().attributionName())
                ?.let { emit(it) }
        }

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * Set the [FeatureProvider] for this instance. Returns immediately and initializes the provider
     * in a coroutine scope. When successfully initialized, status transitions to Ready.
     *
     * @param provider the provider to set
     * @param dispatcher the dispatcher for the initialization coroutine
     * @param initialContext the initial [EvaluationContext] for provider initialization
     */
    fun setProvider(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        initialContext: EvaluationContext? = null
    ) {
        setProviderJob?.cancel(CancellationException("Provider set job was cancelled due to new provider"))
        this.setProviderJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
            setProviderInternal(provider, dispatcher, initialContext)
        }
    }

    /**
     * Set the [FeatureProvider] for this instance. Suspends until the provider is initialized.
     *
     * @param provider the [FeatureProvider] to set
     * @param initialContext the initial [EvaluationContext] for provider initialization
     * @param dispatcher the dispatcher for event observation
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        setProviderInternal(provider, dispatcher, initialContext)
    }

    private class ReplacedProvider(val registered: FeatureProvider, val lifecycle: FeatureProvider)

    /**
     * Provider name for attribution and diagnostics, or null if the provider cannot supply one.
     *
     * Naming an event's origin must never be the reason a registration or an evaluation fails, so a
     * provider whose metadata is unavailable simply goes unattributed.
     */
    private fun FeatureProvider.attributionName(): String? = runCatching { metadata.name }.getOrNull()

    /**
     * Returns the provider the lifecycle should be driven through: the provider itself when it emits its
     * own lifecycle events, otherwise a wrapper that synthesises them on its behalf.
     */
    private fun normalizeProvider(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher
    ): FeatureProvider {
        if (provider is StateManagingProvider) return provider
        LoggerFactory.getLogger(LOGGER_NAME).warn({
            "Provider ${provider.attributionName()} does not implement StateManagingProvider, so the SDK is " +
                "synthesizing its lifecycle events. This compatibility behavior is deprecated and will " +
                "be removed in a future major version; see StateManagingProvider."
        })
        return LegacyProviderWrapper(provider, dispatcher)
    }

    /**
     * Waits for the provider's own lifecycle event to be applied to the status, so that registration
     * settles on what the provider reported rather than on the fact that `initialize` returned.
     *
     * A provider that terminates `initialize` without reporting anything leaves nothing to wait for; the
     * violation is logged rather than worked around, since substituting a status here is what created the
     * race this design removes.
     */
    private suspend fun awaitLifecycleReport(report: CompletableDeferred<Unit>, provider: FeatureProvider) {
        if (!report.isCompleted) {
            LoggerFactory.getLogger(LOGGER_NAME).warn({
                "Provider ${provider.attributionName()} returned from initialize without emitting a " +
                    "lifecycle event; waiting for it. Providers must emit ProviderReady or ProviderError " +
                    "before initialize terminates."
            })
        }
        report.await()
    }

    /**
     * Starts relaying [provider]'s events, returning once collection has begun.
     *
     * Providers are required to report readiness before `initialize` terminates, so the relay has to be
     * listening before `initialize` is called or that report can be missed.
     */
    private suspend fun listenToProviderEvents(provider: FeatureProvider, dispatcher: CoroutineDispatcher) {
        observeProviderEventsJob?.cancel(CancellationException("Provider job was cancelled due to new provider"))
        val listening = CompletableDeferred<Unit>()
        this.observeProviderEventsJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
            provider.observe()
                .onStart { listening.complete(Unit) }
                .collect { event ->
                    dispatchProviderEvent(event, provider.attributionName())
                }
        }
        listening.await()
    }

    private suspend fun setProviderInternal(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher,
        initialContext: EvaluationContext? = null
    ) {
        try {
            trackProviderBinding(provider)
        } catch (e: Throwable) {
            _status.value = OpenFeatureStatus.Error(
                OpenFeatureError.GeneralError(e.message ?: "Unknown error")
            )
            return
        }

        // Reusing the wrapper avoids initializing the provider twice and orphaning the old relay.
        val reusable = synchronized(stateLock) { if (this.provider === provider) lifecycleProvider else null }
        val normalizedProvider = reusable ?: normalizeProvider(provider, dispatcher)
        val lifecycleReport = CompletableDeferred<Unit>()

        // Track whether the swap committed so a mid-flight cancellation can roll back the binding.
        var swapCommitted = false
        try {
            // Atomically swap the old and new provider to prevent race conditions
            val replaced = providerMutex.withLock {
                synchronized(stateLock) {
                    val current = ReplacedProvider(this.provider, this.lifecycleProvider)
                    this.provider = provider
                    this.lifecycleProvider = normalizedProvider
                    providerGeneration++
                    providersFlow.value = provider
                    if (initialContext != null) context = initialContext
                    // Release a registration waiting on a provider it no longer owns.
                    pendingLifecycleReport?.complete(Unit)
                    pendingLifecycleReport = lifecycleReport
                    current
                }
            }
            swapCommitted = true

            // Emit NotReady status after swapping provider
            _status.value = OpenFeatureStatus.NotReady

            // Shutdown the previous provider outside the mutex
            if (replaced.registered !== provider) {
                tryWithStatusEmitErrorHandling {
                    untrackProviderBinding(replaced.registered)
                    replaced.lifecycle.shutdown()
                }
            }

            // Initialize the new provider
            try {
                listenToProviderEvents(normalizedProvider, dispatcher)
                normalizedProvider.initialize(getEvaluationState().context)
                awaitLifecycleReport(lifecycleReport, normalizedProvider)
            } catch (e: CancellationException) {
                // This happens by design and shouldn't be treated as an error
            } catch (e: Throwable) {
                // The provider's event sets the status; writing one here would publish it twice.
                LoggerFactory.getLogger(LOGGER_NAME).warn(
                    { "Provider ${normalizedProvider.attributionName()} failed to initialize" },
                    throwable = e
                )
            }
        } catch (e: CancellationException) {
            // if cancellation hit before we committed the swap, release the binding we just claimed
            // so the provider can be re-registered elsewhere.
            if (!swapCommitted) {
                withContext(NonCancellable) {
                    untrackProviderBinding(provider)
                }
            }
            throw e
        }
    }

    /**
     * Get the current [FeatureProvider] for this instance.
     */
    fun getProvider(): FeatureProvider {
        return synchronized(stateLock) { provider }
    }

    /**
     * Snapshot of the current provider and evaluation context for synchronous client operations.
     */
    internal fun getEvaluationState(): EvaluationState {
        return synchronized(stateLock) {
            EvaluationState(provider, context)
        }
    }

    /**
     * Clear the current [FeatureProvider] and reset to a no-op provider.
     */
    suspend fun clearProvider() {
        val replaced = providerMutex.withLock {
            synchronized(stateLock) {
                val current = ReplacedProvider(this.provider, this.lifecycleProvider)
                this.provider = noOpProvider
                this.lifecycleProvider = noOpProvider
                providerGeneration++
                providersFlow.value = noOpProvider
                // Release any registration still waiting.
                pendingLifecycleReport?.complete(Unit)
                pendingLifecycleReport = null
                current
            }
        }
        untrackProviderBinding(replaced.registered)
        replaced.lifecycle.shutdown()
        _status.value = OpenFeatureStatus.NotReady
    }

    /**
     * Set the [EvaluationContext] for this instance. Suspends until the context is set and the
     * provider has reconciled.
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    suspend fun setEvaluationContextAndWait(evaluationContext: EvaluationContext) {
        setEvaluationContextInternal(evaluationContext)
    }

    /**
     * Set the [EvaluationContext] for this instance. Returns immediately and sets the context
     * in a coroutine scope.
     *
     * @param evaluationContext the [EvaluationContext] to set
     * @param dispatcher the dispatcher for the context-set coroutine
     */
    fun setEvaluationContext(
        evaluationContext: EvaluationContext,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        setEvaluationContextJob?.cancel(CancellationException("Set context job was cancelled due to new context"))
        this.setEvaluationContextJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
            setEvaluationContextInternal(evaluationContext)
        }
    }

    private suspend fun setEvaluationContextInternal(evaluationContext: EvaluationContext) {
        val reconciliation = providerMutex.withLock {
            synchronized(stateLock) {
                val oldContext = context
                context = evaluationContext
                if (provider === noOpProvider) {
                    null
                } else {
                    ContextReconciliation(oldContext, lifecycleProvider)
                }
            }
        } ?: return

        try {
            reconciliation.provider.onContextSet(reconciliation.oldContext, evaluationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The outcome arrives as an event; not rethrown so a failed reconciliation is not fatal.
        }
    }

    private class ContextReconciliation(val oldContext: EvaluationContext?, val provider: FeatureProvider)

    private suspend fun tryWithStatusEmitErrorHandling(function: suspend () -> Unit) {
        try {
            function()
        } catch (e: CancellationException) {
            // This happens by design and shouldn't be treated as an error
        } catch (e: OpenFeatureError) {
            _status.value = OpenFeatureStatus.Error(e)
        } catch (e: Throwable) {
            _status.value = OpenFeatureStatus.Error(
                OpenFeatureError.GeneralError(
                    e.message ?: "Unknown error"
                )
            )
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
        this.hooks += hooks
    }

    /**
     * Clear all [Hook]s from this instance.
     */
    fun clearHooks() {
        this.hooks = listOf()
    }

    /**
     * Shutdown this instance. Cancels pending jobs and resets the provider to no-op.
     */
    suspend fun shutdown() {
        clearHooks()
        setEvaluationContextJob?.cancel(CancellationException("Set context job was cancelled due to shutdown"))
        setProviderJob?.cancel(CancellationException("Provider set job was cancelled due to shutdown"))
        observeProviderEventsJob?.cancel(
            CancellationException("Provider event observe job was cancelled due to shutdown")
        )
        clearProvider()
    }

    /**
     * Get the current [OpenFeatureStatus] of this instance.
     */
    fun getStatus(): OpenFeatureStatus = _status.value

    /**
     * Observe events of type [T] from the currently configured Provider.
     *
     * The status is always updated before an event reaches subscribers. Subscribing while the provider
     * is already in a state yields the event for that state immediately.
     */
    inline fun <reified T : OpenFeatureProviderEvents> observe(): Flow<T> = providerEvents.filterIsInstance()

    /**
     * Aligning the state management to
     * https://openfeature.dev/specification/sections/events#requirement-535
     */
    private fun dispatchProviderEvent(event: OpenFeatureProviderEvents, providerName: String?) {
        val stamped = event.withProviderName(providerName)
        stamped.toOpenFeatureStatus()?.let { status ->
            _status.value = status
            synchronized(stateLock) { pendingLifecycleReport }?.complete(Unit)
        }
        if (!_events.tryEmit(stamped)) {
            LoggerFactory.getLogger(LOGGER_NAME).warn(
                { "Dropped provider event ${stamped::class.simpleName}: a subscriber is not keeping up." }
            )
        }
    }

    private suspend fun trackProviderBinding(provider: FeatureProvider) {
        if (provider === noOpProvider) return
        bindingMutex.withLock {
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

    private suspend fun untrackProviderBinding(provider: FeatureProvider) {
        if (provider === noOpProvider) return
        bindingMutex.withLock {
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
        private val bindingMutex = Mutex()

        /**
         * Clear all provider bindings. Intended for test isolation only.
         */
        internal fun clearBoundProviders() {
            boundProviders.clear()
        }
    }
}

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