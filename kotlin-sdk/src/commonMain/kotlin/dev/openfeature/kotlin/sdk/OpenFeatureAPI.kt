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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
object OpenFeatureAPI {
    private var setProviderJob: Job? = null
    private var setEvaluationContextJob: Job? = null
    private var observeProviderEventsJob: Job? = null

    private val providerMutex = Mutex()
    private val NOOP_PROVIDER = NoOpProvider()
    private var provider: FeatureProvider = NOOP_PROVIDER
    private var context: EvaluationContext? = null
    val providersFlow: MutableStateFlow<FeatureProvider> = MutableStateFlow(NOOP_PROVIDER)

    private val _statusFlow: MutableSharedFlow<OpenFeatureStatus> =
        MutableSharedFlow<OpenFeatureStatus>(replay = 1, extraBufferCapacity = 5)
            .apply {
                tryEmit(OpenFeatureStatus.NotReady)
            }

    /**
     * Get the current [OpenFeatureStatus] for the SDK.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val statusFlow: Flow<OpenFeatureStatus>
        get() = providersFlow.flatMapLatest { p ->
            if (p is StateManagingProvider) {
                p.status
            } else {
                // Legacy path: use the shared Provider/SDK-managed status buffer.
                _statusFlow.distinctUntilChanged()
            }
        }

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * Set the [StateManagingProvider] for the SDK. This method will return immediately and initialize the
     * provider in a coroutine scope. Status becomes Ready or Error when a [StateManagingProvider]
     * updates [StateManagingProvider.status], or when a legacy provider emits on [FeatureProvider.observe].
     * If the provider fails to initialize it will set the status to Error.
     *
     * This method requires you to manually wait for the status to be Ready before using the SDK
     * for flag evaluations. This can be done by using [statusFlow] and waiting for the first Ready
     * status, or by accessing [getStatus].
     *
     * @param provider the provider to set
     * @param dispatcher the dispatcher to use for the provider initialization coroutine. Defaults to [Dispatchers.Default] if not set.
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization. Defaults to null context if not set.
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
     * Set the [StateManagingProvider] for the SDK. This method blocks until the provider
     * has signalled its initial state: [StateManagingProvider] via [StateManagingProvider.status],
     * or a legacy provider via when [FeatureProvider.initialize] finishes, it may throw an exception).
     *
     * @param provider the [FeatureProvider] to set
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization. Defaults to null context if not set.
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
        // Atomically swap the old and new provider to prevent race conditions
        val oldProvider = providerMutex.withLock {
            val current = this@OpenFeatureAPI.provider
            this@OpenFeatureAPI.provider = provider
            providersFlow.value = provider
            if (initialContext != null) context = initialContext
            current
        }

        // Emit NotReady status after swapping provider
        // Legacy path: reset Provider/SDK-managed status.
        if (provider !is StateManagingProvider) {
            _statusFlow.emit(OpenFeatureStatus.NotReady)
        }

        // Shutdown the previous provider outside the mutex
        if (oldProvider is StateManagingProvider) {
            oldProvider.shutdown()
        } else {
            // Legacy path: mirror shutdown failures into _statusFlow;
            tryWithStatusEmitErrorHandling {
                oldProvider.shutdown()
            }
        }

        // Initialize the new provider
        if (provider is StateManagingProvider) {
            // Stop any legacy listener from a previous provider so it cannot propagate to _statusFlow
            observeProviderEventsJob?.cancel(
                CancellationException("State-managing provider: SDK does not mirror observe() into status")
            )
            observeProviderEventsJob = null
            getProvider().initialize(context)
            provider.status.first { it !is OpenFeatureStatus.NotReady }
        } else {
            // Legacy path
                listenToProviderEvents(provider, dispatcher)
                getProvider().initialize(context)
                _statusFlow.emit(OpenFeatureStatus.Ready)
            }
        }
    }

    /**
     * Get the current [FeatureProvider] for the SDK.
     */
    fun getProvider(): FeatureProvider {
        return provider
    }

    /**
     * Clear the current [FeatureProvider] for the SDK and set it to a no-op provider.
     */
    suspend fun clearProvider() {
        getProvider().shutdown()
        provider = NOOP_PROVIDER
        providersFlow.value = NOOP_PROVIDER
        
        if (provider !is StateManagingProvider) {
            // Legacy path: reset Provider/SDK-managed status.
            _statusFlow.emit(OpenFeatureStatus.NotReady)
        }
    }

    /**
     * Set the [EvaluationContext] for the SDK. This method will block until the context is set and the provider is ready.
     *
     * If the new context is different compare to the old context, this will cause the provider to reconcile with the new context.
     * When the provider "Reconciles" it will set the status to [OpenFeatureStatus.Reconciling].
     * When the provider successfully reconciles it will set the status to [OpenFeatureStatus.Ready].
     * If the provider fails to reconcile it will set the status to [OpenFeatureStatus.Error].
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    suspend fun setEvaluationContextAndWait(evaluationContext: EvaluationContext) {
        setEvaluationContextInternal(evaluationContext)
    }

    /**
     * Set the [EvaluationContext] for the SDK. This method will return immediately and set the context in a coroutine scope.
     *
     * If the new context is different compare to the old context, this will cause the provider to reconcile with the new context.
     * When the provider "Reconciles" it will set the status to [OpenFeatureStatus.Reconciling].
     * When the provider successfully reconciles it will set the status to [OpenFeatureStatus.Ready].
     * If the provider fails to reconcile it will set the status to [OpenFeatureStatus.Error].
     *
     * This method requires you to manually wait for the status to be Ready before using the SDK for flag evaluations.
     * This can be done by using the [statusFlow] and waiting for the first Ready status or by accessing [getStatus]
     *
     *
     * @param evaluationContext the [EvaluationContext] to set
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
            val provider = getProvider()
            if (provider is StateManagingProvider) {
                provider.onContextSet(oldContext, evaluationContext)
            } else {
                // Legacy path: emit Reconciling/Ready (and errors via tryWith) on _statusFlow during context updates.
                tryWithStatusEmitErrorHandling {
                    _statusFlow.emit(OpenFeatureStatus.Reconciling)
                    provider.onContextSet(oldContext, evaluationContext)
                    _statusFlow.emit(OpenFeatureStatus.Ready)
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
            // Legacy path: only non-state-managing providers use _statusFlow for surfaced errors.
            if (getProvider() !is StateManagingProvider) {
                _statusFlow.emit(OpenFeatureStatus.Error(e))
            }
        } catch (e: Throwable) {
            // Legacy path: only non-state-managing providers use _statusFlow for surfaced errors.
            if (getProvider() !is StateManagingProvider) {
                _statusFlow.emit(
                    OpenFeatureStatus.Error(
                        OpenFeatureError.GeneralError(
                            e.message ?: "Unknown error"
                        )
                    )
                )
            }
        }
    }

    /**
     * Get the current [EvaluationContext] for the SDK.
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
     * Get a [Client] for the SDK.
     * This client can be used to evaluate flags.
     */
    fun getClient(name: String? = null, version: String? = null): Client {
        return OpenFeatureClient(this, name, version)
    }

    /**
     * Add [Hook]s to the SDK.
     */
    fun addHooks(hooks: List<Hook<*>>) {
        this.hooks += hooks
    }

    /**
     * Clear all [Hook]s from the SDK.
     */
    fun clearHooks() {
        this.hooks = listOf()
    }

    /**
     * Shutdown the SDK.
     * This will cancel the provider set job and call the provider's shutdown method.
     * The SDK status will be set to [OpenFeatureStatus.NotReady].
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
     * Get the current [OpenFeatureStatus] of the SDK.
     */
    // not very sure about how im handling this
    fun getStatus(): OpenFeatureStatus {
        val p = getProvider()
        return if (p is StateManagingProvider) {
            p.status.value
        } else {
            // Legacy path: last value from SDK-managed _statusFlow (distinct replay).
            _statusFlow.replayCache.first()
        }
    }

    /**
     * Observe events from currently configured Provider.
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

            is OpenFeatureProviderEvents.ProviderNotReady -> {
                _statusFlow.emit(OpenFeatureStatus.NotReady)
            }

            is OpenFeatureProviderEvents.ProviderReconciling -> {
                _statusFlow.emit(OpenFeatureStatus.Reconciling)
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
}