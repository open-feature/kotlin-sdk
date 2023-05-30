package dev.openfeature.sdk

import kotlinx.coroutines.coroutineScope

object OpenFeatureAPI {
    private var provider: FeatureProvider? = null
    private var context: EvaluationContext? = null
    var hooks: List<Hook<*>> = listOf()
        private set

    suspend fun setProvider(provider: FeatureProvider, initialContext: EvaluationContext? = null) = coroutineScope {
        provider.initialize(initialContext ?: context)
        this@OpenFeatureAPI.provider = provider
        if (initialContext != null) context = initialContext
    }

    fun getProvider(): FeatureProvider? {
        return provider
    }

    fun clearProvider() {
        provider = null
    }

    suspend fun setEvaluationContext(evaluationContext: EvaluationContext) {
        getProvider()?.onContextSet(context, evaluationContext)
        // A provider evaluation reading the global ctx at this point would fail due to stale cache.
        // To prevent this, the provider should internally manage the ctx to use on each evaluation, and
        // make sure it's aligned with the values in the cache at all times. If no guarantees are offered by
        // the provider, the application can expect STALE resolves while setting a new global ctx
        context = evaluationContext
    }

    fun getEvaluationContext(): EvaluationContext? {
        return context
    }

    fun getProviderMetadata(): Metadata? {
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
