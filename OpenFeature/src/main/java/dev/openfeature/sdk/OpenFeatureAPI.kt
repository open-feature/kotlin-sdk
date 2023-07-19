package dev.openfeature.sdk

import kotlinx.coroutines.coroutineScope

object OpenFeatureAPI {
    private var provider: FeatureProvider? = null
    private var context: EvaluationContext? = null
    var hooks: List<Hook<*>> = listOf()
        private set

    suspend fun setProvider(provider: FeatureProvider, initialContext: EvaluationContext? = null) = coroutineScope {
        this@OpenFeatureAPI.provider = provider
        if (initialContext != null) context = initialContext
        provider.initialize(context)
    }

    fun getProvider(): FeatureProvider? {
        return provider
    }

    fun clearProvider() {
        provider = null
    }

    suspend fun setEvaluationContext(evaluationContext: EvaluationContext) {
        context = evaluationContext
        getProvider()?.onContextSet(context, evaluationContext)
    }

    fun getEvaluationContext(): EvaluationContext? {
        return context
    }

    fun getProviderMetadata(): ProviderMetadata? {
        return provider?.metadata
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
}