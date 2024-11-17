package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.CancellationException

@Suppress("TooManyFunctions")
object OpenFeatureAPI {
    private var setProviderJob: Deferred<Unit>? = null
    private val NOOP_PROVIDER = NoOpProvider()
    private var provider: FeatureProvider = NOOP_PROVIDER
    private var context: EvaluationContext? = null

    private val _statusFlow: MutableSharedFlow<OpenFeatureStatus> =
        MutableSharedFlow<OpenFeatureStatus>(replay = 1, extraBufferCapacity = 5)
            .apply {
                tryEmit(OpenFeatureStatus.NotReady)
            }

    /**
     * A flow of [OpenFeatureStatus] that emits the current status of the SDK.
     */
    val statusFlow: SharedFlow<OpenFeatureStatus> get() = _statusFlow

    var hooks: List<Hook<*>> = listOf()
        private set

    /**
     * Set the [FeatureProvider] for the SDK. This method will return immediately and initialize the provider in a coroutine scope
     * When the provider is successfully initialized it will set the status to Ready.
     * If the provider fails to initialize it will set the status to Error.
     *
     * This method requires you to manually wait for the status to be Ready before using the SDK for flag evaluations.
     * This can be done by using the [statusFlow] and waiting for the first Ready status or by accessing [getStatus]
     *
     * @param provider the provider to set
     * @param dispatcher the dispatcher to use for the provider initialization coroutine. Defaults to [Dispatchers.IO] if not set.
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization. Defaults to an null context if not set.
     */
    fun setProvider(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        initialContext: EvaluationContext? = null
    ) {
        setProviderJob?.cancel()
        this.setProviderJob = CoroutineScope(dispatcher).async {
            setProviderInternal(provider, initialContext)
        }
    }

    /**
     * Set the [FeatureProvider] for the SDK. This method will block until the provider is initialized.
     *
     * @param provider the [FeatureProvider] to set
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization. Defaults to an null context if not set.
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null
    ) {
        setProviderInternal(provider, initialContext)
    }

    private suspend fun setProviderInternal(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null
    ) {
        this@OpenFeatureAPI.provider = provider
        // TODO consider if stale status should emit? _statusFlow.tryEmit(OpenFeatureStatus.Stale)
        if (initialContext != null) context = initialContext
        try {
            getProvider().initialize(context)
            _statusFlow.tryEmit(OpenFeatureStatus.Ready)
        } catch (e: OpenFeatureError) {
            _statusFlow.tryEmit(OpenFeatureStatus.Error(e))
        } catch (e: Throwable) {
            _statusFlow.tryEmit(
                OpenFeatureStatus.Error(
                    OpenFeatureError.GeneralError(
                        e.message ?: e.javaClass.name
                    )
                )
            )
            // TODO deal with things by setting status to Error or Fatal
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
    fun clearProvider() {
        provider = NOOP_PROVIDER
    }

    /**
     * Set the [EvaluationContext] for the SDK.
     * If the new context is different compare to the old context, this will cause the provider to reconcile with the new context.
     * When the provider "Reconciles" it will set the status to [OpenFeatureStatus.Reconciling].
     * When the provider successfully reconciles it will set the status to [OpenFeatureStatus.Ready].
     * If the provider fails to reconcile it will set the status to [OpenFeatureStatus.Error].
     *
     * @param evaluationContext the [EvaluationContext] to set
     */
    suspend fun setEvaluationContext(evaluationContext: EvaluationContext) {
        val oldContext = context
        context = evaluationContext
        if (oldContext != evaluationContext) {
            _statusFlow.tryEmit(OpenFeatureStatus.Reconciling)
            try {
                getProvider().onContextSet(oldContext, evaluationContext)
                _statusFlow.tryEmit(OpenFeatureStatus.Ready)
            } catch (e: OpenFeatureError) {
                _statusFlow.tryEmit(OpenFeatureStatus.Error(e))
                // TODO how do we handle fatal errors?
            } catch (e: Throwable) {
                _statusFlow.tryEmit(
                    OpenFeatureStatus.Error(
                        OpenFeatureError.GeneralError(
                            e.message ?: e.javaClass.name
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
    fun shutdown() {
        setProviderJob?.cancel(CancellationException("Provider set job was cancelled"))
        _statusFlow.tryEmit(OpenFeatureStatus.NotReady)
        getProvider().shutdown()
        clearHooks()
    }

    /**
     * Get the current [OpenFeatureStatus] of the SDK.
     */
    fun getStatus(): OpenFeatureStatus = statusFlow.replayCache.first()
}