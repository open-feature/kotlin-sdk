package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.EvaluationMetadata
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderMetadata
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class DoSomethingProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = DoSomethingProviderMetadata(),
    private var dispatcher: CoroutineDispatcher = Dispatchers.IO
) : FeatureProvider {
    companion object {
        val evaluationMetadata = EvaluationMetadata.builder()
            .putString("key1", "value1")
            .putInt("key2", 42)
            .build()
    }
    private var eventHandler = EventHandler(dispatcher)

    override fun initialize(initialContext: EvaluationContext?) {
        CoroutineScope(dispatcher).launch {
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
        return ProviderEvaluation(
            value = defaultValue.reversed(),
            metadata = evaluationMetadata
        )
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

    override fun observe(): Flow<OpenFeatureEvents> = eventHandler.observe()

    override fun getProviderStatus(): OpenFeatureEvents = OpenFeatureEvents.ProviderReady

    class DoSomethingProviderMetadata(override val name: String? = "something") : ProviderMetadata
}