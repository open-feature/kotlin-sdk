package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

open class NoOpProvider(override val hooks: List<Hook<*>> = listOf()) : StateManagingProvider {
    override val metadata: ProviderMetadata = NoOpProviderMetadata("No-op provider")

    private val statusTracker = ProviderStatusTracker()
    override val status: StateFlow<OpenFeatureStatus> = statusTracker.status

    override suspend fun initialize(initialContext: EvaluationContext?) {
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        statusTracker.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "No-op provider shut down; not ready for evaluation",
                    errorCode = ErrorCode.PROVIDER_NOT_READY
                )
            )
        )
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // no-op
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

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