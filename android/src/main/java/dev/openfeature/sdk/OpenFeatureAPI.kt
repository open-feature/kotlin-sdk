package dev.openfeature.sdk

import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.awaitReadyOrError
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flatMapLatest

@Suppress("TooManyFunctions")
object OpenFeatureAPI {
    private val NOOP_PROVIDER = NoOpProvider()
    private var provider: FeatureProvider = NOOP_PROVIDER
    private var context: EvaluationContext? = null
    private val providersFlow: MutableSharedFlow<FeatureProvider> = MutableSharedFlow(replay = 1)
    internal val sharedProvidersFlow: SharedFlow<FeatureProvider> get() = providersFlow

    var hooks: List<Hook<*>> = listOf()
        private set

    fun setProvider(provider: FeatureProvider, initialContext: EvaluationContext? = null) {
        this@OpenFeatureAPI.provider = provider
        providersFlow.tryEmit(provider)
        if (initialContext != null) context = initialContext
        try {
            provider.initialize(context)
        } catch (e: Throwable) {
            // This is not allowed to happen
        }
    }

    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        dispatcher: CoroutineDispatcher,
        initialContext: EvaluationContext? = null
    ) {
        setProvider(provider, initialContext)
        provider.awaitReadyOrError(dispatcher)
    }

    fun getProvider(): FeatureProvider {
        return provider
    }

    fun clearProvider() {
        provider = NOOP_PROVIDER
    }

    fun setEvaluationContext(evaluationContext: EvaluationContext) {
        val oldContext = context
        context = evaluationContext
        getProvider().onContextSet(oldContext, evaluationContext)
    }

    fun getEvaluationContext(): EvaluationContext? {
        return context
    }

    fun getProviderMetadata(): ProviderMetadata? {
        return provider.metadata
    }

    fun getClient(name: String? = null, version: String? = null): Client {
        return OpenFeatureClient(this, name, version)
    }

    fun addHooks(hooks: List<Hook<*>>) {
        this.hooks += hooks
    }

    fun clearHooks() {
        this.hooks = listOf()
    }

    fun shutdown() {
        provider.shutdown()
    }

    /*
    Observe events from currently configured Provider.
    */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal inline fun <reified T : OpenFeatureEvents> observe(): Flow<T> {
        return sharedProvidersFlow.flatMapLatest { provider ->
            provider.observe<T>()
        }
    }
}