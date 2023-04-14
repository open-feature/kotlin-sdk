package dev.openfeature.sdk

interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: Metadata

    // Called by OpenFeatureAPI whenever the new Provider is registered
    suspend fun initialize(initialContext: EvaluationContext?)
    // Called by OpenFeatureAPI whenever a new EvaluationContext is set by the application
    suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)
    fun getBooleanEvaluation(key: String, defaultValue: Boolean): ProviderEvaluation<Boolean>
    fun getStringEvaluation(key: String, defaultValue: String): ProviderEvaluation<String>
    fun getIntegerEvaluation(key: String, defaultValue: Int): ProviderEvaluation<Int>
    fun getDoubleEvaluation(key: String, defaultValue: Double): ProviderEvaluation<Double>
    fun getObjectEvaluation(key: String, defaultValue: Value): ProviderEvaluation<Value>
}