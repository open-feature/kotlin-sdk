package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * MultiProvider is a FeatureProvider implementation that delegates flag evaluations
 * to multiple underlying providers using a configurable strategy.
 *
 * This class acts as a composite provider that can:
 * - Combine multiple feature providers into a single interface
 * - Apply different evaluation strategies (FirstMatch, FirstSuccessful, etc.)
 * - Manage lifecycle events for all underlying providers
 * - Forward context changes to all providers
 *
 * @param providers List of FeatureProvider instances to delegate to
 * @param strategy Strategy to use for combining provider results (defaults to FirstMatchStrategy)
 */
class MultiProvider(
    providers: List<FeatureProvider>,
    private val strategy: Strategy = FirstMatchStrategy()
) : FeatureProvider {
    // Metadata identifying this as a multiprovider
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = "multiprovider"
    }

    // TODO: Support hooks
    override val hooks: List<Hook<*>> = emptyList()
    private val uniqueProviders = getUniqueSetOfProviders(providers)

    // Shared flow because we don't want the distinct operator since it would break consecutive emits of
    // ProviderConfigurationChanged
    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5).apply {
        tryEmit(OpenFeatureProviderEvents.ProviderError(OpenFeatureError.ProviderNotReadyError()))
    }

    // Track individual provider statuses
    private val providerStatuses = mutableMapOf<FeatureProvider, OpenFeatureProviderEvents>()

    // Event precedence (highest to lowest priority) - based on the specifications
    private val eventPrecedence = mapOf(
        OpenFeatureProviderEvents.ProviderError::class to 4, // FATAL/ERROR
        OpenFeatureProviderEvents.ProviderNotReady::class to 3, // NOT READY, Deprecated but still supporting
        OpenFeatureProviderEvents.ProviderStale::class to 2, // STALE
        OpenFeatureProviderEvents.ProviderReady::class to 1 // READY
        // ProviderConfigurationChanged doesn't affect status, so not included
    )

    private fun getUniqueSetOfProviders(providers: List<FeatureProvider>): List<FeatureProvider> {
        val setOfProviderNames = mutableSetOf<String>()
        val uniqueProviders = mutableListOf<FeatureProvider>()
        providers.forEach { currProvider ->
            val providerName = currProvider.metadata.name
            if (setOfProviderNames.add(providerName.orEmpty())) {
                uniqueProviders.add(currProvider)
            } else {
                println("Duplicate provider with name $providerName found") // Log error, no logging tool
            }
        }

        return uniqueProviders
    }

    /**
     * @return Number of unique providers
     */
    fun getProviderCount(): Int = uniqueProviders.size

    override fun observe(): Flow<OpenFeatureProviderEvents> = eventFlow.asSharedFlow()

    /**
     * Initializes all underlying providers with the given context.
     * This ensures all providers are ready before any evaluations occur.
     *
     * @param initialContext Optional evaluation context to initialize providers with
     */
    override suspend fun initialize(initialContext: EvaluationContext?) {
        coroutineScope {
            // Listen to events emitted by providers to emit our own set of events
            // according to https://openfeature.dev/specification/appendix-a/#status-and-event-handling
            uniqueProviders.forEach { provider ->
                provider.observe()
                    .onEach { event ->
                        handleProviderEvent(provider, event)
                    }
                    .launchIn(this)
            }

            // State updates captured by observing individual Feature Flag providers
            uniqueProviders
                .map { async { it.initialize(initialContext) } }
                .awaitAll()
        }
    }

    private suspend fun handleProviderEvent(provider: FeatureProvider, event: OpenFeatureProviderEvents) {
        val hasStatusUpdated = updateProviderStatus(provider, event)

        // This event should be re-emitted any time it occurs from any provider.
        if (event is OpenFeatureProviderEvents.ProviderConfigurationChanged) {
            eventFlow.emit(event)
            return
        }

        // If the status has been updated, calculate what our new event should be
        if (hasStatusUpdated) {
            val currPrecedenceVal = eventFlow.replayCache.firstOrNull()?.run { eventPrecedence[this::class] } ?: 0
            val updatedPrecedenceVal = eventPrecedence[event::class] ?: 0

            if (updatedPrecedenceVal > currPrecedenceVal) {
                eventFlow.emit(event)
            }
        }
    }

    /**
     * @return true if the status has been updated to a different value, false otherwise
     */
    private fun updateProviderStatus(provider: FeatureProvider, newStatus: OpenFeatureProviderEvents): Boolean {
        val oldStatus = providerStatuses[provider]
        providerStatuses[provider] = newStatus

        return oldStatus != newStatus
    }

    /**
     * Shuts down all underlying providers.
     * This allows providers to clean up resources and complete any pending operations.
     */
    override fun shutdown() {
        uniqueProviders.forEach { it.shutdown() }
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        uniqueProviders.forEach { it.onContextSet(oldContext, newContext) }
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        return strategy.evaluate(
            uniqueProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getBooleanEvaluation
        )
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        return strategy.evaluate(
            uniqueProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getStringEvaluation
        )
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        return strategy.evaluate(
            uniqueProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getIntegerEvaluation
        )
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        return strategy.evaluate(
            uniqueProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getDoubleEvaluation
        )
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        return strategy.evaluate(
            uniqueProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getObjectEvaluation
        )
    }
}