package dev.openfeature.sdk

import dev.openfeature.sdk.events.OpenFeatureEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class NoOpProvider(override val hooks: List<Hook<*>> = listOf()) : FeatureProvider {
    override val metadata: ProviderMetadata = NoOpProviderMetadata("No-op provider")
    override fun initialize(initialContext: EvaluationContext?) {
        // no-op
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
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun observe(): Flow<OpenFeatureEvents> = flowOf()

    override fun getProviderStatus(): OpenFeatureEvents = OpenFeatureEvents.ProviderReady

    data class NoOpProviderMetadata(override val name: String?) : ProviderMetadata
}