package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.*
import dev.openfeature.sdk.exceptions.OpenFeatureError.FlagNotFoundError

class AlwaysBrokenProvider(override var hooks: List<Hook<*>> = listOf(), override var metadata: Metadata = AlwaysBrokenMetadata()) : FeatureProvider {
    override suspend fun initialize(initialContext: EvaluationContext?) {
        // no-op
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // no-op
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean
    ): ProviderEvaluation<Boolean> {
        throw FlagNotFoundError(key)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String
    ): ProviderEvaluation<String> {
        throw FlagNotFoundError(key)
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int
    ): ProviderEvaluation<Int> {
        throw FlagNotFoundError(key)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double
    ): ProviderEvaluation<Double> {
        throw FlagNotFoundError(key)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value
    ): ProviderEvaluation<Value> {
        throw FlagNotFoundError(key)
    }

    class AlwaysBrokenMetadata(override var name: String? = "test") : Metadata
}