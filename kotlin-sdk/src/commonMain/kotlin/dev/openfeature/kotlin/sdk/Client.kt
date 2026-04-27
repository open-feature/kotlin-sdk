package dev.openfeature.kotlin.sdk

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

    fun addHooks(hooks: List<Hook<*>>)

    /**
     * Get the current [OpenFeatureStatus] of the Provider handling this client's evaluations, or [OpenFeatureStatus.NotReady] if no Provider has been initialized.
     */
    val providerStatus: OpenFeatureStatus
        get() = OpenFeatureStatus.NotReady
}