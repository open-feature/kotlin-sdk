package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.EvaluationMetadata
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderMetadata
import dev.openfeature.sdk.TrackingEventDetails
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

open class DoSomethingProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = DoSomethingProviderMetadata()
) : FeatureProvider {
    protected val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)
    companion object {
        val evaluationMetadata = EvaluationMetadata.builder()
            .putString("key1", "value1")
            .putInt("key2", 42)
            .build()
    }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        delay(1000)
        events.emit(OpenFeatureProviderEvents.ProviderReady)
    }

    override fun shutdown() {
        // no-op
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        delay(500)
        events.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged)
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

    class DoSomethingProviderMetadata(override val name: String? = "something") : ProviderMetadata

    override fun observe(): Flow<OpenFeatureProviderEvents> {
        return events
    }
}

class OverlyEmittingProvider(name: String) : DoSomethingProvider(
    metadata = object : ProviderMetadata {
        override val name: String = name
    }
) {
    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        events.emit(OpenFeatureProviderEvents.ProviderStale)
        events.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged)
    }

    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    ) {
        super.track(trackingEventName, context, details)
        events.tryEmit(OpenFeatureProviderEvents.ProviderStale)
        events.tryEmit(OpenFeatureProviderEvents.ProviderStale)
        events.tryEmit(OpenFeatureProviderEvents.ProviderStale)
    }
}