package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow

/**
 * Opt-in marker for providers that emit their own lifecycle events.
 *
 * The SDK derives [OpenFeatureStatus] from provider events. For a provider that does not implement this
 * interface the SDK synthesises those events itself once a lifecycle method returns — which leaves a
 * window in which the provider can emit something from background work and have the synthesised event
 * overwrite it. Implementing this interface closes that window: the SDK synthesises nothing and reports
 * exactly what the provider signals, in the order the provider signals it.
 *
 * Note that the provider does not own the status; it owns the *events*. The SDK remains the single place
 * the status is stored and read from, so there is no status accessor to implement here and no
 * requirement to make one safe for concurrent access.
 *
 * Implementations must emit:
 * - [OpenFeatureProviderEvents.ProviderReady] before [initialize] terminates normally
 * - [OpenFeatureProviderEvents.ProviderError] before [initialize] terminates abnormally, with
 *   [dev.openfeature.kotlin.sdk.exceptions.ErrorCode.PROVIDER_FATAL] if the failure is irrecoverable
 * - [OpenFeatureProviderEvents.ProviderReconciling] while [onContextSet] is reconciling, unless it
 *   reconciles synchronously
 * - [OpenFeatureProviderEvents.ProviderContextChanged] when [onContextSet] terminates normally, or
 *   [OpenFeatureProviderEvents.ProviderError] when it terminates abnormally
 *
 * Where [onContextSet] can be invoked again before an earlier invocation has terminated, emit the
 * terminal event only once the last one terminates, so intermediate reconciliations do not surface as
 * spurious updates.
 *
 * [shutdown] is the exception: the SDK initiates it and infers the transition to
 * [OpenFeatureStatus.NotReady] itself, so no event is required.
 *
 * A provider that terminates [initialize] without having emitted anything leaves the SDK with nothing to
 * report, and [OpenFeatureAPIInstance.setProviderAndWait] will wait for the event it was promised.
 */
interface StateManagingProvider : FeatureProvider {
    /**
     * Lifecycle and provider events for this instance.
     *
     * Unlike [FeatureProvider.observe] this has no default: a provider claiming to emit its own
     * lifecycle events needs somewhere to emit them.
     */
    override fun observe(): Flow<OpenFeatureProviderEvents>
}