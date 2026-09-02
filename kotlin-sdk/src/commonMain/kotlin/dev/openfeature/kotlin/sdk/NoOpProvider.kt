package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow

/** The default provider: it resolves every flag to the passed-in default and reports itself ready. */
open class NoOpProvider(override val hooks: List<Hook<*>> = listOf()) : FeatureProvider {
    private val statusTracker = ProviderStatusTracker()

    override val metadata: ProviderMetadata = NoOpProviderMetadata("No-op provider")

    override val status: OpenFeatureStatus get() = statusTracker.status

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        statusTracker.reset()
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // Nothing is cached, so there is nothing to reconcile and nothing to report.
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        return ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
    }

    data class NoOpProviderMetadata(override val name: String?) : ProviderMetadata
}