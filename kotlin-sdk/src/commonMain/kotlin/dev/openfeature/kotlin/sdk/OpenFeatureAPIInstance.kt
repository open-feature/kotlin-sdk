package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatusError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Core implementation of the OpenFeature API.
 *
 * Each instance maintains its own independent state: provider, evaluation context, hooks, status,
 * and events. The global singleton [OpenFeatureAPI] extends this class. To create isolated,
 * independent instances use [createOpenFeatureAPIInstance].
 *
 * @see OpenFeatureAPI
 * @see createOpenFeatureAPIInstance
 */
@Suppress("TooManyFunctions")
open class OpenFeatureAPIInstance internal constructor() {
    private var setProviderJob: Job? = null
    private var setEvaluationContextJob: Job? = null
    private var observeProviderEventsJob: Job? = null

    private val providerMutex = Mutex()
    private val noOpProvider = NoOpProvider()
    private var provider: FeatureProvider = noOpProvider
    private var context: EvaluationContext? = null
    val providersFlow: MutableStateFlow<FeatureProvider> = MutableStateFlow(noOpProvider)

    private val _statusFlow: MutableSharedFlow<OpenFeatureStatus> =
        MutableSharedFlow<OpenFeatureStatus>(replay = 1, extraBufferCapacity = 5)
            .apply {
                tryEmit(OpenFeatureStatus.NotReady)
            }

    val statusFlow: Flow<OpenFeatureStatus> get() = _statusFlow.distinctUntilChanged()

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
            provider.observe().collect(handleProviderEvents)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun setProviderInternal(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher,
        initialContext: EvaluationContext? = null
    ) {
        try {
            trackProviderBinding(provider)
        } catch (e: Throwable) {
            _statusFlow.emit(
                OpenFeatureStatus.Error(
                    OpenFeatureError.GeneralError(e.message ?: "Unknown error")
                )
            )
            return
        }

        // Atomically swap the old and new provider to prevent race conditions
        val oldProvider = providerMutex.withLock {
            val current = this.provider
            this.provider = provider
            providersFlow.value = provider
            if (initialContext != null) context = initialContext
            current
        }

        // Emit NotReady status after swapping provider
        _statusFlow.emit(OpenFeatureStatus.NotReady)

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
            getProvider().initialize(context)
            _statusFlow.emit(OpenFeatureStatus.Ready)
        }
    }

    /**
     * Get the current [FeatureProvider] for this instance.
     */
    fun getProvider(): FeatureProvider {
        return provider
    }

    /**
     * Clear the current [FeatureProvider] and reset to a no-op provider.
     */
    suspend fun clearProvider() {
        val oldProvider = providerMutex.withLock {
            val current = this.provider
            this.provider = noOpProvider
            providersFlow.value = noOpProvider
            current
        }
        untrackProviderBinding(oldProvider)
        oldProvider.shutdown()
        _statusFlow.emit(OpenFeatureStatus.NotReady)
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
        val oldContext = context
        context = evaluationContext
        if (oldContext != evaluationContext) {
            _statusFlow.emit(OpenFeatureStatus.Reconciling)
            tryWithStatusEmitErrorHandling {
                getProvider().onContextSet(oldContext, evaluationContext)
                _statusFlow.emit(OpenFeatureStatus.Ready)
            }
        }
    }

    private suspend fun tryWithStatusEmitErrorHandling(function: suspend () -> Unit) {
        try {
            function()
        } catch (e: CancellationException) {
            // This happens by design and shouldn't be treated as an error
        } catch (e: OpenFeatureError) {
            _statusFlow.emit(OpenFeatureStatus.Error(e))
        } catch (e: Throwable) {
            _statusFlow.emit(
                OpenFeatureStatus.Error(
                    OpenFeatureError.GeneralError(
                        e.message ?: "Unknown error"
                    )
                )
            )
        }
    }

    /**
     * Get the current [EvaluationContext] for this instance.
     */
    fun getEvaluationContext(): EvaluationContext? {
        return context
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
    fun getStatus(): OpenFeatureStatus = _statusFlow.replayCache.first()

    /**
     * Observe events from the currently configured Provider.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T : OpenFeatureProviderEvents> observe(): Flow<T> = providersFlow
        .flatMapLatest { it.observe() }.filterIsInstance()

    /**
     * Aligning the state management to
     * https://openfeature.dev/specification/sections/events#requirement-535
     */
    private val handleProviderEvents: FlowCollector<OpenFeatureProviderEvents> = FlowCollector { providerEvent ->
        when (providerEvent) {
            is OpenFeatureProviderEvents.ProviderReady -> {
                _statusFlow.emit(OpenFeatureStatus.Ready)
            }

            is OpenFeatureProviderEvents.ProviderStale -> {
                _statusFlow.emit(OpenFeatureStatus.Stale)
            }

            is OpenFeatureProviderEvents.ProviderError -> {
                _statusFlow.emit(providerEvent.toOpenFeatureStatusError())
            }

            else -> { // All other states should not be emitted from here
            }
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
}