package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.cancellation.CancellationException

interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    /**
     * Called by OpenFeatureAPI whenever the new Provider is registered.
     *
     * Implementations must signal their initial state by emitting
     * [OpenFeatureProviderEvents.ProviderReady] (on success) or
     * [OpenFeatureProviderEvents.ProviderError] (on error) via
     * [observe] before or after this function returns.
     *
     * The SDK waits for the first non-NotReady status derived from these events before considering
     * initialization complete, so skipping the [observe] emission will cause [OpenFeatureAPI.setProviderAndWait]
     * to hang indefinitely.
     *
     * @param initialContext any initial context to be set before the provider is ready
     */
    @Throws(OpenFeatureError::class, CancellationException::class)
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
    @Throws(OpenFeatureError::class, CancellationException::class)
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

    /**
     * Exposes a stream of lifecycle events from this provider.
     *
     * Implementations must emit [OpenFeatureProviderEvents.ProviderReady] or
     * [OpenFeatureProviderEvents.ProviderError] to signal the outcome of
     * [initialize]. The SDK derives its status exclusively from these emissions;
     * omitting them will leave the SDK in the [OpenFeatureStatus.NotReady] state.
     *
     * All emissions (e.g. [OpenFeatureProviderEvents.ProviderStale],
     * [OpenFeatureProviderEvents.ProviderConfigurationChanged]) are
     * forwarded to application-level observers via [OpenFeatureAPI.observe].
     */
    fun observe(): Flow<OpenFeatureProviderEvents> {
        return emptyFlow()
    }
}