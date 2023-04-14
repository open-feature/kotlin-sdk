package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.*

class DoSomethingProvider(override val hooks: List<Hook<*>> = listOf(), override val metadata: Metadata = DoSomethingMetadata()) : FeatureProvider {
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
        return ProviderEvaluation(!defaultValue)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String
    ): ProviderEvaluation<String> {
        return ProviderEvaluation(defaultValue.reversed())
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int
    ): ProviderEvaluation<Int> {
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double
    ): ProviderEvaluation<Double> {
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value
    ): ProviderEvaluation<Value> {
        return ProviderEvaluation(Value.Null)
    }
    class DoSomethingMetadata(override var name: String? = "something") : Metadata
}
