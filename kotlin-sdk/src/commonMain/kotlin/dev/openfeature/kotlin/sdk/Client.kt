package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow

interface Client : Features, Tracking {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>
    val statusFlow: Flow<OpenFeatureStatus>

    /**
     * Cold flow of events from the SDK's current [FeatureProvider], same pipeline as
     * [OpenFeatureAPI.observe]. To handle a single event type, narrow with
     * [kotlinx.coroutines.flow.filterIsInstance] (or equivalent) in application code.
     */
    fun observe(): Flow<OpenFeatureProviderEvents>

    fun addHooks(hooks: List<Hook<*>>)
}