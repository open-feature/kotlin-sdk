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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
         * Aggregates the statuses of [providers] into the MultiProvider's own status. The default
         * reports the most severe, in the order the specification's Multi-Provider appendix defines.
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

    private val watchLock = SynchronizedObject()
    private var watchScope: CoroutineScope? = null

    private val statusLock = SynchronizedObject()
    private var openReconciliations = 0

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
            // child's status so a late watcher would still converge.
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
     * Runs one of [this] child's lifecycle calls, leaving the failure to the child: it reports its
     * own error event, so one failing child must not cancel the siblings.
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
     * Subscribes to every child's events, undispatched so each subscription is established before
     * this returns: a child could otherwise report and finish before anyone was listening, and its
     * replay only carries the status it settled on.
     */
    private fun CoroutineScope.watchChildren() {
        // Not a child of initialize's scope, which would either make initialize hang waiting for the
        // collectors or stop them the moment it returned, but it does inherit its dispatcher.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        // Installed under the lock because shutdown runs on the retirement scope, not on the
        // dispatcher initialize was entered on, and has to see the scope it must cancel.
        val replaced = synchronized(watchLock) {
            val replaced = watchScope
            watchScope = scope
            replaced
        }
        replaced?.cancel(CancellationException("Child provider watch replaced by a new initialize call"))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            childFeatureProviders.forEach { child ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    child.observe().collect { handleChildEvent(it) }
                }
            }
        }
    }

    /**
     * A child's event either changes the aggregate status or is a configuration change, which the
     * specification's Multi-Provider appendix asks to be re-emitted whenever a child reports one.
     */
    private fun handleChildEvent(event: OpenFeatureProviderEvents) {
        // Re-aggregated on any child activity, a stateless event included: shutting a child down
        // moves its status to not-ready, which has no event to report.
        updateStatus(event)
        // Forwarded after the aggregate has settled, so a subscriber that re-reads the status on
        // this event sees the aggregate it triggered rather than the one it replaced.
        if (event.toOpenFeatureStatus() == null) statusTracker.send(event)
    }

    /**
     * Reports the aggregate status, carrying [trigger]'s details where one triggered the change.
     *
     * Aggregating and reporting are one critical section: two children transitioning concurrently
     * would otherwise let the thread holding the older aggregate report last.
     */
    private fun updateStatus(trigger: OpenFeatureProviderEvents? = null) = synchronized(statusLock) {
        val aggregate = strategy.status(childFeatureProviders)
        val details = trigger?.eventDetails
        val current = statusTracker.status

        if (aggregate is OpenFeatureStatus.NotReady) {
            // No event describes not-ready, so it reaches getStatus() but not observe().
            if (current != OpenFeatureStatus.NotReady) statusTracker.reset()
            return@synchronized
        }

        // Only this provider's own reconciliation owns its resolution, and it reports the outcome
        // itself once every child has finished. A child reconciling on its own account has no such
        // resolution to wait for, so its return to readiness must be reported here.
        if (aggregate is OpenFeatureStatus.Ready && openReconciliations > 0) return@synchronized

        val event = when (aggregate) {
            is OpenFeatureStatus.Ready -> OpenFeatureProviderEvents.ProviderReady(details)
            is OpenFeatureStatus.Stale -> OpenFeatureProviderEvents.ProviderStale(details)
            is OpenFeatureStatus.Reconciling -> OpenFeatureProviderEvents.ProviderReconciling(details)
            // The child's details are kept with the aggregate's error over the top: rebuilding from
            // the status alone would drop flagsChanged and eventMetadata.
            is OpenFeatureStatus.Error -> OpenFeatureProviderEvents.ProviderError(
                details.describing(aggregate.error)
            )
            is OpenFeatureStatus.Fatal -> OpenFeatureProviderEvents.ProviderError(
                details.describing(aggregate.error, ErrorCode.PROVIDER_FATAL)
            )
            is OpenFeatureStatus.NotReady -> return@synchronized
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
        val watching = synchronized(watchLock) {
            val watching = watchScope
            watchScope = null
            watching
        }
        watching?.cancel(CancellationException("Child provider watch cancelled due to shutdown"))
        statusTracker.reset()

        val shutdownErrors = mutableListOf<Pair<String, Throwable>>()
        childFeatureProviders.forEach { provider ->
            try {
                provider.shutdown()
            } catch (t: Throwable) {
                // A CancellationException too: this is not a suspending function, so one from a
                // child is an ordinary failure, and rethrowing it would abandon the children after.
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
        synchronized(statusLock) { openReconciliations++ }
        try {
            statusTracker.reconciling {
                coroutineScope {
                    childFeatureProviders
                        .map { child ->
                            async { child.reportingItsOwnFailure { onContextSet(oldContext, newContext) } }
                        }
                        .awaitAll()
                }
                updateStatus()
            }
        } finally {
            // Dropped only once the tracker has reported the outcome, which its own finally does
            // before this one runs.
            synchronized(statusLock) { openReconciliations-- }
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
            } catch (t: Throwable) {
                // Collected rather than rethrown, for the same reason as in shutdown.
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