package dev.openfeature.sdk

import dev.openfeature.sdk.events.EventObserver
import dev.openfeature.sdk.events.ProviderStatus

interface FeatureProvider : EventObserver, ProviderStatus {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    // Called by OpenFeatureAPI whenever the new Provider is registered
    // This function should never throw
    fun initialize(initialContext: EvaluationContext?)

    // Called when the lifecycle of the OpenFeatureClient is over
    // to release resources/threads
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

    /**
     * Feature provider implementations can opt in for to support Tracking by implementing this method.
     *
     * Performs tracking of a particular action or application state.
     *
     * @param trackingEventName Event name to track
     * @param context   Evaluation context used in flag evaluation (Optional)
     * @param details   Data pertinent to a particular tracking event (Optional)
     */
    fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?) {
    }
}