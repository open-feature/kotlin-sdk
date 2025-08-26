package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

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
    private class ProviderShutdownException(
        providerName: String,
        cause: Throwable
    ) : RuntimeException("Provider '$providerName' shutdown failed: ${cause.message}", cause)

    /**
     * @property name The unique name of the [FeatureProvider] according to this MultiProvider
     */
    class ChildFeatureProvider(
        implementation: FeatureProvider,
        val name: String // Maybe there's a better variable name for this?
    ) : FeatureProvider by implementation

    // TODO: Support hooks
    override val hooks: List<Hook<*>> = emptyList()
    private val childFeatureProviders: List<ChildFeatureProvider> by lazy {
        providers.toChildFeatureProviders()
    }

    // Metadata identifying this as a multiprovider
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = MULTIPROVIDER_NAME
        override val originalMetadata: Map<String, ProviderMetadata> by lazy {
            constructOriginalMetadata()
        }

        private fun constructOriginalMetadata(): Map<String, ProviderMetadata> {
            return childFeatureProviders.associate { it.name to it.metadata }
        }

        override fun toString(): String {
            return mapOf(
                "name" to name,
                "originalMetadata" to originalMetadata
            ).toString()
        }
    }

    private val _statusFlow = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
    val statusFlow = _statusFlow.asStateFlow()

    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    // Track individual provider statuses, initial state of all providers is NotReady
    private val childProviderStatuses: MutableMap<ChildFeatureProvider, OpenFeatureStatus> =
        childFeatureProviders.associateWithTo(mutableMapOf()) { OpenFeatureStatus.NotReady }

    private fun List<FeatureProvider>.toChildFeatureProviders(): List<ChildFeatureProvider> {
        // Extract a stable base name per provider, falling back for unnamed providers
        val providerBaseNames: List<String> = this.map { it.metadata.name ?: UNDEFINED_PROVIDER_NAME }

        // How many times each base name occurs in the inputs
        val baseNameToTotalCount: Map<String, Int> = providerBaseNames.groupingBy { it }.eachCount()

        // Running index per base name used to generate suffixed unique names in order
        val baseNameToNextIndex = mutableMapOf<String, Int>()

        return this.mapIndexed { providerIndex, provider ->
            val baseName = providerBaseNames[providerIndex]
            val occurrencesForBase = baseNameToTotalCount[baseName] ?: 0

            val uniqueChildName = if (occurrencesForBase > 1) {
                val nextIndex = (baseNameToNextIndex[baseName] ?: 0) + 1
                baseNameToNextIndex[baseName] = nextIndex
                "${baseName}_$nextIndex"
            } else {
                baseName
            }

            ChildFeatureProvider(provider, uniqueChildName)
        }
    }

    /**
     * @return Number of unique providers
     */
    fun getProviderCount(): Int = childFeatureProviders.size

    // TODO Add distinctUntilChanged operator once EventDetails have been added
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
            childFeatureProviders.forEach { provider ->
                provider.observe()
                    .onEach { event ->
                        handleProviderEvent(provider, event)
                    }
                    .launchIn(this)
            }

            // State updates captured by observing individual Feature Flag providers
            childFeatureProviders
                .map { async { it.initialize(initialContext) } }
                .awaitAll()
        }
    }

    private suspend fun handleProviderEvent(provider: ChildFeatureProvider, event: OpenFeatureProviderEvents) {
        if (event is OpenFeatureProviderEvents.ProviderConfigurationChanged) {
            eventFlow.emit(event)
            return
        }

        val newChildStatus = when (event) {
            is OpenFeatureProviderEvents.ProviderReady -> OpenFeatureStatus.Ready
            is OpenFeatureProviderEvents.ProviderNotReady -> OpenFeatureStatus.NotReady
            is OpenFeatureProviderEvents.ProviderStale -> OpenFeatureStatus.Stale
            is OpenFeatureProviderEvents.ProviderError ->
                if (event.error is OpenFeatureError.ProviderFatalError) {
                    OpenFeatureStatus.Fatal(event.error)
                } else {
                    OpenFeatureStatus.Error(event.error)
                }

            else -> error("Unexpected event $event")
        }

        val previousStatus = _statusFlow.value
        childProviderStatuses[provider] = newChildStatus
        val newStatus = calculateAggregateStatus()

        if (previousStatus != newStatus) {
            _statusFlow.update { newStatus }
            // Re-emit the original event that triggered the aggregate status change
            eventFlow.emit(event)
        }
    }

    private fun calculateAggregateStatus(): OpenFeatureStatus {
        val highestPrecedenceStatus = childProviderStatuses.values.maxBy(::precedence)
        return highestPrecedenceStatus
    }

    private fun precedence(status: OpenFeatureStatus): Int {
        return when (status) {
            is OpenFeatureStatus.Fatal -> 5
            is OpenFeatureStatus.NotReady -> 4
            is OpenFeatureStatus.Error -> 3
            is OpenFeatureStatus.Reconciling -> 2 // Not specified in precedence; treat similar to Stale
            is OpenFeatureStatus.Stale -> 2
            is OpenFeatureStatus.Ready -> 1
        }
    }

    /**
     * Shuts down all underlying providers.
     * This allows providers to clean up resources and complete any pending operations.
     */
    override fun shutdown() {
        val shutdownErrors = mutableListOf<Pair<String, Throwable>>()
        childFeatureProviders.forEach { provider ->
            try {
                provider.shutdown()
            } catch (t: Throwable) {
                shutdownErrors += provider.name to t
            }
        }

        if (shutdownErrors.isNotEmpty()) {
            val message = buildString {
                append("One or more providers failed to shutdown: ")
                append(
                    shutdownErrors.joinToString(separator = "\n") { (name, err) ->
                        "$name: ${err.message}"
                    }
                )
            }

            val aggregate = OpenFeatureError.GeneralError(message)
            shutdownErrors.forEach { (name, err) ->
                aggregate.addSuppressed(ProviderShutdownException(name, err))
            }
            throw aggregate
        }
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        coroutineScope {
            // If any of these fail, they should individually bubble up their fail
            // event and that is handled by handleProviderEvent()
            childFeatureProviders
                .map { async { it.onContextSet(oldContext, newContext) } }
                .awaitAll()
        }
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        return strategy.evaluate(
            childFeatureProviders,
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
            childFeatureProviders,
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
            childFeatureProviders,
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
            childFeatureProviders,
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
            childFeatureProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getObjectEvaluation
        )
    }

    companion object {
        private const val MULTIPROVIDER_NAME = "multiprovider"
        private const val UNDEFINED_PROVIDER_NAME = "<unnamed>"
    }
}