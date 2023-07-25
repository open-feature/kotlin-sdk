package dev.openfeature.sdk

class NoOpProvider : FeatureProvider {
    override var metadata: ProviderMetadata = NoOpProviderMetadata("No-op provider")
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

    override var hooks: List<Hook<*>> = listOf()
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

    data class NoOpProviderMetadata(override var name: String?) : ProviderMetadata
}