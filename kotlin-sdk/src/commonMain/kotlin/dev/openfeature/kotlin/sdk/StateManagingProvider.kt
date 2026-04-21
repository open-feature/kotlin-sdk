package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Provider that owns lifecycle [OpenFeatureStatus] in [status] and reports changes on [observe].
 *
 * Implementations must update [status] first, then emit on [observe], so readers of either see a
 * consistent order. Plain [FeatureProvider] implementations are wrapped internally; their status is
 * derived from [observe] via an SDK adapter.
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
     * [OpenFeatureProviderEvents.ProviderError] on [observe].
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
     * Provider lifecycle events. Replace [FeatureProvider.observe]'s empty default.
     *
     * Implementations update [status] before each matching emission. For [initialize], set [status]
     * first, then emit [OpenFeatureProviderEvents.ProviderReady] or
     * [OpenFeatureProviderEvents.ProviderError], or the SDK remains [OpenFeatureStatus.NotReady]. Other
     * events surface through [OpenFeatureAPI.observe].
     */
    override fun observe(): Flow<OpenFeatureProviderEvents>
}