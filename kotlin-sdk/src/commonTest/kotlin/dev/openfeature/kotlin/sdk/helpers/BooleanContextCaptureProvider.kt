package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value

/** Records the [EvaluationContext] passed to the last boolean evaluation. */
class BooleanContextCaptureProvider(
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = "capture"
    }
) : FeatureProvider {
    override val hooks: List<Hook<*>> = emptyList()

    var lastBooleanContext: EvaluationContext? = null
        private set

    override suspend fun initialize(initialContext: EvaluationContext?) {}

    override fun shutdown() {}

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {}

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        lastBooleanContext = context
        return ProviderEvaluation(!defaultValue)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = throw UnsupportedOperationException()

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = throw UnsupportedOperationException()

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = throw UnsupportedOperationException()

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = throw UnsupportedOperationException()
}
