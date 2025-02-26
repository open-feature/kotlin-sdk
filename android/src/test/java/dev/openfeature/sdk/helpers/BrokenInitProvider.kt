package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderMetadata
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.OpenFeatureError
import dev.openfeature.sdk.exceptions.OpenFeatureError.FlagNotFoundError

class BrokenInitProvider(
    override var hooks: List<Hook<*>> = listOf(),
    override var metadata: ProviderMetadata = AlwaysBrokenProviderMetadata()
) :
    FeatureProvider {
    override suspend fun initialize(initialContext: EvaluationContext?) {
        throw OpenFeatureError.ProviderNotReadyError("test error from $this")
    }

    override fun shutdown() {
        // no-op
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // this works
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        throw FlagNotFoundError(key)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        throw FlagNotFoundError(key)
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        throw FlagNotFoundError(key)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        throw FlagNotFoundError(key)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        throw FlagNotFoundError(key)
    }

    class AlwaysBrokenProviderMetadata(override val name: String? = "test") : ProviderMetadata
}