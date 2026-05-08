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

    /**
     * Set the [FeatureProvider] for this client's domain. This method will return immediately and initialize the provider in a coroutine scope.
     * @param provider the provider to set
     * @param dispatcher the dispatcher to use for the provider initialization coroutine
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization
     */
    fun setProvider(
        provider: FeatureProvider,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
        initialContext: EvaluationContext? = null
    )

    /**
     * Set the [FeatureProvider] for this client's domain. This method will block until the provider is initialized.
     * @param provider the provider to set
     * @param initialContext the initial [EvaluationContext] to use for the provider initialization
     * @param dispatcher the dispatcher to use for the provider initialization coroutine
     */
    suspend fun setProviderAndWait(
        provider: FeatureProvider,
        initialContext: EvaluationContext? = null,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
    )
}

/**
 * Observe a specific event type from the provider associated with this client's domain.
 */
inline fun <reified T : OpenFeatureProviderEvents> Client.observe(): Flow<T> =
    observeEvents().filterIsInstance<T>()