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
    private data class ContextReconciliation(
        val oldContext: EvaluationContext?,
        val provider: FeatureProvider,
        val providerGeneration: Long
    )

    private var setProviderJob: Job? = null
    private var setEvaluationContextJob: Job? = null
    private var observeProviderEventsJob: Job? = null

    private val providerMutex = Mutex()
    private val contextReconciliationMutex = Mutex()
    private val stateLock = SynchronizedObject()
    private val noOpProvider = NoOpProvider()
    private var provider: FeatureProvider = noOpProvider
    private var providerGeneration: Long = 0
    private var context: EvaluationContext? = null
    private var contextReconciliationGeneration: Long? = null
    private var activeContextReconciliations: Int = 0
    private var contextReconciliationInitialStatus: OpenFeatureStatus? = null
    private var contextReconciliationTerminalStatus: OpenFeatureStatus? = null
    private var providerStatusGeneration: Long = 0
    private var contextReconciliationTerminalProviderStatusGeneration: Long? = null
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
                ?.withProviderName(getProvider().metadata.name)
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

    private fun listenToProviderEvents(provider: FeatureProvider, dispatcher: CoroutineDispatcher) {
        observeProviderEventsJob?.cancel(CancellationException("Provider job was cancelled due to new provider"))
        this.observeProviderEventsJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
            provider.observe().collect { event ->
                dispatchProviderEvent(event, provider.metadata.name)
            }
        }
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

        // Track whether the swap committed so a mid-flight cancellation can roll back the binding.
        var swapCommitted = false
        try {
            // Atomically swap the old and new provider to prevent race conditions
            val oldProvider = providerMutex.withLock {
                synchronized(stateLock) {
                    val current = this.provider
                    this.provider = provider
                    providerGeneration++
                    providersFlow.value = provider
                    if (initialContext != null) context = initialContext
                    current
                }
            }
            swapCommitted = true

            // Emit NotReady status after swapping provider
            _status.value = OpenFeatureStatus.NotReady

            // Shutdown the previous provider outside the mutex
            if (oldProvider !== provider) {
                tryWithStatusEmitErrorHandling {
                    untrackProviderBinding(oldProvider)
                    oldProvider.shutdown()
                }
            }

            // Initialize the new provider
            tryWithStatusEmitErrorHandling {
                listenToProviderEvents(provider, dispatcher)
                val state = getEvaluationState()
                state.provider.initialize(state.context)
                _status.value = OpenFeatureStatus.Ready
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
        val oldProvider = providerMutex.withLock {
            synchronized(stateLock) {
                val current = this.provider
                this.provider = noOpProvider
                providerGeneration++
                providersFlow.value = noOpProvider
                current
            }
        }
        untrackProviderBinding(oldProvider)
        oldProvider.shutdown()
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
        var reconciliation: ContextReconciliation? = null
        var terminalStatus: OpenFeatureStatus? = null
        try {
            contextReconciliationMutex.withLock {
                providerMutex.withLock {
                    var shouldEmitReconciling = false
                    synchronized(stateLock) {
                        val oldContext = context
                        context = evaluationContext
                        if (provider !== noOpProvider) {
                            reconciliation = ContextReconciliation(oldContext, provider, providerGeneration)
                            if (contextReconciliationGeneration != providerGeneration) {
                                contextReconciliationGeneration = providerGeneration
                                activeContextReconciliations = 0
                            }
                            if (activeContextReconciliations == 0) {
                                contextReconciliationInitialStatus = getStatus()
                                contextReconciliationTerminalStatus = null
                                contextReconciliationTerminalProviderStatusGeneration = null
                            }
                            activeContextReconciliations++
                            shouldEmitReconciling = activeContextReconciliations == 1
                        }
                    }
                    if (shouldEmitReconciling) {
                        _status.value = OpenFeatureStatus.Reconciling
                    }
                }
            }

            val registeredReconciliation = reconciliation ?: return
            registeredReconciliation.provider.onContextSet(
                registeredReconciliation.oldContext,
                evaluationContext
            )
            terminalStatus = OpenFeatureStatus.Ready
        } catch (e: CancellationException) {
            // This happens by design and shouldn't be treated as an error
        } catch (e: OpenFeatureError) {
            terminalStatus = OpenFeatureStatus.Error(e)
        } catch (e: Throwable) {
            terminalStatus = OpenFeatureStatus.Error(
                OpenFeatureError.GeneralError(e.message ?: "Unknown error")
            )
        } finally {
            val registeredReconciliation = reconciliation
            if (registeredReconciliation != null) {
                withContext(NonCancellable) {
                    completeContextReconciliation(
                        registeredReconciliation.provider,
                        registeredReconciliation.providerGeneration,
                        terminalStatus
                    )
                }
            }
        }
    }

    private suspend fun completeContextReconciliation(
        reconciliationProvider: FeatureProvider,
        reconciliationProviderGeneration: Long,
        terminalStatus: OpenFeatureStatus?
    ) {
        contextReconciliationMutex.withLock {
            if (contextReconciliationGeneration != reconciliationProviderGeneration) return

            if (terminalStatus != null) {
                contextReconciliationTerminalStatus = terminalStatus
                contextReconciliationTerminalProviderStatusGeneration = providerStatusGeneration
            }
            activeContextReconciliations--
            if (activeContextReconciliations == 0) {
                val retainedTerminalStatus = contextReconciliationTerminalStatus
                val statusToEmit = retainedTerminalStatus ?: contextReconciliationInitialStatus
                val shouldEmitStatus = if (retainedTerminalStatus != null) {
                    contextReconciliationTerminalProviderStatusGeneration == providerStatusGeneration
                } else {
                    getStatus() is OpenFeatureStatus.Reconciling
                }
                contextReconciliationInitialStatus = null
                contextReconciliationTerminalStatus = null
                contextReconciliationTerminalProviderStatusGeneration = null

                providerMutex.withLock {
                    if (
                        synchronized(stateLock) { provider === reconciliationProvider } &&
                        providerGeneration == reconciliationProviderGeneration &&
                        statusToEmit != null &&
                        shouldEmitStatus
                    ) {
                        _status.value = statusToEmit
                    }
                }
            }
        }
    }

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
    private suspend fun dispatchProviderEvent(event: OpenFeatureProviderEvents, providerName: String?) {
        val stamped = event.withProviderName(providerName)
        stamped.toOpenFeatureStatus()?.let { emitProviderStatus(it) }
        if (!_events.tryEmit(stamped)) {
            LoggerFactory.getLogger(LOGGER_NAME).warn(
                { "Dropped provider event ${stamped::class.simpleName}: a subscriber is not keeping up." }
            )
        }
    }

    private suspend fun emitProviderStatus(status: OpenFeatureStatus) {
        contextReconciliationMutex.withLock {
            providerStatusGeneration++
            _status.value = status
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