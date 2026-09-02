package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.ProviderStatusTracker
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Type alias for a function that evaluates a feature flag using a FeatureProvider.
 * This represents an extension function on FeatureProvider that takes:
 * - key: The feature flag key to evaluate
 * - defaultValue: The default value to return if evaluation fails
 * - evaluationContext: Optional context for the evaluation
 * Returns a ProviderEvaluation containing the result
 */
typealias FlagEval<T> =
    FeatureProvider.(key: String, defaultValue: T, evaluationContext: EvaluationContext?) -> ProviderEvaluation<T>

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

    /**
     * Strategy interface defines how multiple feature providers should be evaluated
     * to determine the final result for a feature flag evaluation.
     * Different strategies can implement different logic for combining or selecting
     * results from multiple providers.
     */
    interface Strategy {
        /**
         * Evaluates a feature flag across multiple providers using the strategy's logic.
         * @param providers List of FeatureProvider instances to evaluate against
         * @param key The feature flag key to evaluate
         * @param defaultValue The default value to use if evaluation fails or no providers match
         * @param evaluationContext Optional context containing additional data for evaluation
         * @param flagEval Function reference to the specific evaluation method to call on each provider
         * @return ProviderEvaluation<T> containing the final evaluation result
         */
        fun <T> evaluate(
            providers: List<FeatureProvider>,
            key: String,
            defaultValue: T,
            evaluationContext: EvaluationContext?,
            flagEval: FlagEval<T>
        ): ProviderEvaluation<T>

        /**
         * Aggregates the statuses of [providers] into the MultiProvider's own status.
         *
         * The default reports the most severe, which is the order the specification's Multi-Provider
         * appendix defines: Fatal, then NotReady, then Error, then Stale, then Ready.
         */
        fun status(providers: List<FeatureProvider>): OpenFeatureStatus =
            providers.map { it.status }.maxByOrNull { it.severity } ?: OpenFeatureStatus.NotReady
    }

    // TODO: Support hooks
    override val hooks: List<Hook<*>> = emptyList()
    private val childFeatureProviders: List<ChildFeatureProvider> by lazy {
        providers.toChildFeatureProviders()
    }

    // Metadata identifying this as a multiprovider
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = MULTIPROVIDER_NAME
        override val originalMetadata: Map<String, ProviderMetadata> by lazy {
            childFeatureProviders.associate { it.name to it.metadata }
        }

        override fun toString(): String {
            return mapOf(
                "name" to name,
                "originalMetadata" to originalMetadata
            ).toString()
        }
    }

    private val statusTracker = ProviderStatusTracker()

    override val status: OpenFeatureStatus get() = statusTracker.status

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

    private var watchScope: CoroutineScope? = null

    /**
     * @return Number of unique providers
     */
    internal fun getProviderCount(): Int = childFeatureProviders.size

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    /**
     * Initializes all underlying providers with the given context.
     * This ensures all providers are ready before any evaluations occur.
     *
     * @param initialContext Optional evaluation context to initialize providers with
     */
    override suspend fun initialize(initialContext: EvaluationContext?) {
        coroutineScope {
            // Started before the children, not after: the terminal updateStatus() re-reads every
            // child's status so a late watcher would still converge, but a configuration change a
            // child reports while initializing carries no status and could not be recovered.
            watchChildren()
            try {
                childFeatureProviders
                    .map { child -> async { child.reportingItsOwnFailure { initialize(initialContext) } } }
                    .awaitAll()
            } finally {
                updateStatus()
            }
        }
    }

    /**
     * Runs one of [this] child's lifecycle calls, leaving the failure to the child.
     *
     * A child that fails reports its own error event and the aggregate reads it from the child's
     * status, so one failing child must not cancel the siblings that structured concurrency would
     * otherwise take down with it.
     */
    private suspend fun ChildFeatureProvider.reportingItsOwnFailure(
        work: suspend ChildFeatureProvider.() -> Unit
    ) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Reported by the child, observed through its status.
        }
    }

    /**
     * Subscribes to every child's events, synchronously.
     *
     * Undispatched, so each subscription is established before this returns: merely launching the
     * collectors would let a child report and finish before anyone was listening, and a child's
     * replay only carries its current status, so the transitions in between would be lost. The scope
     * outlives the call, since the children keep reporting after initialization.
     */
    private fun CoroutineScope.watchChildren() {
        // The scope is replaced, not just the job inside it: a fresh SupervisorJob per initialize
        // that is never cancelled leaves one root job behind each time. It deliberately is not a
        // child of initialize's scope — that would either make initialize hang waiting for the
        // collectors or stop them the moment it returned — but it does inherit its dispatcher.
        watchScope?.cancel(CancellationException("Child provider watch replaced by a new initialize call"))
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        watchScope = scope
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            childFeatureProviders.forEach { child ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    child.observe().collect { handleChildEvent(it) }
                }
            }
        }
    }

    /**
     * A child's event either changes the aggregate status or is a configuration change.
     *
     * The specification's Multi-Provider appendix asks for a configuration change to be re-emitted
     * whenever any provider reports one, and for an aggregate transition to carry the details of the
     * child event that triggered it.
     */
    private fun handleChildEvent(event: OpenFeatureProviderEvents) {
        if (event.toOpenFeatureStatus() == null) {
            statusTracker.send(event)
            // Re-aggregated on any child activity, not only on a status-carrying event: a child's
            // status can move without one, since shutting a child down resets it and not-ready has
            // no event to report.
            updateStatus()
        } else {
            updateStatus(event)
        }
    }

    /** Reports the aggregate status, carrying [trigger]'s details where one triggered the change. */
    private fun updateStatus(trigger: OpenFeatureProviderEvents? = null) {
        val aggregate = strategy.status(childFeatureProviders)
        val details = trigger?.eventDetails
        val current = statusTracker.status

        if (aggregate is OpenFeatureStatus.NotReady) {
            // No event describes not-ready, so the status is established directly. It therefore
            // reaches getStatus() but not observe() or statusFlow, which have no event to carry.
            if (current != OpenFeatureStatus.NotReady) statusTracker.reset()
            return
        }

        // A reconciliation in flight owns its own resolution. Reporting readiness here on the first
        // child's behalf would publish the outcome before the others had finished, and the real
        // outcome would then look like no change at all.
        if (aggregate is OpenFeatureStatus.Ready && current is OpenFeatureStatus.Reconciling) return

        val event = when (aggregate) {
            is OpenFeatureStatus.Ready -> OpenFeatureProviderEvents.ProviderReady(details)
            is OpenFeatureStatus.Stale -> OpenFeatureProviderEvents.ProviderStale(details)
            is OpenFeatureStatus.Reconciling -> OpenFeatureProviderEvents.ProviderReconciling(details)
            // The child's details are kept, as the appendix asks, with the aggregate's error over
            // the top: rebuilding from the status alone would drop flagsChanged and eventMetadata.
            is OpenFeatureStatus.Error -> OpenFeatureProviderEvents.ProviderError(
                details.describing(aggregate.error)
            )
            is OpenFeatureStatus.Fatal -> OpenFeatureProviderEvents.ProviderError(
                details.describing(aggregate.error, ErrorCode.PROVIDER_FATAL)
            )
            is OpenFeatureStatus.NotReady -> return
        }
        if (event.toOpenFeatureStatus() != current) statusTracker.send(event)
    }

    private fun OpenFeatureProviderEvents.EventDetails?.describing(
        error: OpenFeatureError,
        errorCode: ErrorCode = error.errorCode()
    ) = (this ?: OpenFeatureProviderEvents.EventDetails()).copy(
        message = error.message,
        errorCode = errorCode
    )

    /**
     * Shuts down all underlying providers.
     * This allows providers to clean up resources and complete any pending operations.
     */
    override fun shutdown() {
        watchScope?.cancel(CancellationException("Child provider watch cancelled due to shutdown"))
        watchScope = null
        statusTracker.reset()

        val shutdownErrors = mutableListOf<Pair<String, Throwable>>()
        childFeatureProviders.forEach { provider ->
            try {
                provider.shutdown()
            } catch (e: CancellationException) {
                throw e
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
    ) = statusTracker.reconciling {
        // The tracker owns the transitions: it reports reconciliation once across overlapping
        // context sets, reports only the outcome of the last one to terminate, and restores the
        // preceding status if they were all cancelled. Hand-rolling that here left the aggregate
        // stuck at Reconciling whenever a context set was cancelled.
        coroutineScope {
            childFeatureProviders
                .map { child -> async { child.reportingItsOwnFailure { onContextSet(oldContext, newContext) } } }
                .awaitAll()
        }
        updateStatus()
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

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
        return strategy.evaluate(
            childFeatureProviders,
            key,
            defaultValue,
            context,
            FeatureProvider::getLongEvaluation
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

    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    ) {
        val trackingErrors = mutableListOf<Pair<String, Throwable>>()
        childFeatureProviders.forEach { provider ->
            try {
                provider.track(trackingEventName, context, details)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                trackingErrors += provider.name to t
            }
        }

        if (trackingErrors.isNotEmpty()) {
            val message = buildString {
                append("One or more providers failed during track call: ")
                append(
                    trackingErrors.joinToString(separator = "\n") { (name, err) ->
                        "$name: ${err.message}"
                    }
                )
            }

            val aggregate = OpenFeatureError.GeneralError(message)
            trackingErrors.forEach { (name, err) ->
                aggregate.addSuppressed(RuntimeException("Provider '$name' tracking failed", err))
            }
            throw aggregate
        }
    }

    companion object {
        private const val MULTIPROVIDER_NAME = "multiprovider"
        private const val UNDEFINED_PROVIDER_NAME = "<unnamed>"
    }
}

/** Most severe wins, per the specification's Multi-Provider appendix. */
private val OpenFeatureStatus.severity: Int
    get() = when (this) {
        is OpenFeatureStatus.Fatal -> 5
        is OpenFeatureStatus.NotReady -> 4
        is OpenFeatureStatus.Error -> 3
        // Not in the appendix's list; treated as Stale is, since both mean "usable but not current".
        is OpenFeatureStatus.Reconciling -> 2
        is OpenFeatureStatus.Stale -> 2
        is OpenFeatureStatus.Ready -> 1
    }