package dev.openfeature.sdk

import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import kotlinx.coroutines.CoroutineDispatcher

class TestFeatureProvider(
    dispatcher: CoroutineDispatcher,
    private val eventHandler: EventHandler
) : FeatureProvider {
    override val hooks: List<Hook<*>>
        get() = TODO("Not yet implemented")
    override val metadata: ProviderMetadata
        get() = TODO("Not yet implemented")

    override fun initialize(initialContext: EvaluationContext?) {
        TODO("Not yet implemented")
    }

    override fun shutdown() {
        eventHandler.publish(OpenFeatureEvents.ProviderNotReady)
    }

    override fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        TODO("Not yet implemented")
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        TODO("Not yet implemented")
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        TODO("Not yet implemented")
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        TODO("Not yet implemented")
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        TODO("Not yet implemented")
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        TODO("Not yet implemented")
    }

    override fun observe() = eventHandler.observe()

    override fun getProviderStatus(): OpenFeatureEvents = eventHandler.getProviderStatus()

    fun emitReady() {
        eventHandler.publish(OpenFeatureEvents.ProviderReady)
    }

    fun emitStale() {
        eventHandler.publish(OpenFeatureEvents.ProviderStale)
    }

    fun emitError(exception: Exception) {
        eventHandler.publish(OpenFeatureEvents.ProviderError(exception))
    }
}