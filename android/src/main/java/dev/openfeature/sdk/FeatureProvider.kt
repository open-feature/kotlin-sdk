package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.OpenFeatureError
import kotlin.jvm.Throws

interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    /**
     * Called by OpenFeatureAPI whenever the new Provider is registered
     * This function should block until ready and throw exceptions if it fails to initialize
     * @param initialContext any initial context to be set before the provider is ready
     */
    @Throws(OpenFeatureError::class)
    suspend fun initialize(initialContext: EvaluationContext?)

    /**
     * Called when the lifecycle of the OpenFeatureClient is over to release resources/threads
     */
    fun shutdown()

    /**
     * Called by OpenFeatureAPI whenever a new EvaluationContext is set by the application
     * Perform blocking work here until the provider is ready again or throws an exception
     * @param oldContext The old EvaluationContext
     * @param newContext The new EvaluationContext
     * @throws OpenFeatureError if the provider cannot perform the task
     */
    @Throws(OpenFeatureError::class)
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
        // an empty default implementation to make implementing this functionality optional
    }
}