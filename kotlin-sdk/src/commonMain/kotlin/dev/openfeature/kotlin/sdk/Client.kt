package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

interface Client : Features, Tracking {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>
    val statusFlow: Flow<OpenFeatureStatus>

    /**
     * Cold flow of events from the SDK's current [FeatureProvider], same pipeline as
     * [OpenFeatureAPI.observe]. For a single event type, use the [observe] extension
     * with a reified type argument (e.g. `observe<ProviderReady>().collect { ... }`).
     */
    fun observe(): Flow<OpenFeatureProviderEvents>

    fun addHooks(hooks: List<Hook<*>>)
}

/**
 * Observe provider events of type [T], matching [OpenFeatureAPI.observe].
 */
@OptIn(ExperimentalCoroutinesApi::class)
inline fun <reified T : OpenFeatureProviderEvents> Client.observe(): Flow<T> =
    observe().filterIsInstance()