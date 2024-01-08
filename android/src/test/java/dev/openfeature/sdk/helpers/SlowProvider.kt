package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.*
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class SlowProvider(override val hooks: List<Hook<*>> = listOf(), private var dispatcher: CoroutineDispatcher) : FeatureProvider {
    override val metadata: ProviderMetadata = SlowProviderMetadata("Slow provider")
    private var ready = false
    private var eventHandler = EventHandler(dispatcher)
    override fun initialize(initialContext: EvaluationContext?) {
        CoroutineScope(dispatcher).launch {
            Thread.sleep(2000) // TODO Improve without sleep
            ready = true
            eventHandler.publish(OpenFeatureEvents.ProviderReady)
        }
    }

    override fun shutdown() {
        // no-op
    }

    override fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // no-op
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        return ProviderEvaluation(!defaultValue)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        return ProviderEvaluation(defaultValue.reversed())
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        return ProviderEvaluation(Value.Null)
    }

    override fun observe(): Flow<OpenFeatureEvents> = flowOf()

    override fun getProviderStatus(): OpenFeatureEvents = if (ready) {
        OpenFeatureEvents.ProviderReady
    } else {
        OpenFeatureEvents.ProviderStale
    }

    data class SlowProviderMetadata(override val name: String?) : ProviderMetadata
}