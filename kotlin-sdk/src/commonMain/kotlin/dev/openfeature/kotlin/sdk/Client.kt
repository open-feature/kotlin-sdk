package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

interface Client : Features, Tracking {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>
    val statusFlow: Flow<OpenFeatureStatus>

    fun addHooks(hooks: List<Hook<*>>)

    /**
     * Provide a flow of Provider events, respecting the dynamic binding of this client via its domain.
     */
    fun observeEvents(): Flow<OpenFeatureProviderEvents>
}

/**
 * Observe a specific event type from the provider associated with this client's domain.
 */
inline fun <reified T : OpenFeatureProviderEvents> Client.observe(): Flow<T> =
    observeEvents().filterIsInstance<T>()