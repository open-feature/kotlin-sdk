package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import kotlinx.atomicfu.atomic

/**
 * Minimal **legacy** [FeatureProvider] for tests that exercise the
 * SDK adapter path: [observe] defaults to empty flow; after successful
 * [initialize], the SDK sets readiness.
 */
class LegacyMinimalProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = "legacy-minimal"
    }
) : FeatureProvider {
    val shutdownCalls = atomic(0)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        // Legacy: SDK adapter sets OpenFeatureStatus.Ready after this returns successfully.
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {}

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> = ProviderEvaluation(defaultValue)

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = ProviderEvaluation(defaultValue)

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = ProviderEvaluation(defaultValue)

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> = ProviderEvaluation(defaultValue)

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = ProviderEvaluation(defaultValue)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = ProviderEvaluation(defaultValue)
}