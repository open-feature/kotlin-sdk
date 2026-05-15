package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Provider that owns lifecycle [OpenFeatureStatus] in [status] and reports changes on [observe].
 *
 * Implementations must update [status] first, then emit on [observe], so readers of either see a
 * consistent order.
 */
interface StateManagingProvider : FeatureProvider {
    /**
     * Holds the current [OpenFeatureStatus]. The SDK reads this [StateFlow] directly; for each
     * lifecycle step, update [status], then emit the matching event on [observe].
     */
    val status: StateFlow<OpenFeatureStatus>

    /**
     * Called by [OpenFeatureAPI] when the provider is registered.
     *
     * Update [status] first, then emit [OpenFeatureProviderEvents.ProviderReady] or
     * [OpenFeatureProviderEvents.ProviderError] on [observe]. The SDK waits until [status] is neither
     * [OpenFeatureStatus.NotReady] nor [OpenFeatureStatus.Reconciling]. Failing to update [status] after
     * initialization (so the wait never ends) is a provider bug; the SDK does not time out. Consumers of
     * [OpenFeatureAPI] may apply their own deadlines (for example [kotlinx.coroutines.withTimeout]) if
     * they need one.
     *
     * @param initialContext optional initial [EvaluationContext]
     */
    @Throws(OpenFeatureError::class, CancellationException::class)
    override suspend fun initialize(initialContext: EvaluationContext?)

    /**
     * Called when the client lifecycle ends; release resources and threads.
     *
     * Before returning: set [status] to [OpenFeatureStatus.NotReady], then emit
     * [OpenFeatureProviderEvents.ProviderError] on [observe] with [ErrorCode.PROVIDER_NOT_READY] in
     * [OpenFeatureProviderEvents.EventDetails.errorCode].
     */
    override fun shutdown()

    /**
     * Called by [OpenFeatureAPI] when the [EvaluationContext] changes.
     *
     * Reconcile as needed. For each transition, update [status] first, then emit the matching event
     * on [observe] (for example [OpenFeatureStatus.Reconciling], then [OpenFeatureStatus.Ready] or
     * [OpenFeatureStatus.Error]).
     *
     * @param oldContext previous context, if any
     * @param newContext new context
     * @throws OpenFeatureError if the update cannot complete
     */
    @Throws(OpenFeatureError::class, CancellationException::class)
    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext)

    /**
     * Lifecycle and provider events for this instance.
     *
     * For each step, update [status] first, then emit the matching [OpenFeatureProviderEvents] on this
     * flow; required pairings for [initialize], [shutdown], and [onContextSet] are described on those
     * methods.
     */
    override fun observe(): Flow<OpenFeatureProviderEvents>
}