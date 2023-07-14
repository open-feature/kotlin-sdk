package dev.openfeature.sdk

interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    // Called by OpenFeatureAPI whenever the new Provider is registered
    suspend fun initialize(initialContext: EvaluationContext?)

    // Called by OpenFeatureAPI whenever a new EvaluationContext is set by the application
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)
    fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean>

    fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?): ProviderEvaluation<String>
    fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?): ProviderEvaluation<Int>
    fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?): ProviderEvaluation<Double>
    fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?): ProviderEvaluation<Value>
}