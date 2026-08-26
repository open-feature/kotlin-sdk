package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow

interface Client : Features, Tracking {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>

    /**
     * A [Flow] that emits the initial [OpenFeatureStatus] and all subsequent state transitions
     * of the Provider handling this client's evaluations. This enables reactive observation
     * of the provider's lifecycle.
     */
    val statusFlow: Flow<OpenFeatureStatus>

    /**
     * Cold flow of events from the SDK's current [FeatureProvider], same pipeline as
     * [OpenFeatureAPI.observe]. To handle a single event type, narrow with
     * [kotlinx.coroutines.flow.filterIsInstance] (or equivalent) in application code.
     */
    fun observe(): Flow<OpenFeatureProviderEvents>

    fun addHooks(hooks: List<Hook<*>>)

    /**
     * The current status of the provider handling this client's evaluations.
     *
     * @return the current [OpenFeatureStatus], or [OpenFeatureStatus.NotReady] if no provider has
     * been initialized.
     */
    val providerStatus: OpenFeatureStatus
        get() = OpenFeatureStatus.NotReady
}