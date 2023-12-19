package dev.openfeature.sdk

import dev.openfeature.sdk.events.EventObserver
import dev.openfeature.sdk.events.ProviderStatus

interface FeatureProvider : EventObserver, ProviderStatus {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    // Called by OpenFeatureAPI whenever the new Provider is registered
    fun initialize(initialContext: EvaluationContext?)

    // called when the lifecycle of the OpenFeatureClient is over
    // to release resources/threads.
    fun shutdown()

    // Called by OpenFeatureAPI whenever a new EvaluationContext is set by the application
    fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)
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