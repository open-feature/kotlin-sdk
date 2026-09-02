package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.EvaluationMetadata
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.ProviderStatusTracker
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

open class DoSomethingProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = DoSomethingProviderMetadata()
) : FeatureProvider {
    protected val statusTracker = ProviderStatusTracker()

    override val status: OpenFeatureStatus get() = statusTracker.status
    companion object {
        val evaluationMetadata = EvaluationMetadata.builder()
            .putString("key1", "value1")
            .putInt("key2", 42)
            .build()
    }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        delay(1000)
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        statusTracker.reset()
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) = statusTracker.reconciling {
        delay(500)
        statusTracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
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

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
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

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()
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
        statusTracker.send(OpenFeatureProviderEvents.ProviderStale())
        statusTracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
    }

    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    ) {
        super.track(trackingEventName, context, details)
        statusTracker.send(OpenFeatureProviderEvents.ProviderStale())
        statusTracker.send(OpenFeatureProviderEvents.ProviderStale())
        statusTracker.send(OpenFeatureProviderEvents.ProviderStale())
    }
}