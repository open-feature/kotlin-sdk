package dev.openfeature.sdk

import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.CoroutineDispatcher

@Suppress("TooManyFunctions")
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

    inline fun <reified T : OpenFeatureEvents> observeEvents(dispatcher: CoroutineDispatcher) =
        EventHandler.eventsObserver(dispatcher)
            .observe<T>()

    fun clearProvider() {
        provider = null
    }

    fun setEvaluationContext(evaluationContext: EvaluationContext) {
        val oldContext = context
        context = evaluationContext
        getProvider()?.onContextSet(oldContext, evaluationContext)
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