package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val providerMutex = Mutex()
    private val NOOP_PROVIDER = NoOpProvider()
    private var provider: StateManagingProvider = NOOP_PROVIDER
    private var context: EvaluationContext? = null

    private val _providersFlow: MutableStateFlow<StateManagingProvider> = MutableStateFlow(NOOP_PROVIDER)

    /**
     * The active [StateManagingProvider], including when the SDK swaps or clears the provider. This is
     * read-only for consumers; use [setProvider], [setProviderAndWait], or [clearProvider] to change it.
     */
    val providersFlow: StateFlow<StateManagingProvider> = _providersFlow

    /**
     * Get the current [OpenFeatureStatus] for the SDK.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val statusFlow: Flow<OpenFeatureStatus>
        get() = providersFlow.flatMapLatest { it.status }.distinctUntilChanged()

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * Runs the same logic as [setProviderAndWait] in a new coroutine on [dispatcher],
     * then returns immediately. Calling again cancels the previous registration job.
     *
     * After [FeatureProvider.initialize] returns, the coroutine waits until [StateManagingProvider.status] is
     * neither [OpenFeatureStatus.NotReady] nor [OpenFeatureStatus.Reconciling]. The SDK does not impose a
     * time limit: meeting that contract is the provider’s responsibility. A provider that never updates
     * [StateManagingProvider.status] accordingly can keep this coroutine waiting indefinitely. Application
     * code that needs a wall-clock bound can use [kotlinx.coroutines.withTimeout] (or a similar policy)
     * around the whole registration flow, if appropriate for your app.
     *
     * Plain [FeatureProvider] implementations (not [StateManagingProvider]) are wrapped; their status is
     * derived from [FeatureProvider.observe] with the legacy lifecycle rules as before.
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
     * Suspends until provider swap, previous provider shutdown, and initialization
     * complete (same steps as [setProvider], but on the caller's coroutine).
     *
     * After `initialize` returns, suspends until [StateManagingProvider.status] is neither
     * [OpenFeatureStatus.NotReady] nor [OpenFeatureStatus.Reconciling] (for example
     * [OpenFeatureStatus.Ready] or [OpenFeatureStatus.Error]).
     *
     * The SDK does not enforce a time limit for that wait: the provider is responsible for updating
     * [StateManagingProvider.status] after [FeatureProvider.initialize] as required by
     * [StateManagingProvider]. If the provider never does, this function never completes. If your
     * application needs a maximum wait time, wrap the call in [kotlinx.coroutines.withTimeout] (or
     * equivalent) at the call site; that is an application policy, not part of the provider contract.
     *
     * @param provider the [FeatureProvider] to set
     * @param initialContext the initial [EvaluationContext] to use for the
     * provider initialization. Defaults to null context if not set.
     * @param dispatcher used when a plain [FeatureProvider] is wrapped in
     * [LegacyFeatureProviderAdapter] (event observation is scheduled on this dispatcher).
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        setProviderInternal(provider, dispatcher, initialContext)
    }

    // Error handling is done in the caller's coroutine to avoid crashing async flows
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun setProviderInternal(
        newProvider: FeatureProvider,
        dispatcher: CoroutineDispatcher,
        initialContext: EvaluationContext? = null
    ) {
        val normalizedProvider: StateManagingProvider = when (newProvider) {
            is StateManagingProvider -> newProvider
            else -> LegacyFeatureProviderAdapter(newProvider, dispatcher)
        }

        val oldProvider = providerMutex.withLock {
            val current = provider
            provider = normalizedProvider
            _providersFlow.value = normalizedProvider
            if (initialContext != null) context = initialContext
            current
        }

        // Shutdown the previous provider outside the mutex
        tryWithErrorHandling {
            oldProvider.shutdown()
        }

        // Initialize the new provider
        tryWithErrorHandling {
            normalizedProvider.initialize(context)
            normalizedProvider.status.first {
                it !is OpenFeatureStatus.NotReady && it !is OpenFeatureStatus.Reconciling
            }
        }
    }

    /**
     * Get the current [FeatureProvider] registered by the application.
     *
     * When a plain [FeatureProvider] was set, this returns that instance (not an internal wrapper).
     */
    fun getProvider(): FeatureProvider {
        val p = provider
        return if (p is LegacyFeatureProviderAdapter) p.inner else p
    }

    /**
     * Clear the current [FeatureProvider] for the SDK and set it to a no-op provider.
     */
    suspend fun clearProvider() {
        val oldProvider = providerMutex.withLock {
            val current = provider
            provider = NOOP_PROVIDER
            _providersFlow.value = NOOP_PROVIDER
            current
        }

        oldProvider.shutdown()
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
            tryWithErrorHandling {
                provider.onContextSet(oldContext, evaluationContext)
            }
        }
    }

    private suspend fun tryWithErrorHandling(function: suspend () -> Unit) {
        try {
            function()
        } catch (e: CancellationException) {
            // This happens by design and shouldn't be treated as an error
        } catch (e: Throwable) {
            // This happens by design - provider is responsible for its error handling
            throw e
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
        clearProvider()
    }

    /**
     * Get the current [OpenFeatureStatus] of the SDK.
     */
    fun getStatus(): OpenFeatureStatus {
        return provider.status.value
    }

    /**
     * Observe events from currently configured Provider.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T : OpenFeatureProviderEvents> observe(): Flow<T> = providersFlow
        .flatMapLatest { it.observe() }
        .filterIsInstance<T>()
}