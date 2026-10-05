package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The interface implemented by upstream flag providers to resolve flags for their service.
 *
 * A provider is responsible for its own [status] and for emitting the events that explain it, which
 * [ProviderStatusTracker] does on its behalf. The README carries a worked example.
 */
interface FeatureProvider {
    val hooks: List<Hook<*>>
    val metadata: ProviderMetadata

    /**
     * The current lifecycle status of this provider, kept up to date by the provider alone. It must
     * be [OpenFeatureStatus.NotReady] before [initialize] is called, must reflect the most recently
     * emitted event thereafter, and must be thread-safe: the SDK reads it from flag evaluation paths
     * on any thread.
     */
    val status: OpenFeatureStatus

    /**
     * Called by OpenFeatureAPI when this provider is registered, to do whatever asynchronous setup
     * it needs.
     *
     * Emit at least one event before returning, so that [status] moves away from
     * [OpenFeatureStatus.NotReady] — usually [OpenFeatureProviderEvents.ProviderReady] or
     * [OpenFeatureProviderEvents.ProviderError]. Throwing does not set a status: the SDK logs the
     * failure and a provider that throws without emitting stays [OpenFeatureStatus.NotReady].
     *
     * Lifecycle calls are entered in the order they were made, but the SDK does not wait for one to
     * finish before entering the next.
     *
     * @param initialContext any initial context to be set before the provider is ready
     */
    @Throws(OpenFeatureError::class, CancellationException::class)
    suspend fun initialize(initialContext: EvaluationContext?)

    /**
     * Called when the lifecycle of the OpenFeatureClient is over to release resources/threads.
     *
     * A provider that can be registered again must return to [OpenFeatureStatus.NotReady] here, so
     * a reused instance does not report the status it held before it was shut down.
     */
    fun shutdown()

    /**
     * Called by OpenFeatureAPI whenever the application sets the [EvaluationContext], including when
     * the new context is equal to or the same instance as the previous context.
     *
     * Either return without emitting anything, where no reconciliation is needed, or emit
     * [OpenFeatureProviderEvents.ProviderReconciling], do the work, and emit
     * [OpenFeatureProviderEvents.ProviderContextChanged] or
     * [OpenFeatureProviderEvents.ProviderError]. [ProviderStatusTracker.reconciling] does the
     * latter, including collapsing overlapping invocations.
     *
     * This can be entered while previous reconciliation work is still in flight; a provider
     * reconciling asynchronously should handle that, for instance by cancelling the work it
     * supersedes.
     *
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
    fun getLongEvaluation(key: String, defaultValue: Long, context: EvaluationContext?): ProviderEvaluation<Long>
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
     * The events this provider emits, for the SDK and the application. The SDK derives nothing from
     * a provider's silence: every status transition must arrive here.
     */
    fun observe(): Flow<OpenFeatureProviderEvents>
}