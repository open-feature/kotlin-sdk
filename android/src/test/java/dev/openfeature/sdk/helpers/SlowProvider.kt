package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderMetadata
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class SlowProvider(override val hooks: List<Hook<*>> = listOf(), private var dispatcher: CoroutineDispatcher) : FeatureProvider {
    override val metadata: ProviderMetadata = SlowProviderMetadata("Slow provider")
    private var ready = false
    private var eventHandler = EventHandler(dispatcher)
    override fun initialize(initialContext: EvaluationContext?) {
        CoroutineScope(dispatcher).launch {
            delay(10000)
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
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(!defaultValue)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(defaultValue.reversed())
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
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