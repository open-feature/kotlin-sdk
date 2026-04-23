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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("TooManyFunctions")
object OpenFeatureAPI {

    @PublishedApi
    internal val repository = ProviderRepository()
    private val globalContextMutex = Mutex()
    private val jobMutex = Mutex()
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
        launchProviderJob(null, provider, dispatcher, initialContext, isGlobalContext = true)
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
        launchProviderJob(domain, provider, dispatcher, initialContext, isGlobalContext = false)
    }

    private fun launchProviderJob(
        domain: String?,
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher,
        initialContext: EvaluationContext?,
        isGlobalContext: Boolean
    ) {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            val state = repository.getOrCreateState(domain)
            state.jobMutex.withLock {
                state.setProviderJob?.cancel(
                    CancellationException("Provider set job was cancelled due to new provider")
                )
                state.setProviderJob = coroutineContext[Job]
            }
            setProviderInternal(state, provider, dispatcher, initialContext, isGlobalContext)
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
        repository.attachProvider(provider)

        // Atomically swap the old and new provider to prevent race conditions
        val oldProvider = state.providerMutex.withLock {
            val current = state.provider
            state.providersFlow.value = provider
            state.emitStatus(OpenFeatureStatus.NotReady)
            current
        }

        // Shutdown the previous provider isolated from stream errors
        if (repository.detachProvider(oldProvider)) {
            try {
                oldProvider.shutdown()
            } catch (e: Exception) {
                // Ignore termination exceptions from dead configurations natively securely
            }
        }

        if (initialContext != null) {
            updateContextOnProviderSet(state, initialContext, isGlobalContext, dispatcher)
        }

        initializeProvider(state, provider, dispatcher)
    }

    private suspend fun updateContextOnProviderSet(
        state: DomainState,
        initialContext: EvaluationContext,
        isGlobalContext: Boolean,
        dispatcher: CoroutineDispatcher
    ) {
        if (!isGlobalContext) {
            val globalCtx = globalContextMutex.withLock { context }
            state.contextMutex.withLock {
                state.context = initialContext
                state.mergedContext = globalCtx?.mergeWith(initialContext) ?: initialContext
            }
            return
        }

        val states = globalContextMutex.withLock {
            context = initialContext
            repository.getAllStates()
        }

        states.forEach { s ->
            if (s === state) {
                s.contextMutex.withLock {
                    s.mergedContext = s.context?.let { initialContext.mergeWith(it) } ?: initialContext
                }
            } else {
                s.jobMutex.withLock {
                    s.setEvaluationContextJob?.cancel(
                        CancellationException("Set context job cancelled by global provider")
                    )
                    s.setEvaluationContextJob = CoroutineScope(SupervisorJob() + dispatcher).launch {
                        applyContextToState(s, initialContext)
                    }
                }
            }
        }
    }

    private suspend fun initializeProvider(
        state: DomainState,
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher
    ) {
        state.initializeListener(dispatcher)
        state.ioMutex.withLock {
            tryWithStatusEmitErrorHandling(state) {
                val globalCtx = globalContextMutex.withLock { context }
                val resolvedContext = state.contextMutex.withLock { state.mergedContext } ?: globalCtx

                if (provider !is NoOpProvider) {
                    val initMutex = repository.getInitMutex(provider)
                    initMutex.withLock {
                        if (!repository.isInitialized(provider)) {
                            provider.initialize(resolvedContext)
                            repository.markInitialized(provider)
                            repository.updateGlobalProviderStatus(provider, OpenFeatureStatus.Ready)
                            state.emitStatus(OpenFeatureStatus.Ready)
                            state.emitEvent(OpenFeatureProviderEvents.ProviderReady())
                        } else {
                            val currentStatus = repository.getGlobalProviderStatus(provider) ?: OpenFeatureStatus.Ready
                            state.emitStatus(currentStatus)
                            when (currentStatus) {
                                is OpenFeatureStatus.Ready -> state.emitEvent(OpenFeatureProviderEvents.ProviderReady())
                                is OpenFeatureStatus.Error -> state.emitEvent(
                                    OpenFeatureProviderEvents.ProviderError(
                                        dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.EventDetails(
                                            message = "Provider in error state"
                                        )
                                    )
                                )
                                is OpenFeatureStatus.Stale -> state.emitEvent(OpenFeatureProviderEvents.ProviderStale())
                                else -> {}
                            }
                        }
                    }
                } else {
                    provider.initialize(resolvedContext)
                    state.emitStatus(OpenFeatureStatus.Ready)
                    state.emitEvent(OpenFeatureProviderEvents.ProviderReady())
                }
            }
        }
    }

    private suspend fun applyContextToState(
        state: DomainState,
        globalCtx: EvaluationContext?,
        applyDomainCtx: ((EvaluationContext?) -> EvaluationContext?)? = null
    ) {
        val (oldMerged, newMerged) = state.contextMutex.withLock {
            val oldMerged = state.mergedContext

            applyDomainCtx?.let { handler ->
                state.context = handler(state.context)
            }

            state.mergedContext = if (globalCtx != null && state.context != null) {
                globalCtx.mergeWith(state.context!!)
            } else {
                globalCtx ?: state.context
            }

            oldMerged to (state.mergedContext ?: ImmutableContext())
        }

        setEvaluationContextForState(state, oldMerged, newMerged)
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

    /**
     * Clear the [EvaluationContext] for the SDK. This method will block until the context is cleared and the providers from all domains are ready.
     */
    suspend fun clearEvaluationContextAndWait() {
        clearEvaluationContextInternal(null)
    }

    /**
     * Clear the [EvaluationContext] for a specific domain. This method will block until the context is cleared and the provider is ready.
     *
     * @param domain the domain
     */
    suspend fun clearEvaluationContextAndWait(domain: String?) {
        clearEvaluationContextInternal(domain)
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
        launchEvaluationContextJob(null, dispatcher, "Set context job was cancelled due to new context") {
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
        launchEvaluationContextJob(domain, dispatcher, "Set context job was cancelled due to new context") {
            if (domain == null) {
                setEvaluationContextInternal(evaluationContext)
            } else {
                setEvaluationContextInternal(domain, evaluationContext)
            }
        }
    }

    /**
     * Clear the [EvaluationContext] for the SDK. This method will return immediately and clear the context in a coroutine scope.
     */
    fun clearEvaluationContext(
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        launchEvaluationContextJob(null, dispatcher, "Clear context job was cancelled due to new context") {
            clearEvaluationContextInternal(null)
        }
    }

    /**
     * Clear the [EvaluationContext] for a specific domain. This method will return immediately and clear the context in a coroutine scope.
     *
     * @param domain the domain
     */
    fun clearEvaluationContext(
        domain: String?,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) {
        launchEvaluationContextJob(domain, dispatcher, "Clear context job was cancelled due to new context") {
            clearEvaluationContextInternal(domain)
        }
    }

    private fun launchEvaluationContextJob(
        domain: String?,
        dispatcher: CoroutineDispatcher,
        cancellationMessage: String,
        block: suspend () -> Unit
    ) {
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            if (domain == null) {
                jobMutex.withLock {
                    setEvaluationContextJob?.cancel(CancellationException(cancellationMessage))
                    setEvaluationContextJob = coroutineContext[Job]
                }
            } else {
                val state = repository.getOrCreateState(domain)
                state.jobMutex.withLock {
                    state.setEvaluationContextJob?.cancel(CancellationException(cancellationMessage))
                    state.setEvaluationContextJob = coroutineContext[Job]
                }
            }
            block()
        }
    }

    private suspend fun setEvaluationContextInternal(evaluationContext: EvaluationContext) {
        val states = globalContextMutex.withLock {
            context = evaluationContext
            repository.getAllStates()
        }

        kotlinx.coroutines.coroutineScope {
            states.forEach { state ->
                launch { applyContextToState(state, evaluationContext) }
            }
        }
    }

    private suspend fun setEvaluationContextInternal(domain: String?, evaluationContext: EvaluationContext) {
        if (domain == null) {
            setEvaluationContextInternal(evaluationContext)
            return
        }
        val globalCtx = globalContextMutex.withLock { context }
        val state = repository.getOrCreateState(domain)
        applyContextToState(state, globalCtx) { evaluationContext }
    }

    private suspend fun clearEvaluationContextInternal(domain: String?) {
        if (domain == null) {
            val states = globalContextMutex.withLock {
                context = null
                repository.getAllStates()
            }
            kotlinx.coroutines.coroutineScope {
                states.forEach { state ->
                    launch { applyContextToState(state, null) }
                }
            }
            return
        }

        val globalCtx = globalContextMutex.withLock { context }
        applyContextToState(repository.getState(domain), globalCtx) { null }
    }

    private suspend fun setEvaluationContextForState(
        state: DomainState,
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        if (oldContext == newContext) return

        state.ioMutex.withLock {
            val activeProvider = state.providerMutex.withLock {
                state.provider.takeIf { state.getStatus() != OpenFeatureStatus.NotReady }
            }

            activeProvider?.let { provider ->
                state.emitStatus(OpenFeatureStatus.Reconciling)
                state.emitEvent(OpenFeatureProviderEvents.ProviderReconciling())
                tryWithStatusEmitErrorHandling(state) {
                    provider.onContextSet(oldContext, newContext)
                    state.emitStatus(OpenFeatureStatus.Ready)
                    state.emitEvent(OpenFeatureProviderEvents.ProviderContextChanged())
                }
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
            state.emitEvent(
                OpenFeatureProviderEvents.ProviderError(
                    eventDetails = OpenFeatureProviderEvents.EventDetails(
                        message = e.message ?: "Unknown error",
                        errorCode = e.errorCode()
                    )
                )
            )
        } catch (e: Throwable) {
            val generalError = OpenFeatureError.GeneralError(e.message ?: "Unknown error")
            state.emitStatus(OpenFeatureStatus.Error(generalError))
            state.emitEvent(
                OpenFeatureProviderEvents.ProviderError(
                    eventDetails = OpenFeatureProviderEvents.EventDetails(
                        message = generalError.message,
                        errorCode = generalError.errorCode()
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
        jobMutex.withLock {
            setEvaluationContextJob?.cancel(CancellationException("Job cancelled due to shutdown"))
        }
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

    @PublishedApi
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun getEventsFlowForDomain(domain: String?): Flow<OpenFeatureProviderEvents> =
        repository.getStateFlow(domain).flatMapLatest { it.eventsFlow }

    /**
     * Observe events from currently configured default provider.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T : OpenFeatureProviderEvents> observe(): Flow<T> =
        getEventsFlowForDomain(null).filterIsInstance()

    /**
     * Observe events from currently configured provider for the specified domain.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T : OpenFeatureProviderEvents> observe(domain: String?): Flow<T> =
        getEventsFlowForDomain(domain).filterIsInstance()
}