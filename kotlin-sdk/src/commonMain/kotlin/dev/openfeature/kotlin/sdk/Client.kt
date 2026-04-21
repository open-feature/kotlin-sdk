package dev.openfeature.kotlin.sdk

import kotlinx.coroutines.flow.Flow

interface Client : Features, Tracking {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>
    val statusFlow: Flow<OpenFeatureStatus>

    fun addHooks(hooks: List<Hook<*>>)

    /**
     * Get the current [OpenFeatureStatus] of the Provider handling this client's evaluations, or [OpenFeatureStatus.NotReady] if no Provider has been initialized.
     */
    fun getProviderStatus(): OpenFeatureStatus = OpenFeatureStatus.NotReady
}