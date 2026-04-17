package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

open class NoOpProvider(override val hooks: List<Hook<*>> = listOf()) : StateManagingProvider {
    override val metadata: ProviderMetadata = NoOpProviderMetadata("No-op provider")

    private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
    override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()

    // Ensure propagation to registered handlers
    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        _status.value = OpenFeatureStatus.Ready
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        _status.value = OpenFeatureStatus.NotReady
        events.tryEmit(
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

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

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