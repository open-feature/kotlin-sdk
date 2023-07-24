package dev.openfeature.sdk

object OpenFeatureAPI {
    private var provider: FeatureProvider? = null
    private var context: EvaluationContext? = null
    var hooks: List<Hook<*>> = listOf()
        private set

    fun setProvider(provider: FeatureProvider, initialContext: EvaluationContext? = null) {
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

    fun setEvaluationContext(evaluationContext: EvaluationContext) {
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

    fun shutdown() {
        provider?.shutdown()
    }
}