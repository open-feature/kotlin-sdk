package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
object OpenFeatureAPI {

    @PublishedApi
    internal val repository = ProviderRepository()
    private var context: EvaluationContext? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val providersFlow: Flow<FeatureProvider> get() = repository.getStateFlow(null)
        .flatMapLatest { it.providersFlow }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val statusFlow: Flow<OpenFeatureStatus> get() = repository.getStateFlow(null)
        .flatMapLatest { it.statusFlow }.distinctUntilChanged()

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * Set the [FeatureProvider] for the SDK. This method will return immediately and initialize the provider in a coroutine scope
     * @param provider the provider to set
     * @param dispatcher the dispatcher to use for the provider initialization coroutine
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization
     *
     * When the provider successfully reconciles it will set the status to [OpenFeatureStatus.Ready].
     * If the provider fails to reconcile it will set the status to [OpenFeatureStatus.Error].
     *
     * This method requires you to manually wait for the status to be Ready before using the SDK for flag evaluations.
     * This can be done by using the [statusFlow] and waiting for the first Ready status or by accessing [getStatus].
     */
    fun setProvider(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        initialContext: EvaluationContext? = null
    ) {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            val state = repository.getOrCreateState(null)
            state.setProviderJob?.cancel(CancellationException("Provider set job was cancelled due to new provider"))
            state.setProviderJob = coroutineContext[Job]
            setProviderInternal(state, provider, dispatcher, initialContext, isGlobalContext = true)
        }
    }

    /**
     * Set the [FeatureProvider] for a specific domain.
     */
    fun setProvider(
        domain: String?,
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        initialContext: EvaluationContext? = null
    ) {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            val state = repository.getOrCreateState(domain)
            state.setProviderJob?.cancel(CancellationException("Provider set job was cancelled due to new provider"))
            state.setProviderJob = coroutineContext[Job]
            setProviderInternal(state, provider, dispatcher, initialContext, isGlobalContext = false)
        }
    }

    /**
     * Set the [FeatureProvider] for the SDK. This method will block until the provider is initialized.
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        setProviderInternal(
            repository.getOrCreateState(null),
            provider,
            dispatcher,
            initialContext,
            isGlobalContext = true
        )
    }

    /**
     * Set the [FeatureProvider] for a specific domain. This method will block until the provider is initialized.
     */
    suspend fun setProviderAndWait(
        domain: String?,
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        setProviderInternal(
            repository.getOrCreateState(domain),
            provider,
            dispatcher,
            initialContext,
            isGlobalContext = false
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun setProviderInternal(
        state: DomainState,
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher,
        initialContext: EvaluationContext? = null,
        isGlobalContext: Boolean = false
    ) {
        // Atomically swap the old and new provider to prevent race conditions
        val oldProvider = state.providerMutex.withLock {
            val current = state.provider
            state.providersFlow.value = provider
            if (initialContext != null) {
                if (isGlobalContext) {
                    context = initialContext
                    repository.getAllStates().forEach { updateMergedContext(it) }
                } else {
                    state.context = initialContext
                    updateMergedContext(state)
                }
            }
            current
        }

        // Emit NotReady status after swapping provider
        state.emitStatus(OpenFeatureStatus.NotReady)

        // Shutdown the previous provider outside the mutex
        tryWithStatusEmitErrorHandling(state) {
            oldProvider.shutdown()
        }

        // Initialize the new provider
        state.initializeListener(dispatcher)
        tryWithStatusEmitErrorHandling(state) {
            val resolvedContext = state.mergedContext ?: context
            provider.initialize(resolvedContext)
            state.emitStatus(OpenFeatureStatus.Ready)
        }
    }

    private fun updateMergedContext(state: DomainState) {
        val globalCtx = context
        state.mergedContext = when {
            state.context == null -> globalCtx
            globalCtx == null -> state.context
            else -> globalCtx.mergeWith(state.context!!)
        }
    }

    /**
     * Get the current default [FeatureProvider] for the SDK.
     */
    fun getProvider(): FeatureProvider {
        return repository.getState().provider
    }

    /**
     * Get the [FeatureProvider] for the specified domain.
     */
    fun getProvider(domain: String?): FeatureProvider {
        return repository.getState(domain).provider
    }

    /**
     * Clear all current [FeatureProvider]s for the SDK and set it to a no-op provider.
     */
    suspend fun clearProvider() {
        repository.clearAll()
    }

    /**
     * Set the [EvaluationContext] for the SDK. This method will block until the context is set and the providers form all domains are ready.
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    suspend fun setEvaluationContextAndWait(evaluationContext: EvaluationContext) {
        setEvaluationContextInternal(evaluationContext)
    }

    /**
     * Set the [EvaluationContext] for a specific domain. This method will block until the context is set and the provider is ready.
     *
     * @param domain the domain
     * @param evaluationContext the [EvaluationContext] to set
     */
    suspend fun setEvaluationContextAndWait(domain: String?, evaluationContext: EvaluationContext) {
        if (domain == null) {
            setEvaluationContextAndWait(evaluationContext)
            return
        }
        setEvaluationContextInternal(domain, evaluationContext)
    }

    private var setEvaluationContextJob: Job? = null

    /**
     * Set the [EvaluationContext] for the SDK. This method will return immediately and set the context in a coroutine scope.
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    fun setEvaluationContext(
        evaluationContext: EvaluationContext,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        setEvaluationContextJob?.cancel(CancellationException("Set context job was cancelled due to new context"))
        setEvaluationContextJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
            setEvaluationContextInternal(evaluationContext)
        }
    }

    /**
     * Set the [EvaluationContext] for a specific domain. This method will return immediately and set the context in a coroutine scope.
     *
     * @param domain the domain
     * @param evaluationContext the [EvaluationContext] to set
     */
    fun setEvaluationContext(
        domain: String?,
        evaluationContext: EvaluationContext,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        if (domain == null) {
            setEvaluationContext(evaluationContext, dispatcher)
            return
        }
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            val state = repository.getOrCreateState(domain)
            state.setEvaluationContextJob?.cancel(
                CancellationException("Set context job was cancelled due to new context")
            )
            state.setEvaluationContextJob = coroutineContext[Job]
            setEvaluationContextInternal(domain, evaluationContext)
        }
    }

    private suspend fun setEvaluationContextInternal(evaluationContext: EvaluationContext) {
        context = evaluationContext
        val states = repository.getAllStates()
        kotlinx.coroutines.coroutineScope {
            for (state in states) {
                launch {
                    val oldMerged = state.mergedContext
                    updateMergedContext(state)
                    val newMerged = state.mergedContext!!
                    setEvaluationContextForState(state, oldMerged, newMerged)
                }
            }
        }
    }

    private suspend fun setEvaluationContextInternal(domain: String?, evaluationContext: EvaluationContext) {
        if (domain == null) {
            setEvaluationContextInternal(evaluationContext)
            return
        }
        val state = repository.getOrCreateState(domain)
        val oldMerged = state.mergedContext
        state.context = evaluationContext
        updateMergedContext(state)
        val newMerged = state.mergedContext!!
        setEvaluationContextForState(state, oldMerged, newMerged)
    }

    private suspend fun setEvaluationContextForState(
        state: DomainState,
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        if (oldContext != newContext) {
            state.emitStatus(OpenFeatureStatus.Reconciling)
            tryWithStatusEmitErrorHandling(state) {
                state.provider.onContextSet(oldContext, newContext)
                state.emitStatus(OpenFeatureStatus.Ready)
            }
        }
    }

    private suspend fun tryWithStatusEmitErrorHandling(state: DomainState, function: suspend () -> Unit) {
        try {
            function()
        } catch (e: CancellationException) {
            // This happens by design and shouldn't be treated as an error
        } catch (e: OpenFeatureError) {
            state.emitStatus(OpenFeatureStatus.Error(e))
        } catch (e: Throwable) {
            state.emitStatus(
                OpenFeatureStatus.Error(
                    OpenFeatureError.GeneralError(
                        e.message ?: "Unknown error"
                    )
                )
            )
        }
    }

    /**
     * Get the current global [EvaluationContext] for the SDK.
     */
    fun getEvaluationContext(): EvaluationContext? {
        return context
    }

    /**
     * Get the [EvaluationContext] for the specified domain. If not set, returns the global context.
     */
    fun getEvaluationContext(domain: String?): EvaluationContext? {
        if (domain == null) return context
        val state = repository.getState(domain)
        return state.context?.let { state.mergedContext } ?: context
    }

    /**
     * Get the [ProviderMetadata] for the current default [FeatureProvider].
     */
    fun getProviderMetadata(): ProviderMetadata? {
        return getProvider().metadata
    }

    /**
     * Get the [ProviderMetadata] for the [FeatureProvider] of the specified domain.
     */
    fun getProviderMetadata(domain: String?): ProviderMetadata? {
        return getProvider(domain).metadata
    }

    /**
     * Get a [Client] for the SDK.
     * This client can be used to evaluate flags.
     */
    fun getClient(domain: String? = null, version: String? = null): Client {
        return OpenFeatureClient(this, domain, version)
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
        setEvaluationContextJob?.cancel(CancellationException("Job cancelled due to shutdown"))
        clearHooks()
        clearProvider()
    }

    /**
     * Get the current [OpenFeatureStatus] of the default SDK provider.
     */
    fun getStatus(): OpenFeatureStatus = repository.getState().getStatus()

    /**
     * Get the current [OpenFeatureStatus] of the provider associated with the specified domain.
     */
    fun getProviderStatus(domain: String?): OpenFeatureStatus = repository.getState(domain).getStatus()

    /**
     * Get the status flow of the provider associated with the specified domain.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getProviderStatusFlow(domain: String?): Flow<OpenFeatureStatus> = repository.getStateFlow(domain)
        .flatMapLatest { it.statusFlow }.distinctUntilChanged()

    @PublishedApi
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun getProvidersFlowForDomain(domain: String?): Flow<FeatureProvider> =
        repository.getStateFlow(domain).flatMapLatest { it.providersFlow }

    /**
     * Observe events from currently configured default provider.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T : OpenFeatureProviderEvents> observe(): Flow<T> =
        getProvidersFlowForDomain(null)
            .flatMapLatest { it.observe() }
            .filterIsInstance()

    /**
     * Observe events from currently configured provider for the specified domain.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T : OpenFeatureProviderEvents> observe(domain: String?): Flow<T> =
        getProvidersFlowForDomain(domain)
            .flatMapLatest { it.observe() }
            .filterIsInstance()
}